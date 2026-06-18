package dev.upscaler.rt;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkDestroyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;

/**
 * A built acceleration structure (BLAS or TLAS) plus its backing buffer. Build with the static
 * factories; free with {@link #destroy()}. This is the unit P1's chunk lifecycle manages
 * (one BLAS per section, one TLAS rebuilt per frame).
 */
public final class RtAccel {
    public final long handle;
    public final long deviceAddress;

    private final RtBuffer backing;
    private final boolean ownsBacking;
    private final VkDevice vk;
    private boolean destroyed;

    private RtAccel(VkDevice vk, long handle, long deviceAddress, RtBuffer backing) {
        this(vk, handle, deviceAddress, backing, true);
    }

    private RtAccel(VkDevice vk, long handle, long deviceAddress, RtBuffer backing, boolean ownsBacking) {
        this.vk = vk;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.backing = backing;
        this.ownsBacking = ownsBacking;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (handle != 0L) {
            vkDestroyAccelerationStructureKHR(vk, handle, null);
        }
        // A pooled BLAS's backing is owned by RtBufferPool (recycled, not destroyed here).
        if (ownsBacking) {
            backing.destroy();
        }
        destroyed = true;
    }

    /**
     * A BLAS whose AS + backing buffer are allocated but whose build command is recorded later, so
     * many sections' builds can be batched into one submission — one {@code vkQueueSubmit} + fence
     * wait per tick instead of one per section (each submit drains the graphics queue, so per-section
     * submits were the dominant terrain-streaming stall).
     * {@code opaque} marks geometry {@code OPAQUE} (solid, no any-hit) vs
     * {@code NO_DUPLICATE_ANY_HIT_INVOCATION} for alpha-tested cutout.
     */
    public static final class PreparedBlas {
        public final RtAccel accel;
        private final RtBuffer scratch;
        // Non-null only for a pooled BLAS (see prepareTrianglesBlasPooled): the AS backing buffer, owned
        // by RtBufferPool, so releaseBlasToPool can return it rather than destroying it.
        private final RtBuffer pooledBacking;
        private final long vertexAddr;
        private final long indexAddr;
        private final int maxVertex;
        private final int triangleCount;
        private final boolean opaque;
        // P5-perf #1 (step 2): refit support. {@code updatable} = built with ALLOW_UPDATE (so it can be
        // refit later); {@code update} = this recorded op is an in-place UPDATE (refit) rather than a full
        // BUILD. Set for the entity refit path; false for terrain + pooled block entities.
        private final boolean updatable;
        private final boolean update;

        private PreparedBlas(RtAccel accel, RtBuffer scratch, RtBuffer pooledBacking, long vertexAddr, long indexAddr,
                             int maxVertex, int triangleCount, boolean opaque, boolean updatable, boolean update) {
            this.accel = accel;
            this.scratch = scratch;
            this.pooledBacking = pooledBacking;
            this.vertexAddr = vertexAddr;
            this.indexAddr = indexAddr;
            this.maxVertex = maxVertex;
            this.triangleCount = triangleCount;
            this.opaque = opaque;
            this.updatable = updatable;
            this.update = update;
        }
    }

    /**
     * Result of {@link #prepareUpdatableBlasBuild}: the per-frame BUILD op to record, plus the persistent
     * resources the caller's per-entity ring must keep ({@code backing}) and cache ({@code updateScratchSize}
     * for sizing later refit scratch). The {@code scratch} is this frame's transient build scratch (release
     * at the frames-in-flight horizon, like the mesh buffers); the {@code op.accel} + {@code backing} persist.
     */
    public record UpdatableBuild(PreparedBlas op, RtAccel accel, RtBuffer backing, RtBuffer scratch, long updateScratchSize) {
    }

