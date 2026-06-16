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
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * On-screen composite. Each frame, ray-trace into a render-res storage image (+ guide buffers), use
 * DLSS Ray Reconstruction to denoise and upscale it to display res, blend that over a storage-capable
 * copy of the full-res vanilla world color, and copy the result back to the world target at the
 * end-of-world seam. Gated by {@code -Dupscaler.rt.composite=true}.
 *
 * <p>P4.2b resolution split: the path tracer and its guide buffers run at {@link #RENDER_SCALE} of
 * display res with a per-frame sub-pixel camera jitter; DLSS-RR ({@link RtDlssRr}) reconstructs the
 * display-res image. With RR disabled the trace runs at 1:1 and a linear blit stands in for the
 * upscale (a raw, noisy reference). The vanilla world is rendered at full res (see WorldRenderScaler).
 *
 * <p>Traces the extracted {@link RtTerrain} with perspective camera rays (camera matrices captured
 * each frame via {@link #captureFrame}); composites nothing until terrain is available.
 * Pipelines/SBT/descriptors are built once; sized images rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.composite", "false"));
    /** Blend weight of RT over vanilla: 0 = vanilla only, 1 = RT only. {@code -Dupscaler.rt.blend}. */
    public static final float BLEND = parseBlend();

    // invViewProj(64) + camOffset(@64) + sectionTableAddr(@80) + debugView(@88) + frameIndex(@92)
    // + prevViewProj(@96) + camDelta(@160) + spp(@172) + jitter(@176)
    private static final int WORLD_PUSH_SIZE = 184;
    private static final int GUIDE_COUNT = 5; // P4 guide buffers bound at world-pipeline bindings 3..7

    /** Debug guide-buffer view: 0 = normal render, 1 = normals, 2 = albedo, 3 = depth, 4 = roughness. */
    public static final int DEBUG_VIEW = Integer.getInteger("upscaler.rt.debugView", 0);
    /** Samples per pixel per frame. Default 1: DLSS-RR denoises ~1 spp; raise for the no-RR reference. */
    public static final int SPP = Math.max(1, Integer.getInteger("upscaler.rt.spp", 1));
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

    private static float parseBlend() {
        try {
            return Math.clamp(Float.parseFloat(System.getProperty("upscaler.rt.blend", "0.5")), 0f, 1f);
        } catch (NumberFormatException e) {
            return 0.5f;
        }
    }

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
    private RtBlendPipeline blendPipeline;
    private RtImage output;
    private RtImage baseCopy;
    // P4 guide buffers (first-hit attributes for the denoiser/DLSS-RR): normal+roughness, albedo, depth, motion.
    private RtImage gNormal;
    private RtImage gAlbedo;
    private RtImage gDepth;
    private RtImage gMotion;
    private RtImage gSpecAlbedo;
    // Display-res RT image the blend reads: DLSS-RR writes it (render -> display denoise+upscale), or a
    // linear blit of `output` fills it when RR is off/unavailable (the no-RR reference / fallback).
    private RtImage rrOutput;

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
    private long boundWorldTlas;
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

    private RtComposite() {
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
        if (RtTerrain.currentOrNull() == null || !frameCaptured) {
            return false; // no terrain extracted yet
        }
        try {
            if (blendPipeline == null) {
                blendPipeline = RtBlendPipeline.create(ctx);
            }
            ensureOutput(ctx, width, height);
            RtPipeline active = ensureWorld(ctx);
            updateMotion();
            recordFrame(active, nativeColor);
            if (!loggedActive) {
                loggedActive = true;
                UpscalerMod.LOGGER.info("RT composite active (terrain): {}x{}, RT blended at {} over the world target",
                        width, height, BLEND);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("RT composite failed; reverting to vanilla/upscaler path", t);
            return false;
        }
    }

    private RtPipeline ensureWorld(RtContext ctx) {
        if (worldPipeline == null) {
            worldPipeline = RtPipeline.create(ctx, "world.rgen.spv",
                    new String[]{"world.rmiss.spv", "shadow.rmiss.spv"}, "world.rchit.spv", "world.rahit.spv",
                    WORLD_PUSH_SIZE, true, GUIDE_COUNT);
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            worldPipeline.setAtlasSampler(blockAtlasView(), atlasSampler(ctx));
        }
        long tlas = RtTerrain.currentOrNull().tlas();
        if (boundWorldTlas != tlas) {
            worldPipeline.setTlas(tlas);
            boundWorldTlas = tlas;
        }
        return worldPipeline;
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
        if (output != null && baseCopy != null && rrOutput != null && displayW == width && displayH == height) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        if (baseCopy != null) {
            baseCopy.destroy();
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

        // RT traces into an HDR (R16G16B16A16_SFLOAT) target so radiance > 1 survives to the tonemap
        // seam in blend.comp. baseCopy stays R8G8B8A8 to match the vanilla world target it is copied
        // to/from (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        baseCopy = ctx.createStorageImage(width, height);
        // Guide buffers match the trace (render) resolution; DLSS-RR consumes them at render res.
        gNormal = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        gAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        gDepth = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT);
        gMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT);
        gSpecAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        // Display-res RT image the blend reads. Always present (DLSS-RR target, or blit-upscale fallback).
        rrOutput = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);

        mvHasPrev = false; // recreated images -> first MV frame is zero
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        blendPipeline.setImages(baseCopy.view, rrOutput.view);
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

    private void recordFrame(RtPipeline active, GpuTexture nativeColor) {
        long dstImage = vkImage(nativeColor);
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).upscaler$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
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
            ByteBuffer push = stack.malloc(WORLD_PUSH_SIZE);
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
            active.trace(cmd, renderW, renderH, push);
            // P4.2b: DLSS-RR denoise + upscale. The RT pass wrote noisy color (render res) + guides;
            // RR reads them and writes the display-res denoised result straight into rrOutput, which
            // the blend reads. No copy-back: render and display sizes now differ.
            if (rrPath && RtDlssRr.INSTANCE.ensureFeature(cmd.address(), renderW, renderH, displayW, displayH)) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // RT writes visible to DLSS reads
                // DLSS expects the reported jitter to be the NEGATION of what was added to the
                // primary ray (pixelCenter += jitter), matching the mcvr reference (apply +J, report
                // -J). The shader push above uses +jitter; report -jitter here.
                rrDone = RtDlssRr.INSTANCE.evaluate(cmd.address(), output, gDepth, gMotion, gAlbedo,
                        gSpecAlbedo, gNormal, rrOutput, renderW, renderH, displayW, displayH, -jitterX, -jitterY);
            }

            // When DLSS-RR did not produce the display-res image (disabled, debug view, or a runtime
            // failure), bring the render-res trace up to display res with a linear blit so the blend
            // always has a display-res RT image. With RR off render == display, so this is a 1:1 copy.
            if (!rrDone) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                blitUpscale(cmd, stack, output, rrOutput);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VK10.vkCmdCopyImage(cmd, dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    baseCopy.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayW, displayH));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            blendPipeline.dispatch(cmd, displayW, displayH, BLEND);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VK10.vkCmdCopyImage(cmd, baseCopy.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayW, displayH));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
    }

    public void destroy() {
        if (RtDlssRr.ENABLED) {
            RtDlssRr.INSTANCE.destroy();
        }
        if (baseCopy != null) {
            baseCopy.destroy();
            baseCopy = null;
        }
        if (output != null) {
            output.destroy();
            output = null;
        }
        destroyGuideImages();
        if (blendPipeline != null) {
            blendPipeline.destroy();
            blendPipeline = null;
        }
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        if (atlasSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), atlasSampler, null);
            }
            atlasSampler = 0L;
        }
        boundWorldTlas = 0L;
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
     * non-RR / fallback upscale so the blend always sees a display-res RT image; a no-op stretch when
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
