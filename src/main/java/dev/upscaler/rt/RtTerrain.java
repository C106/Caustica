package dev.upscaler.rt;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.upscaler.UpscalerMod;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

/**
 * P1 step 4a: extract real block-model geometry around the player. Each non-air MODEL block is
 * tessellated through vanilla's {@link ModelBlockRenderer} into a capturing {@link BlockQuadOutput},
 * giving correct shapes and neighbour-culled faces without touching the (refactored) model API
 * directly. Positions are rebased to the player's block.
 *
 * <p>Per-vertex stream: position (BLAS). Per-primitive stream ({@code material}, indexed by
 * gl_PrimitiveID): geometric normal + albedo. Albedo is white for now — biome tint + atlas UV
 * textures come in step 4b.
 */
public final class RtTerrain {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.terrain", "true"));
    private static final int RADIUS = Integer.getInteger("upscaler.rt.terrainRadius", 16);

    private static RtTerrain instance;

    private final RtBuffer positions;
    private final RtBuffer indices;
    private final RtBuffer material; // per-primitive: {vec4 normal, vec4 albedo}
    private final RtAccel blas;
    private final RtAccel tlas;
    public final int blockX;
    public final int blockY;
    public final int blockZ;

    private RtTerrain(RtBuffer positions, RtBuffer indices, RtBuffer material, RtAccel blas, RtAccel tlas, int bx, int by, int bz) {
        this.positions = positions;
        this.indices = indices;
        this.material = material;
        this.blas = blas;
        this.tlas = tlas;
        this.blockX = bx;
        this.blockY = by;
        this.blockZ = bz;
    }

    public static RtTerrain currentOrNull() {
        return instance;
    }

    public long tlas() {
        return tlas.handle;
    }

    /** Device address of the per-primitive material buffer ({vec4 normal, vec4 albedo} per triangle). */
    public long primAddress() {
        return material.deviceAddress;
    }

    public static boolean extractAroundPlayer(RtContext ctx) {
        if (!ENABLED) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return false; // not in a world yet
        }
        BlockPos center = mc.player.blockPosition();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
        ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
        BlockAndTintGetter view = new LevelView(level);

        QuadCapture capture = new QuadCapture();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int blocks = 0;

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    m.set(cx + dx, cy + dy, cz + dz);
                    BlockState state = level.getBlockState(m);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BlockStateModel model = modelSet.get(state);
                    if (model == null) {
                        continue;
                    }
                    try {
                        renderer.tesselateBlock(capture, dx, dy, dz, view, m, state, model, state.getSeed(m));
                        blocks++;
                    } catch (Throwable t) {
                        // skip a block whose model rendering throws rather than failing the whole snapshot
                    }
                }
            }
        }

        if (capture.idx.isEmpty()) {
            UpscalerMod.LOGGER.info("RT terrain: no model geometry around ({},{},{}) — skipping", cx, cy, cz);
            return false;
        }

        int vertCount = capture.verts.size() / 3;
        int idxCount = capture.idx.size();
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        RtBuffer positions = ctx.createBuffer((long) capture.verts.size() * Float.BYTES, asInput, true);
        RtBuffer indices = ctx.createBuffer((long) capture.idx.size() * Integer.BYTES, asInput, true);
        RtBuffer material = ctx.createBuffer((long) capture.prim.size() * Float.BYTES,
                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        MemoryUtil.memFloatBuffer(positions.mapped, capture.verts.size()).put(capture.verts.elements(), 0, capture.verts.size());
        MemoryUtil.memIntBuffer(indices.mapped, capture.idx.size()).put(capture.idx.elements(), 0, capture.idx.size());
        MemoryUtil.memFloatBuffer(material.mapped, capture.prim.size()).put(capture.prim.elements(), 0, capture.prim.size());

        RtAccel blas = RtAccel.buildTrianglesBlas(ctx, positions, vertCount, indices, idxCount);
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
        RtAccel tlas = RtAccel.buildTlas(ctx, List.of(new RtAccel.Instance(identity, blas.deviceAddress)));

        if (instance != null) {
            instance.destroy();
        }
        instance = new RtTerrain(positions, indices, material, blas, tlas, cx, cy, cz);
        UpscalerMod.LOGGER.info("RT terrain: {} blocks -> {} triangles ({} verts) around ({},{},{}); BLAS+TLAS built",
                blocks, idxCount / 3, vertCount, cx, cy, cz);
        return true;
    }

    public void destroy() {
        tlas.destroy();
        blas.destroy();
        material.destroy();
        indices.destroy();
        positions.destroy();
        if (instance == this) {
            instance = null;
        }
    }

    /** Captures the quads vanilla's model renderer emits into RT position/index/material streams. */
    private static final class QuadCapture implements BlockQuadOutput {
        final FloatArrayList verts = new FloatArrayList();
        final IntArrayList idx = new IntArrayList();
        final FloatArrayList prim = new FloatArrayList(); // 8 floats/triangle: normal.xyz0, albedo.rgb0

        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            int base = verts.size() / 3;
            Vector3fc p0 = quad.position(0);
            Vector3fc p1 = quad.position(1);
            Vector3fc p2 = quad.position(2);
            Vector3fc p3 = quad.position(3);
            addVertex(p0, x, y, z);
            addVertex(p1, x, y, z);
            addVertex(p2, x, y, z);
            addVertex(p3, x, y, z);
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);

            float ex1 = p1.x() - p0.x(), ey1 = p1.y() - p0.y(), ez1 = p1.z() - p0.z();
            float ex2 = p2.x() - p0.x(), ey2 = p2.y() - p0.y(), ez2 = p2.z() - p0.z();
            float nx = ey1 * ez2 - ez1 * ey2;
            float ny = ez1 * ex2 - ex1 * ez2;
            float nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            for (int t = 0; t < 2; t++) { // one {normal, albedo} record per triangle
                prim.add(nx);
                prim.add(ny);
                prim.add(nz);
                prim.add(0f);
                prim.add(1f);
                prim.add(1f);
                prim.add(1f);
                prim.add(0f);
            }
        }

        private void addVertex(Vector3fc p, float x, float y, float z) {
            verts.add(p.x() + x);
            verts.add(p.y() + y);
            verts.add(p.z() + z);
        }
    }

    /** Minimal {@link BlockAndTintGetter} over the client level so the model renderer can cull + tint. */
    private record LevelView(ClientLevel level) implements BlockAndTintGetter {
        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver color) {
            return level.getBlockTint(pos, color);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return level.getLightEngine();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return level.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return level.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return level.getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinY() {
            return level.getMinY();
        }
    }
}
