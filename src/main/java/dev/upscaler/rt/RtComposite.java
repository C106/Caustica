package dev.upscaler.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.upscaler.UpscalerMod;
import dev.upscaler.client.SodiumCompat;
import dev.upscaler.client.UpscalerJitter;
import dev.upscaler.mixin.CommandEncoderAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * On-screen composite. Each frame, ray-trace into a render-res storage image (+ guide buffers), use
 * DLSS Ray Reconstruction to denoise and upscale it to display res, write that into a storage-capable
 * copy of the world color, and copy the result back to the world target at the
 * end-of-world seam. Gated by {@code -Dupscaler.rt.composite=true}.
 *
 * <p>P4.2b resolution split: the path tracer and its guide buffers run at {@link #RENDER_SCALE} of
 * display res with a per-frame sub-pixel camera jitter; DLSS-RR ({@link RtDlssRr}) reconstructs the
 * display-res image. With RR disabled the trace runs at 1:1 and a linear blit stands in for the
 * upscale (a raw, noisy reference). Output selection is controlled by {@code -Dupscaler.rt.output=rt|vanilla}.
 *
 * <p>Traces the extracted {@link RtTerrain} with perspective camera rays (camera matrices captured
 * each frame via {@link #captureFrame}); writes nothing until terrain is available.
 * Pipelines/SBT/descriptors are built once; sized images rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.composite", "false"));

    // invViewProj(64) + camOffset(@64) + sectionTableAddr(@80) + debugView(@88) + frameIndex(@92)
    // + prevViewProj(@96) + camDelta(@160) + spp(@172) + jitter(@176) + entityTableAddr(@184)
    // + flags(@192): P6.1 bit 0 = camera submerged, bit 1 = PBR BRDF enabled
    // + P6.3 dynamic sky (16-byte aligned vec4s): sunDir+dayFactor(@208) + lightDir(@224) + lightRadiance(@240)
    private static final int WORLD_PUSH_SIZE = 256;
    private static final int GUIDE_COUNT = 5; // P4 guide buffers bound at world-pipeline bindings 3..7
    // Frames a retired per-frame TLAS must outlive before it's freed (> frames-in-flight); matches
    // RtTerrain's deferred-free horizon. The frame TLAS is built + traced this frame, then freed once
    // the composite frame counter has advanced this far past it (so no in-flight frame still reads it).
    private static final int KEEP_FRAMES = 4;

    /** Debug guide-buffer view: 0 = normal render, 1 = normals, 2 = albedo, 3 = depth, 4 = roughness. */
    public static final int DEBUG_VIEW = Integer.getInteger("upscaler.rt.debugView", 0);
    /** Samples per pixel per frame. Default 1: DLSS-RR denoises ~1 spp; raise for the no-RR reference. */
    public static final int SPP = Math.max(1, Integer.getInteger("upscaler.rt.spp", 1));
    /**
     * P6.3 dynamic sky: drive the sun/moon direction, light colour and sky gradient from the game's time
     * of day. {@code -Dupscaler.rt.dynamicSky=false} pins the legacy fixed noon sun for a clean A/B.
     */
    public static final boolean DYNAMIC_SKY = Boolean.parseBoolean(System.getProperty("upscaler.rt.dynamicSky", "true"));
    /**
     * P6.3b soft shadows: give the sun/moon a finite angular size so NEE shadow rays sample the light's
     * disk (soft, contact-hardening penumbrae). {@code -Dupscaler.rt.softShadows=false} -> hard shadows.
     * Radii in degrees; the real sun/moon are ~0.27° but a touch larger reads as a pleasant penumbra.
     */
    public static final boolean SOFT_SHADOWS = Boolean.parseBoolean(System.getProperty("upscaler.rt.softShadows", "true"));
    private static final float SUN_ANGULAR_RADIUS = (float) Math.toRadians(Double.parseDouble(System.getProperty("upscaler.rt.sunAngularRadius", "0.6")));
    private static final float MOON_ANGULAR_RADIUS = (float) Math.toRadians(Double.parseDouble(System.getProperty("upscaler.rt.moonAngularRadius", "1.5")));
    private static final float SUN_NOON_SOUTH_TILT = (float) Math.toRadians(Double.parseDouble(System.getProperty("upscaler.rt.sunNoonSouthDeg", "0.0")));
    private static final float SUN_NOON_Y = Mth.cos(SUN_NOON_SOUTH_TILT);
    private static final float SUN_NOON_Z = Mth.sin(SUN_NOON_SOUTH_TILT);
    /**
     * P4.2b RT trace scale: the path tracer + guide buffers run at this fraction of display resolution
     * and DLSS-RR upscales to display. Only applied when {@link RtDlssRr#ENABLED}; the no-RR reference
     * traces at 1.0 (a 1:1 blit). Default 1/1.5 matches DLSS MaxQuality. {@code -Dupscaler.rt.renderScale}.
     */
    public static final float RENDER_SCALE = parseRenderScale();
    // Sign of the sub-pixel jitter as reported to DLSS-RR + applied to the primary ray, mirroring the
    // validated DLSS-SR convention (Vulkan flipped clip space wants Y negated). Tune in P4.3.
    private static final float JITTER_SIGN_X = Float.parseFloat(System.getProperty("upscaler.rt.jitterSignX", "1"));
    private static final float JITTER_SIGN_Y = Float.parseFloat(System.getProperty("upscaler.rt.jitterSignY", "-1"));

    private static float parseRenderScale() {
        try {
            return Math.clamp(Float.parseFloat(System.getProperty("upscaler.rt.renderScale", "0.5")), 0.25f, 1f);
        } catch (NumberFormatException e) {
            return 0.5f;
        }
    }

    // Monotonic per-composite frame counter, used by RtTerrain to time frames-in-flight-safe frees.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    private RtPipeline worldPipeline;
    // Set at the HEAD of Minecraft.reloadResourcePacks() (mixin): a resource reload recreates the block
    // atlas + entity textures. We tear down the world pipeline there (drops all descriptor references) and
    // rebuild it once the NEW atlas is in place — detected by the atlas view handle changing away from
    // boundAtlasHandle to a fresh non-zero value (MC's deferred free keeps the old handle live for a few
    // frames, so "handle != 0" alone isn't enough to tell old from new).
    private volatile boolean reloadRebindRequested;
    // The block-atlas view handle currently bound into the world pipeline (set by bindWorldTextures).
    private long boundAtlasHandle;
    // P6.4: the world push data (256 B) is uploaded to a host-visible BDA ring instead of inline push
    // constants, which were maxed at the 256-byte NVIDIA ceiling. Only the 8-byte slot address is pushed
    // now (see WorldPushRef in the world shaders). One slot per in-flight frame, cycled per frame so an
    // in-flight slot is never overwritten; created with the world pipeline and freed at shutdown.
    private static final int PUSH_RING = 6;
    private RtBuffer[] pushRing;
    private int pushSlot;
    private RtDisplayPipeline displayPipeline;
    private RtImage output;
    private RtImage displayImage;
    // P4 guide buffers (first-hit attributes for the denoiser/DLSS-RR): normal+roughness, albedo, depth, motion.
    private RtImage gNormal;
    private RtImage gAlbedo;
    private RtImage gDepth;
    private RtImage gMotion;
    private RtImage gSpecAlbedo;
    // Display-res RT image the display mapper reads: DLSS-RR writes it (render -> display denoise+upscale), or a
    // linear blit of `output` fills it when RR is off/unavailable (the no-RR reference / fallback).
    private RtImage rrOutput;
    private final RtExposure exposure = new RtExposure();

    // P4.2b resolution split: the trace + guide buffers run at render res, the composite at display res.
    private int displayW = -1;
    private int displayH = -1;
    private int renderW = -1;
    private int renderH = -1;

    // Motion-vector reprojection state (P4.0b): the previous frame's camera-relative view-projection
    // and camera position, read into the push constant each frame then advanced at frame end.
    private final Matrix4f mvPrevProjView = new Matrix4f();
    private final Matrix4f mvCurProjView = new Matrix4f();
    private final Matrix4f mvPushMatrix = new Matrix4f();
    private double mvPrevCamX;
    private double mvPrevCamY;
    private double mvPrevCamZ;
    private float mvCamDeltaX;
    private float mvCamDeltaY;
    private float mvCamDeltaZ;
    private boolean mvHasPrev;
    private long atlasSampler;
    private boolean failed;
    private boolean loggedActive;

    // Camera captured each frame from GameRenderer (unjittered level projection + camera rotation + pos).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private double camX;
    private double camY;
    private double camZ;
    private boolean frameCaptured;

    // Per-frame TLASes awaiting a frames-in-flight-safe free (the build + trace that used them must
    // finish first). Each is retired at the composite frame counter it was built on + KEEP_FRAMES.
    private final java.util.List<DeferredTlas> deferredTlas = new java.util.ArrayList<>();

    /** A per-frame TLAS retired for a frames-in-flight-safe free once {@code freeFrame} is reached. */
    private record DeferredTlas(long freeFrame, RtAccel.PreparedTlas tlas) {
    }

    private RtComposite() {
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /** Capture the frame's camera for the next composite. Called from GameRendererMixin. */
    public void captureFrame(Matrix4f projection, Matrix4fc viewRotation, double cameraX, double cameraY, double cameraZ) {
        frameProjection.set(projection);
        frameViewRotation.set(viewRotation);
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        frameCaptured = true;
    }

    public boolean composite(GpuTexture nativeColor, int width, int height) {
        frameCounter++; // advances once per frame; RtTerrain retires resources relative to it
        if (failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }
        processDeferredTlasFrees(); // free per-frame TLASes now safely past their frames-in-flight window
        if (RtTerrain.currentOrNull() == null || !frameCaptured) {
            return false; // no terrain extracted yet
        }
        try {
            if (displayPipeline == null) {
                displayPipeline = RtDisplayPipeline.create(ctx);
            }
            // A resource reload re-stitches the block atlas. We've already torn down the world pipeline
            // (onResourceReloadStart) so nothing references the old atlas, but MC's deferred free keeps the
            // old view handle live for a few frames, then swaps in the new atlas (whose GPU upload may lag,
            // leaving the handle 0 transiently). Skip RT — vanilla renders — until the handle becomes a
            // fresh, non-zero value different from what we last bound; only then rebuild against it.
            if (reloadRebindRequested) {
                long atlas = blockAtlasView();
                if (atlas == 0L || atlas == boundAtlasHandle) {
                    return false;
                }
            }
            ensureOutput(ctx, width, height);
            RtPipeline active = ensureWorld(ctx);
            updateMotion();
            recordFrame(ctx, active, nativeColor);
            if (!loggedActive) {
                loggedActive = true;
                UpscalerMod.LOGGER.info("RT composite active (terrain): {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("RT composite failed; reverting to vanilla/upscaler path", t);
            return false;
        }
    }

    /**
     * Bring the world pipeline + LabPBR atlases up as soon as we're in a world and the block atlas is
     * loaded — <em>before</em> terrain tessellates — so per-prim material flags ({@code hasS}/{@code
     * hasN}) resolve from the first section. That makes PBR-on-join structural rather than relying on a
     * re-extract after the fact. Driven from the client tick ahead of {@link RtTerrain#update}. No-op once
     * the pipeline exists, while a reload rebuild is pending (the reload path rebuilds against the new
     * atlas), or until we're in a world with the atlas ready. The heavy {@code _s}/{@code _n} atlases are
     * deliberately not built at the menu — only once a world is entered.
     */
    public void ensureResourcesReady(RtContext ctx) {
        if (failed || worldPipeline != null || reloadRebindRequested) {
            return;
        }
        if (Minecraft.getInstance().level == null || blockAtlasView() == 0L) {
            return;
        }
        try {
            ensureWorld(ctx);
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("RT resource bring-up failed; reverting to vanilla path", t);
        }
    }

    private RtPipeline ensureWorld(RtContext ctx) {
        if (worldPipeline == null) {
            worldPipeline = RtPipeline.create(ctx, "world.rgen.spv",
                    new String[]{"world.rmiss.spv", "shadow.rmiss.spv"}, "world.rchit.spv", "world.rahit.spv",
                    Long.BYTES, true, GUIDE_COUNT, RtEntityTextures.MAX_TEXTURES, RtMaterials.ENABLED);
            // P6.4: per-frame push data now lives in this BDA ring; the pipeline only pushes its address.
            if (pushRing == null) {
                pushRing = new RtBuffer[PUSH_RING];
                for (int i = 0; i < PUSH_RING; i++) {
                    pushRing[i] = ctx.createBuffer(WORLD_PUSH_SIZE,
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push " + i);
                }
            }
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindWorldTextures(ctx);
            reloadRebindRequested = false;
        }
        // The TLAS is no longer bound here — it's rebuilt and bound per frame in recordFrame (P5.1a),
        // since dynamic content (entities, P5.1b) animates the instance set every frame.
        return worldPipeline;
    }

    /**
     * Resolve + bind every world-pipeline texture: the block atlas (binding 2 + bindless fallback slot 0)
     * and the LabPBR {@code _s}/{@code _n} parallel atlases (bindings 8/9). Shared by first creation and
     * the post-reload rebind. Resets the entity bindless registry and recreates the {@code _s}/{@code _n}
     * atlases at the current block-atlas size, then re-extracts all terrain ({@link RtTerrain#markAllDirty})
     * so per-prim material flags are recomputed against the (re)built atlases. On first creation this runs
     * before terrain is resident (see {@link #ensureResourcesReady}), so the re-extract is a no-op and PBR
     * is structural; on a resource reload it refreshes the flags of the already-resident terrain.
     */
    private void bindWorldTextures(RtContext ctx) {
        long sampler = atlasSampler(ctx);
        long atlasView = blockAtlasView();
        boundAtlasHandle = atlasView; // remember what we bound so a reload can detect the new atlas
        worldPipeline.setAtlasSampler(atlasView, sampler);
        // Bindless slot 0 = fallback texture (the block atlas) so an entity whose texture can't be
        // resolved samples something defined rather than an unbound (partially-bound) descriptor.
        RtEntityTextures.INSTANCE.reset();
        worldPipeline.setBindlessTexture(0, atlasView, sampler);
        // P6.2a/b: LabPBR _s + _n parallel atlases. Bind the (block-atlas-sized) atlases; their pixels
        // fill lazily as terrain extraction encounters sprites and refresh via flush(). Fall back to the
        // block atlas view if an atlas didn't initialize, so bindings 8/9 always hold a valid descriptor —
        // the shader only samples them when a prim is flagged (mat.z/mat.w), which can't happen unless the
        // atlas exists, so the fallback content is never read.
        if (RtMaterials.ENABLED) {
            RtBlockMaterials.INSTANCE.reset();
            // Build the full _s/_n atlases now (parallel decode + blit), before terrain tessellates, so
            // ensure() is a pure lookup on the build path instead of decoding each sprite's maps lazily.
            RtBlockMaterials.INSTANCE.prepareAll();
            long specView = RtBlockMaterials.INSTANCE.viewS();
            long normalView = RtBlockMaterials.INSTANCE.viewN();
            worldPipeline.setBlockSpecAtlas(specView != 0L ? specView : atlasView, sampler);
            worldPipeline.setBlockNormalAtlas(normalView != 0L ? normalView : atlasView, sampler);
        }
        RtTerrain.markAllDirty();
    }

    /**
     * Hooked at the HEAD of {@link net.minecraft.client.Minecraft#reloadResourcePacks()} (mixin). A
     * resource reload re-stitches the block atlas (and reloads entity textures): MC frees the old GPU
     * images via its deferred destruction queue, which refuses while any descriptor set still references
     * them ("in use by VkDescriptorSet" → device lost). So we drain in-flight frames and then <b>destroy
     * the world pipeline outright</b> — dropping every descriptor reference (block atlas binding 2 +
     * bindless set) — so MC can free its textures cleanly. The pipeline is cheap to rebuild (no terrain
     * re-upload); {@code ensureWorld} recreates it on the first world frame after the reload, once the new
     * atlas is ready (gated in {@link #composite}). Terrain stays resident and is re-extracted via
     * {@code markAllDirty()} so material flags pick up the new pack.
     */
    public void onResourceReloadStart() {
        reloadRebindRequested = true;
        RtContext ctx = RtContext.currentOrNull();
        if (ctx != null && worldPipeline != null) {
            ctx.waitIdle();
            worldPipeline.destroy();
            worldPipeline = null;
        }
    }

    /** Bind the three guide buffers into the world pipeline's extra storage-image slots (0..2). */
    private void bindGuideImages() {
        if (worldPipeline == null || gNormal == null) {
            return;
        }
        worldPipeline.setExtraStorageImage(0, gNormal.view);
        worldPipeline.setExtraStorageImage(1, gAlbedo.view);
        worldPipeline.setExtraStorageImage(2, gDepth.view);
        worldPipeline.setExtraStorageImage(3, gMotion.view);
        worldPipeline.setExtraStorageImage(4, gSpecAlbedo.view);
    }

    private void destroyGuideImages() {
        if (gNormal != null) {
            gNormal.destroy();
            gNormal = null;
        }
        if (gAlbedo != null) {
            gAlbedo.destroy();
            gAlbedo = null;
        }
        if (gDepth != null) {
            gDepth.destroy();
            gDepth = null;
        }
        if (gMotion != null) {
            gMotion.destroy();
            gMotion = null;
        }
        if (gSpecAlbedo != null) {
            gSpecAlbedo.destroy();
            gSpecAlbedo = null;
        }
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        if (output != null && displayImage != null && rrOutput != null && exposure.ready()
                && displayW == width && displayH == height) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        if (displayImage != null) {
            displayImage.destroy();
        }
        if (output != null) {
            output.destroy();
        }
        destroyGuideImages();

        displayW = width;
        displayH = height;
        // The path tracer + its guide buffers run at render res; DLSS-RR (or a fallback blit) upscales
        // to display res. With RR off there is no upscaler, so trace at 1:1 for a faithful reference.
        float scale = RtDlssRr.ENABLED ? RENDER_SCALE : 1.0f;
        renderW = Math.max(1, Math.round(width * scale));
        renderH = Math.max(1, Math.round(height * scale));

        // RT traces into an HDR (R16G16B16A16_SFLOAT) target so radiance > 1 survives to the display
        // mapping seam. displayImage stays R8G8B8A8 to match the main target it is copied into
        // (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "trace color " + renderW + "x" + renderH);
        displayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "RT display image " + width + "x" + height);
        // Guide buffers match the trace (render) resolution; DLSS-RR consumes them at render res.
        gNormal = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderW + "x" + renderH);
        gAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderW + "x" + renderH);
        gDepth = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT, "guide linear depth " + renderW + "x" + renderH);
        gMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide motion " + renderW + "x" + renderH);
        gSpecAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderW + "x" + renderH);
        // Display-res RT image the display mapper reads. Always present (DLSS-RR target, or blit-upscale fallback).
        rrOutput = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "DLSS-RR output " + width + "x" + height);
        exposure.ensureResources(ctx);

        mvHasPrev = false; // recreated images -> first MV frame is zero
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        displayPipeline.setImages(displayImage.view, rrOutput.view, exposure.image().view);
    }

    /**
     * Compute this frame's motion-vector push data: the matrix that projects a current world point
     * into the previous frame's clip space, plus the per-frame camera translation. On the first frame
     * (or after a reset) push the current view-projection with zero delta so MVs come out zero.
     */
    private void updateMotion() {
        mvCurProjView.set(frameProjection).mul(frameViewRotation);
        if (mvHasPrev) {
            mvPushMatrix.set(mvPrevProjView);
            mvCamDeltaX = (float) (camX - mvPrevCamX);
            mvCamDeltaY = (float) (camY - mvPrevCamY);
            mvCamDeltaZ = (float) (camZ - mvPrevCamZ);
        } else {
            mvPushMatrix.set(mvCurProjView);
            mvCamDeltaX = 0f;
            mvCamDeltaY = 0f;
            mvCamDeltaZ = 0f;
        }
        mvPrevProjView.set(mvCurProjView);
        mvPrevCamX = camX;
        mvPrevCamY = camY;
        mvPrevCamZ = camZ;
        mvHasPrev = true;
    }

    private void recordFrame(RtContext ctx, RtPipeline active, GpuTexture nativeColor) {
        long dstImage = vkImage(nativeColor);
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).upscaler$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            // RR drives the upscale: trace + jitter at render res, DLSS-RR denoises+upscales to display.
            // Jitter is suppressed for the no-RR reference and for the debug guide views (raw inspection).
            boolean rrPath = RtDlssRr.ENABLED && DEBUG_VIEW == 0;
            float jitterX = 0f;
            float jitterY = 0f;
            if (rrPath) {
                UpscalerJitter.INSTANCE.prepare(renderW, renderH, displayW);
                jitterX = UpscalerJitter.INSTANCE.jitterPixelsX() * JITTER_SIGN_X;
                jitterY = UpscalerJitter.INSTANCE.jitterPixelsY() * JITTER_SIGN_Y;
            }

            boolean rrDone = false;
            RtTerrain terrain = RtTerrain.currentOrNull();
            // P6.4: write this frame's push data into the next BDA ring slot (cycled so an in-flight slot
            // is never overwritten). The std430 WorldPush layout matches these byte offsets exactly, so
            // the put(offset, ...) writes below — and writeSky(push) — are unchanged from the inline-push era.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            RtBuffer pushBuf = pushRing[pushSlot];
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            new Matrix4f(frameProjection).mul(frameViewRotation).invert().get(0, push);
            push.putFloat(64, (float) (camX - terrain.blockX));
            push.putFloat(68, (float) (camY - terrain.blockY));
            push.putFloat(72, (float) (camZ - terrain.blockZ));
            push.putLong(80, terrain.tableAddress());
            push.putInt(88, DEBUG_VIEW);
            push.putInt(92, (int) frameCounter); // per-frame RNG variation for the denoiser
            mvPushMatrix.get(96, push);
            push.putFloat(160, mvCamDeltaX);
            push.putFloat(164, mvCamDeltaY);
            push.putFloat(168, mvCamDeltaZ);
            push.putInt(172, SPP);
            push.putFloat(176, jitterX);
            push.putFloat(180, jitterY);
            // P6.1 flags: PBR BRDF toggle + camera-in-water (so the path tracer starts in the water
            // medium when the eye is submerged, fixing the air->water first-segment orientation).
            int flags = RtMaterials.ENABLED ? 0b10 : 0;
            var level = Minecraft.getInstance().level;
            if (level != null && level.getFluidState(BlockPos.containing(camX, camY, camZ)).is(FluidTags.WATER)) {
                flags |= 0b01;
            }
            push.putInt(192, flags);
            // P6.3: sun/moon direction + light radiance + sky day-factor, derived from the time of day.
            writeSky(push);

            // P5.1a/b: rebuild the TLAS this frame from the static section instances merged with
            // dynamic entity-box instances, bind it into the pipeline's descriptor ring, record the
            // build into this frame's command buffer, then barrier so the trace sees the finished TLAS.
            // The section BLAS are already built (async, by RtTerrain) and the entity cube BLAS is built
            // once; only the cheap instance-level TLAS is rebuilt per frame. Retired KEEP_FRAMES later,
            // once this frame is no longer in flight.
            // P5.1b-2: capture this frame's entities as real meshes; their per-entity BLAS are built
            // inline below and merged into the per-frame TLAS. geomTableAddr feeds the chit entity path
            // (per-prim normal/tint) and per-object motion vectors (0 when no model entities present).
            RtEntities.FrameEntities fe = RtEntities.INSTANCE.beginFrame(ctx, terrain.staticInstances(),
                    terrain.blockX, terrain.blockY, terrain.blockZ, camX, camY, camZ, frameProjection, frameViewRotation);
            push.putLong(184, fe.geomTableAddr());
            // Upload any entity textures registered this frame into the bindless set before the trace.
            RtEntityTextures.INSTANCE.uploadPending(active, atlasSampler(ctx));
            // P6.2a: re-upload the LabPBR _s atlas if extraction added sprites since the last frame (the
            // view handle is stable, so no re-bind needed). Before the trace records, like uploadPending.
            if (RtMaterials.ENABLED) {
                RtBlockMaterials.INSTANCE.flush();
            }
            // Build the entity BLAS this frame, then the TLAS that references them (+ the already-built
            // terrain BLAS), then the trace — each separated by a barrier. The frame TLAS is retired
            // KEEP_FRAMES later (entity meshes/BLAS are retired by RtEntities on the same horizon).
            if (!fe.blas().isEmpty()) {
                RtAccel.recordBlasBuilds(ctx, cmd, fe.blas());
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // entity BLAS writes visible to the TLAS build
            }
            RtAccel.PreparedTlas frameTlas = RtAccel.prepareTlas(ctx, fe.instances());
            active.setTlas(frameTlas.accel.handle);
            RtAccel.recordTlasBuild(ctx, cmd, frameTlas);
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // TLAS build visible to the trace
            deferredTlas.add(new DeferredTlas(frameCounter + KEEP_FRAMES, frameTlas));

            // P6.4: push only the 8-byte device address of this frame's filled push-data slot.
            ByteBuffer pushAddr = stack.malloc(Long.BYTES).putLong(0, pushBuf.deviceAddress);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world trace")) {
                active.trace(cmd, renderW, renderH, pushAddr);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // RT color visible to exposure histogram/fixed write
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure")) {
                exposure.record(ctx, cmd, stack, output);
            }
            // P4.2b: DLSS-RR denoise + upscale. The RT pass wrote noisy color (render res) + guides;
            // RR reads them and writes the display-res denoised result straight into rrOutput, which
            // the display mapper reads. No copy-back: render and display sizes now differ.
            if (rrPath && RtDlssRr.INSTANCE.ensureFeature(cmd.address(), renderW, renderH, displayW, displayH)) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // RT writes visible to DLSS reads
                // DLSS expects the reported jitter to be the NEGATION of what was added to the
                // primary ray (pixelCenter += jitter), matching the mcvr reference (apply +J, report
                // -J). The shader push above uses +jitter; report -jitter here.
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "DLSS-RR evaluate")) {
                    rrDone = RtDlssRr.INSTANCE.evaluate(cmd.address(), output, gDepth, gMotion, gAlbedo,
                            gSpecAlbedo, gNormal, rrOutput, renderW, renderH, displayW, displayH, -jitterX, -jitterY);
                }
            }

            // When DLSS-RR did not produce the display-res image (disabled, debug view, or a runtime
            // failure), bring the render-res trace up to display res with a linear blit so the display mapper
            // always has a display-res RT image. With RR off render == display, so this is a 1:1 copy.
            if (!rrDone) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fallback upscale")) {
                    blitUpscale(cmd, stack, output, rrOutput);
                }
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display")) {
                displayPipeline.dispatch(cmd, displayW, displayH);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target")) {
                VK10.vkCmdCopyImage(cmd, displayImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayW, displayH));
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
    }

    /**
     * P6.3 dynamic sky. Derive the celestial light from Minecraft's time of day and write it into the
     * world push constant (three 16-byte-aligned vec4s at offsets 208/224/240):
     * <ul>
     *   <li>{@code sunDir.xyz} — the true sun direction (for the sky glow/disc), {@code .w} = dayFactor
     *       (0 night .. 1 day), used to cross-fade the sky gradient.</li>
     *   <li>{@code lightDir.xyz} — the active NEE light direction: the sun while it is above the horizon,
     *       otherwise the moon (so surfaces still get soft moonlight at night); {@code .w} = the light's
     *       angular radius in radians (P6.3b soft shadows; 0 when {@code softShadows=false}).</li>
     *   <li>{@code lightRadiance.xyz} — that light's HDR colour: warm + dim near the horizon, white +
     *       bright when high; dim cool moonlight at night.</li>
     * </ul>
     * 26.2 exposes the celestial angles via the camera's {@link EnvironmentAttributeProbe} (partial-tick
     * interpolated). {@code upscaler.rt.sunNoonSouthDeg} tilts the east-west arc toward south (+Z) at
     * noon while keeping sunrise/sunset on the east-west horizon. {@code dynamicSky=false} pins the
     * legacy fixed noon sun (P3.1a constants) for an exact A/B.
     */
    private void writeSky(ByteBuffer push) {
        float sunX, sunY, sunZ, dayFactor, lx, ly, lz, rr, rg, rb, lightRadius;
        Minecraft mc = Minecraft.getInstance();
        if (!DYNAMIC_SKY || mc.level == null) {
            Vector3f s = new Vector3f(0.35f, 0.9f, 0.25f).normalize();
            sunX = s.x; sunY = s.y; sunZ = s.z; dayFactor = 1f;
            lx = s.x; ly = s.y; lz = s.z;
            rr = 4.2f; rg = 4.0f; rb = 3.6f; // P3.1a SUN_RADIANCE
            lightRadius = SOFT_SHADOWS ? SUN_ANGULAR_RADIUS : 0f;
        } else {
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            var probe = mc.gameRenderer.mainCamera().attributeProbe();
            float sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * (float) (Math.PI / 180.0);
            float moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * (float) (Math.PI / 180.0);
            float sunNoon = Mth.cos(sunAngle);
            sunX = -Mth.sin(sunAngle); sunY = SUN_NOON_Y * sunNoon; sunZ = SUN_NOON_Z * sunNoon;
            float moonNoon = Mth.cos(moonAngle);
            float mx = -Mth.sin(moonAngle), my = SUN_NOON_Y * moonNoon, mz = SUN_NOON_Z * moonNoon;
            dayFactor = smoothstep(-0.08f, 0.10f, sunY);
            if (sunY > 0.0f) {
                // Sun: fades out as it sets; warm at the horizon, white overhead.
                float strength = smoothstep(-0.06f, 0.18f, sunY);
                float warmth = smoothstep(0.0f, 0.30f, sunY);
                float sunPeak = 4.6f;
                lx = sunX; ly = sunY; lz = sunZ;
                rr = 1.00f * sunPeak * strength;
                rg = Mth.lerp(warmth, 0.42f, 0.96f) * sunPeak * strength;
                rb = Mth.lerp(warmth, 0.18f, 0.90f) * sunPeak * strength;
                lightRadius = SOFT_SHADOWS ? SUN_ANGULAR_RADIUS : 0f;
            } else {
                // Moon: dim cool light, fading in as the sun drops below the horizon.
                float moonStrength = 1.0f - dayFactor;
                float moonPeak = 0.30f;
                lx = mx; ly = my; lz = mz;
                rr = 0.30f * moonPeak * moonStrength;
                rg = 0.36f * moonPeak * moonStrength;
                rb = 0.55f * moonPeak * moonStrength;
                lightRadius = SOFT_SHADOWS ? MOON_ANGULAR_RADIUS : 0f;
            }
        }
        push.putFloat(208, sunX); push.putFloat(212, sunY); push.putFloat(216, sunZ); push.putFloat(220, dayFactor);
        push.putFloat(224, lx); push.putFloat(228, ly); push.putFloat(232, lz); push.putFloat(236, lightRadius);
        push.putFloat(240, rr); push.putFloat(244, rg); push.putFloat(248, rb); push.putFloat(252, 0f);
    }

    /** Hermite smoothstep matching GLSL semantics (0 below edge0, 1 above edge1). */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Free per-frame TLASes whose tracing frame is now far enough behind to be off all in-flight queues. */
    private void processDeferredTlasFrees() {
        if (deferredTlas.isEmpty()) {
            return;
        }
        java.util.Iterator<DeferredTlas> it = deferredTlas.iterator();
        while (it.hasNext()) {
            DeferredTlas d = it.next();
            if (d.freeFrame() <= frameCounter) {
                d.tlas().destroyAll();
                it.remove();
            }
        }
    }

    public void destroy() {
        // Teardown runs after the device is idle (CLIENT_STOPPING waits), so any outstanding per-frame
        // TLASes are no longer in flight and can be freed immediately.
        for (DeferredTlas d : deferredTlas) {
            d.tlas().destroyAll();
        }
        deferredTlas.clear();
        if (RtDlssRr.ENABLED) {
            RtDlssRr.INSTANCE.destroy();
        }
        if (displayImage != null) {
            displayImage.destroy();
            displayImage = null;
        }
        if (output != null) {
            output.destroy();
            output = null;
        }
        destroyGuideImages();
        exposure.destroy();
        if (displayPipeline != null) {
            displayPipeline.destroy();
            displayPipeline = null;
        }
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        if (pushRing != null) {
            for (RtBuffer b : pushRing) {
                if (b != null) {
                    b.destroy();
                }
            }
            pushRing = null;
        }
        if (atlasSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), atlasSampler, null);
            }
            atlasSampler = 0L;
        }
    }

    private long atlasSampler(RtContext ctx) {
        if (atlasSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(0f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(block atlas) failed");
                }
                atlasSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, atlasSampler, "block atlas sampler");
            }
        }
        return atlasSampler;
    }

    private static long blockAtlasView() {
        GpuTextureView view = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        return vkImageView(view);
    }

    private static long vkImageView(GpuTextureView view) {
        Long sodiumHandle = SodiumCompat.vkImageView(view);
        if (sodiumHandle != null) {
            return sodiumHandle;
        }
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        throw new IllegalStateException("cannot resolve VkImageView for " + view);
    }

    private static long vkImage(GpuTexture texture) {
        Long sodiumHandle = SodiumCompat.vkImage(texture);
        if (sodiumHandle != null) {
            return sodiumHandle;
        }
        if (texture instanceof VulkanGpuTexture vulkanTexture) {
            return vulkanTexture.vkImage();
        }
        throw new IllegalStateException("cannot resolve VkImage for " + texture);
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    /**
     * Linear-filtered blit of the full render-res image into the full display-res image. Used as the
     * non-RR / fallback upscale so display mapping always sees a display-res RT image; a no-op stretch when
     * the two are the same size (RR disabled -> render == display).
     */
    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, RtImage src, RtImage dst) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width, src.height, 1); // srcOffsets[0] zeroed by calloc
        region.get(0).dstOffsets(1).set(dst.width, dst.height, 1);
        VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_LINEAR);
    }
}