    /** Allocate a BLAS (AS + backing + scratch) and query sizes, but defer the build to {@link #recordBlasBuilds}. */
    public static PreparedBlas prepareTrianglesBlas(RtContext ctx, RtBuffer positions, int vertexCount,
                                                    RtBuffer indices, int indexCount, boolean opaque) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, positions, indices, vertexCount, indexCount, opaque, false);
            RtBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
            RtBuffer scratch = ctx.createBuffer(sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            RtAccel accel = createBlasOn(vk, stack, backing, sizes.accelerationStructureSize(), true);
            return new PreparedBlas(accel, scratch, null, positions.deviceAddress, indices.deviceAddress, vertexCount - 1, indexCount / 3, opaque, false, false);
        }
    }

    /**
     * Pooled variant of {@link #prepareTrianglesBlas} for the per-frame entity path: the AS backing +
     * scratch buffers come from {@code pool} (recycled, not freshly allocated). The BLAS is reclaimed with
     * {@link #releaseBlasToPool} (NOT {@code freeBlasScratch} + {@code accel.destroy()}). Used only by
     * {@link RtEntities}; the terrain path keeps {@link #prepareTrianglesBlas}.
     */
    public static PreparedBlas prepareTrianglesBlasPooled(RtContext ctx, RtBufferPool pool, RtBuffer positions, int vertexCount,
                                                          RtBuffer indices, int indexCount, boolean opaque) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, positions, indices, vertexCount, indexCount, opaque, false);
            // acquire() returns capacity ≥ requested size; the AS is created with the exact queried size.
            RtBuffer backing = pool.acquire(ctx, sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
            RtBuffer scratch = pool.acquire(ctx, sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            RtAccel accel = createBlasOn(vk, stack, backing, sizes.accelerationStructureSize(), false);
            return new PreparedBlas(accel, scratch, backing, positions.deviceAddress, indices.deviceAddress, vertexCount - 1, indexCount / 3, opaque, false, false);
        }
    }

    /**
     * P5-perf #1 (step 2): create a new <em>updatable</em> (ALLOW_UPDATE) BLAS sized for this mesh and a
     * pool-backed backing buffer, and prepare its initial full BUILD. The {@code accel} + {@code backing}
     * persist in the caller's per-entity ring (NOT released per frame); later frames refit it with {@link
     * #refitUpdate} (cheap in-place UPDATE) while the topology is stable, and free it with {@link
     * #destroyPooledAccel} on eviction / topology change.
     */
    public static UpdatableBuild prepareUpdatableBlasBuild(RtContext ctx, RtBufferPool pool, RtBuffer positions, int vertexCount,
                                                           RtBuffer indices, int indexCount, boolean opaque) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, positions, indices, vertexCount, indexCount, opaque, true);
            long accelSize = sizes.accelerationStructureSize();
            long updateScratch = sizes.updateScratchSize();
            RtBuffer backing = pool.acquire(ctx, accelSize, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
            RtBuffer scratch = pool.acquire(ctx, sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            RtAccel accel = createBlasOn(vk, stack, backing, accelSize, false);
            PreparedBlas op = new PreparedBlas(accel, scratch, backing, positions.deviceAddress, indices.deviceAddress,
                    vertexCount - 1, indexCount / 3, opaque, true, false);
            return new UpdatableBuild(op, accel, backing, scratch, updateScratch);
        }
    }

    /**
     * P5-perf #1 (step 2): prepare an in-place refit (UPDATE) of an existing updatable BLAS with new vertex
     * data of the SAME topology. {@code scratch} (sized {@code updateScratchSize}) and the mesh buffers are
     * caller-owned per-frame transients; the {@code accel} persists. Records nothing on its own — returned
     * to {@link #recordBlasBuilds} like a BUILD.
     */
    public static PreparedBlas refitUpdate(RtAccel accel, RtBuffer scratch, long vertexAddr, long indexAddr,
                                           int vertexCount, int indexCount, boolean opaque) {
        return new PreparedBlas(accel, scratch, null, vertexAddr, indexAddr, vertexCount - 1, indexCount / 3, opaque, true, true);
    }

    /** Reclaim a pooled BLAS: destroy its AS handle and return its backing + scratch buffers to the pool. */
    public static void releaseBlasToPool(RtBufferPool pool, PreparedBlas blas) {
        blas.accel.destroy(); // ownsBacking == false → destroys only the AS handle, not the backing buffer
        pool.release(blas.pooledBacking);
        pool.release(blas.scratch);
    }

    /** Destroy a pool-backed (updatable-entity) AS: destroy the handle, return its backing buffer to the pool. */
    public static void destroyPooledAccel(RtBufferPool pool, RtAccel accel, RtBuffer backing) {
        accel.destroy(); // ownsBacking == false → handle only
        pool.release(backing);
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryBlasSizes(VkDevice vk, MemoryStack stack, RtBuffer positions,
                                                                           RtBuffer indices, int vertexCount, int indexCount, boolean opaque, boolean allowUpdate) {
        VkAccelerationStructureGeometryKHR.Buffer geom = triangleGeometry(stack, positions.deviceAddress,
                indices.deviceAddress, vertexCount, opaque);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(allowUpdate))
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geom);
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), stack.ints(indexCount / 3), sizes);
        return sizes;
    }

    private static int buildFlags(boolean allowUpdate) {
        return VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                | (allowUpdate ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR : 0);
    }

    private static RtAccel createBlasOn(VkDevice vk, MemoryStack stack, RtBuffer backing, long accelSize, boolean ownsBacking) {
        VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(backing.handle).offset(0).size(accelSize).type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        java.nio.LongBuffer pAs = stack.mallocLong(1);
        RtContext.check(vkCreateAccelerationStructureKHR(vk, ci, null, pAs), "vkCreateAccelerationStructureKHR");
        long handle = pAs.get(0);
        VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                .sType$Default().accelerationStructure(handle);
        long deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo);
        return new RtAccel(vk, handle, deviceAddress, backing, ownsBacking);
    }

    private static VkAccelerationStructureGeometryKHR.Buffer triangleGeometry(MemoryStack stack, long vertexAddr, long indexAddr, int vertexCount, boolean opaque) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geom.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
        var tri = geom.geometry().triangles();
        tri.sType$Default()
                .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT).vertexStride(3L * Float.BYTES)
                .maxVertex(vertexCount - 1).indexType(VK10.VK_INDEX_TYPE_UINT32);
        tri.vertexData().deviceAddress(vertexAddr);
        tri.indexData().deviceAddress(indexAddr);
        return geom;
    }

    /**
     * A TLAS instance: a 3x4 row-major transform, the device address of its BLAS, and the 24-bit
     * {@code instanceCustomIndex} the hit shaders read. Terrain passes its section-table index;
     * dynamic entities set the high {@code ENTITY_BIT} flag so the hit shader takes the entity path.
     */
    public record Instance(float[] transform3x4, long blasDeviceAddress, int customIndex) {
    }

    /** A TLAS whose AS + backing + instance buffer are allocated but whose build is recorded later. */
    public static final class PreparedTlas {
        public final RtAccel accel;
        private final RtBuffer instanceBuffer;
        private final RtBuffer scratch;
        private final int instanceCount;

        private PreparedTlas(RtAccel accel, RtBuffer instanceBuffer, RtBuffer scratch, int instanceCount) {
            this.accel = accel;
            this.instanceBuffer = instanceBuffer;
            this.scratch = scratch;
            this.instanceCount = instanceCount;
        }

        /**
         * Free the TLAS, its instance buffer, and its scratch. For a per-frame TLAS the whole bundle
         * is retired together once the frame that traced it is no longer in flight (the instance +
         * scratch buffers are still read by the recorded build, and the AS by the recorded trace).
         */
        public void destroyAll() {
            accel.destroy();
            instanceBuffer.destroy();
            scratch.destroy();
        }
    }

    /** Allocate a TLAS (AS + backing + filled instance buffer + scratch), deferring the build. */
    public static PreparedTlas prepareTlas(RtContext ctx, List<Instance> instances) {
        VkDevice vk = ctx.vk();
        int count = instances.size();
        RtBuffer instanceBuffer = ctx.createBuffer((long) VkAccelerationStructureInstanceKHR.SIZEOF * count,
                org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Reuse a single record + transform buffer across all instances: allocating per-instance
            // on the MemoryStack (64 KB/thread) overflows it once there are hundreds of sections.
            VkAccelerationStructureInstanceKHR rec = VkAccelerationStructureInstanceKHR.calloc(stack);
            java.nio.FloatBuffer xform = stack.mallocFloat(12);
            for (int i = 0; i < count; i++) {
                Instance inst = instances.get(i);
                xform.clear();
                xform.put(inst.transform3x4()).flip();
                rec.transform().matrix(xform);
                rec.instanceCustomIndex(inst.customIndex()).mask(0xFF).instanceShaderBindingTableRecordOffset(0)
                        .flags(0x00000001) // VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR
                        .accelerationStructureReference(inst.blasDeviceAddress());
                MemoryUtil.memCopy(rec.address(), instanceBuffer.mapped + (long) i * VkAccelerationStructureInstanceKHR.SIZEOF,
                        VkAccelerationStructureInstanceKHR.SIZEOF);
            }

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = tlasBuildInfo(stack, instanceBuffer.deviceAddress);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0), stack.ints(count), sizes);

            RtBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
            VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(backing.handle).offset(0).size(sizes.accelerationStructureSize()).type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            java.nio.LongBuffer pAs = stack.mallocLong(1);
            RtContext.check(vkCreateAccelerationStructureKHR(vk, ci, null, pAs), "vkCreateAccelerationStructureKHR");
            long handle = pAs.get(0);
            RtBuffer scratch = ctx.createBuffer(sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default().accelerationStructure(handle);
            long deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo);
            return new PreparedTlas(new RtAccel(vk, handle, deviceAddress, backing), instanceBuffer, scratch, count);
        }
    }

    private static VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasBuildInfo(MemoryStack stack, long instanceBufferAddr) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geom.sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geom.geometry().instances().sType$Default().arrayOfPointers(false);
        geom.geometry().instances().data().deviceAddress(instanceBufferAddr);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geom);
        return build;
    }

    /** Record every prepared BLAS build into the command buffer (independent builds, no barriers between them). */
    public static void recordBlasBuilds(VkCommandBuffer cmd, List<PreparedBlas> blas) {
        for (PreparedBlas b : blas) {
            try (MemoryStack stack = MemoryStack.stackPush()) { // per-iteration: avoid 64 KB stack overflow
                recordBlasBuild(cmd, stack, b);
            }
        }
    }

    /** Free the transient scratch buffers of a set of prepared BLAS (only after their build completed). */
    public static void freeBlasScratch(List<PreparedBlas> blas) {
        for (PreparedBlas b : blas) {
            b.scratch.destroy();
        }
    }

    /**
     * Record a single TLAS build into the command buffer. The caller is responsible for the AS-build →
     * ray-trace barrier before tracing (and, when BLAS are built in the same submission, the
     * BLAS-write → AS-read barrier before this). Drives the per-frame TLAS rebuild in {@link
     * RtComposite} that merges static terrain instances with dynamic ones.
     */
    public static void recordTlasBuild(VkCommandBuffer cmd, PreparedTlas tlas) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = tlasBuildInfo(stack, tlas.instanceBuffer.deviceAddress);
            build.get(0).dstAccelerationStructure(tlas.accel.handle);
            build.get(0).scratchData().deviceAddress(tlas.scratch.deviceAddress);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(tlas.instanceCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
            PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());
            vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange);
        }
    }

    private static void recordBlasBuild(VkCommandBuffer cmd, MemoryStack stack, PreparedBlas b) {
        VkAccelerationStructureGeometryKHR.Buffer geom = triangleGeometry(stack, b.vertexAddr, b.indexAddr, b.maxVertex + 1, b.opaque);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(b.updatable))
                .mode(b.update ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1).pGeometries(geom)
                .dstAccelerationStructure(b.accel.handle);
        if (b.update) {
            // In-place refit: the existing (off-queue) AS is both source and destination. The flags +
            // topology (primitiveCount/maxVertex) must match its original ALLOW_UPDATE build.
            build.get(0).srcAccelerationStructure(b.accel.handle);
        }
        build.get(0).scratchData().deviceAddress(b.scratch.deviceAddress);
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(b.triangleCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
        PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());
        vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange);
    }
}
