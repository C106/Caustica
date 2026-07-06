package dev.upscaler.rt.overlay;

import org.lwjgl.vulkan.VkCommandBuffer;

import dev.upscaler.rt.RtContext;

/**
 * One world-space overlay effect (glow outline today; block outline, nametags, leash planned) rendered by
 * {@link RtWorldOverlay} at the post-upscale seam. Implementations create their pipelines through
 * {@link RtOverlayPipelines} (reusing an existing vertex-format/blend combination where one fits) and take
 * per-frame vertex scratch from the shared {@link RtOverlayFramePool} — never own one-off pools.
 */
public interface RtOverlayFeature {
    /**
     * Gather this frame's CPU-side data, lazily create GPU resources, and upload vertex scratch via
     * {@code pool}. Runs before any command recording; return false to skip {@link #record} this frame.
     * {@code width}/{@code height} are the composite target's (display-res) extent.
     */
    boolean prepare(RtContext ctx, RtOverlayFramePool pool, int width, int height);

    /**
     * Record this feature's passes. {@code targetView} is the presented image's colour view
     * ({@link RtWorldOverlay#TARGET_FORMAT}, GENERAL layout) — composite onto it with
     * {@code loadOp = LOAD} dynamic rendering (attachment writes only: vanilla-owned images are never
     * storage-capable). Host vertex writes are already visible; barriers between a feature's own passes
     * are the feature's responsibility, and {@link RtWorldOverlay} barriers between features.
     */
    void record(VkCommandBuffer cmd, long targetView, int width, int height);

    /** Destroy GPU resources (device is idle); must tolerate never-prepared and repeated calls. */
    void destroy();
}
