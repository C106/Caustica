package dev.upscaler.rt.overlay;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.upscaler.UpscalerMod;
import dev.upscaler.mixin.CommandEncoderAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import java.util.ArrayList;
import java.util.List;

import dev.upscaler.rt.RtComposite;
import dev.upscaler.rt.RtContext;

/**
 * The world-space overlay seam: full-res raster content composited over the RT world AFTER upscaling
 * (nothing thin/crisp survives DLSS-RR, so overlays must not be traced/rastered at render res) but BEFORE
 * DLSS-FG's hudless capture and the GUI composite — world-locked content has to be interpolated with the
 * world, unlike the GUI. Called once per frame from {@code GameRendererMixin} right after
 * {@code GuiRenderer.render()}.
 *
 * <p>This class owns the questions every overlay feature would otherwise re-answer: which image to
 * composite onto (currently the SDR main target — the presented image with HDR off; with HDR on the
 * presented image is {@code RtComposite}'s HDR display image and overlays are not yet visible there —
 * closing that gap means retargeting HERE, once, not per feature), the transient command buffer +
 * inter-feature barriers, per-frame vertex scratch ({@link RtOverlayFramePool}), and the failure latch.
 * Features implement {@link RtOverlayFeature}; pipelines come from {@link RtOverlayPipelines}.
 */
public final class RtWorldOverlay {
    public static final RtWorldOverlay INSTANCE = new RtWorldOverlay();

    /** The composite target's VkFormat: vanilla's main render target ({@code GpuFormat.RGBA8_UNORM}). */
    public static final int TARGET_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;

    private final RtOverlayFramePool framePool = new RtOverlayFramePool();
    private final List<RtOverlayFeature> features =
            List.of(new RtGlowOutlineFeature(), new RtNameTagFeature(), new RtBlockOutlineFeature());
    private boolean failed;

    private RtWorldOverlay() {
    }

    /** Render + composite every active overlay feature onto {@code main}. Never throws (session latch). */
    public void composite(RenderTarget main) {
        long frame = RtComposite.frameCounter();
        framePool.beginFrame(frame);
        if (failed || main == null || main.getColorTexture() == null) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        long targetView = vkImageView(main.getColorTextureView());
        if (targetView == 0L) {
            UpscalerMod.LOGGER.warn("World overlay: main render target has no Vulkan image view; skipping");
            return;
        }
        try {
            List<RtOverlayFeature> ready = new ArrayList<>(features.size());
            for (RtOverlayFeature f : features) {
                if (f.prepare(ctx, framePool, main.width, main.height)) {
                    ready.add(f);
                }
            }
            if (!ready.isEmpty()) {
                record(ready, targetView, main.width, main.height);
            }
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("World overlay failed; disabling for this session", t);
        } finally {
            framePool.endFrame(frame);
        }
    }

    private static void record(List<RtOverlayFeature> ready, long targetView, int width, int height) {
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).upscaler$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // host vertex writes + main's world writes visible
            for (RtOverlayFeature f : ready) {
                f.record(cmd, targetView, width, height);
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // this feature's writes visible to the next / present
            }
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(world overlay) failed");
        }
        encoder.execute(cmd);
    }

    /** Teardown with the rest of the RT stack ({@code RtComposite.destroy}); the device is idle by then. */
    public void destroy() {
        for (RtOverlayFeature f : features) {
            f.destroy();
        }
        framePool.destroy();
    }

    // ---- Recording helpers shared by features ----

    /**
     * Begin a one-attachment dynamic-rendering pass on {@code view} (GENERAL layout) and set the
     * viewport/scissor. {@code clear} = start from transparent black (mask passes); otherwise the existing
     * content is loaded (composite passes). Balance with {@link #endRendering}.
     */
    static void beginColorRendering(VkCommandBuffer cmd, MemoryStack stack, long view, int width, int height, boolean clear) {
        VkRenderingAttachmentInfo.Buffer colorAttach = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(clear ? VK10.VK_ATTACHMENT_LOAD_OP_CLEAR : VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        if (clear) {
            VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
            clearValue.get(0).color().float32(stack.floats(0f, 0f, 0f, 0f));
            colorAttach.get(0).clearValue(clearValue.get(0));
        }
        VkRect2D renderArea = VkRect2D.calloc(stack);
        renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
        renderArea.extent().set(width, height);
        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                .renderArea(renderArea).layerCount(1).pColorAttachments(colorAttach);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
        VK10.vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).offset(VkOffset2D.calloc(stack).set(0, 0));
        scissor.get(0).extent().set(width, height);
        VK10.vkCmdSetScissor(cmd, 0, scissor);
    }

    /**
     * Begin a one-attachment dynamic-rendering pass on the multisample {@code msaaView}, always clearing to
     * transparent black (mask passes only — there is nothing sensible to "load" into a fresh multisample
     * image from a single-sample source). {@code resolveView} receives the driver's per-pixel sample average
     * when the pass ends ({@link #endRendering}) — {@code VK_RESOLVE_MODE_AVERAGE_BIT} is the only mode
     * color attachments support, which is exactly coverage-weighted anti-aliasing for a flat-colour mask.
     */
    static void beginMsaaColorRendering(VkCommandBuffer cmd, MemoryStack stack, long msaaView, long resolveView,
                                        int width, int height) {
        VkRenderingAttachmentInfo.Buffer colorAttach = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(msaaView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .resolveMode(VK12.VK_RESOLVE_MODE_AVERAGE_BIT)
                .resolveImageView(resolveView).resolveImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE); // only the resolved target's contents matter
        VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
        clearValue.get(0).color().float32(stack.floats(0f, 0f, 0f, 0f));
        colorAttach.get(0).clearValue(clearValue.get(0));

        VkRect2D renderArea = VkRect2D.calloc(stack);
        renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
        renderArea.extent().set(width, height);
        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                .renderArea(renderArea).layerCount(1).pColorAttachments(colorAttach);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
        VK10.vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).offset(VkOffset2D.calloc(stack).set(0, 0));
        scissor.get(0).extent().set(width, height);
        VK10.vkCmdSetScissor(cmd, 0, scissor);
    }

    static void endRendering(VkCommandBuffer cmd) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
