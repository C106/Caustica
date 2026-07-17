package dev.comfyfluffy.caustica.rt;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import org.joml.Vector4f;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;

import net.minecraft.client.Minecraft;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * HDR Phase 2 (step A) — transparent final-UI overlay. World-space overlay features and the vanilla GUI/HUD
 * are routed into one transparent {@code RGBA8} target, then that single image is composited back over the
 * world (from {@code GameRendererMixin}, right after {@code GuiRenderer.render} returns). In SDR this
 * reproduces vanilla; the point is to keep SDR-authored UI out of the world's HDR tonemap once HDR
 * presentation lands (the compositor will then blend this same overlay over the HDR world at paper white
 * rather than over the SDR main target).
 *
 * <p>Composite blend: vanilla GUI pipelines use {@code BlendFunction.TRANSLUCENT} (colour {@code SRC_ALPHA,
 * ONE_MINUS_SRC_ALPHA}; alpha {@code ONE, ONE_MINUS_SRC_ALPHA}), so drawing onto a cleared target
 * accumulates <em>premultiplied</em> colour ({@code rgb = C*A}, {@code a = A}). The composite therefore uses
 * premultiplied-over ({@code TRANSLUCENT_PREMULTIPLIED_ALPHA}); {@code ENTITY_OUTLINE_BLIT} expects straight
 * alpha and would double-darken semi-transparent UI. The pipeline is unregistered, so its shaders are not
 * preloaded — it lazily compiles fine once resources are loaded, but cannot compile during the loading
 * screen; hence the {@link #enabled()} {@code isGameLoadFinished} guard, plus a defensive try/catch.
 *
 * <p>Depth: the overlay clears depth to 0.0 each frame, exactly as {@code GameRenderer.render} clears the
 * main depth right before the GUI. Blur ({@code GameRenderer.processBlurEffect}) still operates on the real
 * main target, so the world behind screens is blurred as usual and the overlay composites over the result.
 */
public final class RtUiOverlay {
    private static final Vector4f TRANSPARENT = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
    // VulkanCommandEncoder keeps two submissions in flight. Keep replaced targets alive beyond that window;
    // destroyBuffers() then enters them into Blaze3D's own two-submit destruction queue as a second guard.
    private static final long TARGET_RETIRE_FRAMES = 4L;

    /** Fullscreen blit that composites the premultiplied overlay over the destination (premultiplied-over). */
    private static final RenderPipeline COMPOSITE_PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation("pipeline/caustica_ui_overlay_composite")
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(new ColorTargetState(
                    Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    private static TextureTarget overlay;
    // TextureTarget's public extent does not identify its backing allocation. Preserve the Java object
    // generation as well so an in-place/same-size backing replacement is never consumed as the old target.
    private static Object overlayColorTexture;
    private static Object overlayColorView;
    private static Object overlayDepthTexture;
    private static Object overlayDepthView;
    private static final Deque<RetiredTarget> retiredTargets = new ArrayDeque<>();
    private static long frameSerial;
    private static long overlayGeneration;
    // Kept separate from usedThisFrame: HDR consumes the pending composite before DLSS-FG evaluates, but FG
    // still needs to know that the UI resource was freshly rendered during this frame.
    private static boolean populatedThisFrame;
    private static boolean usedThisFrame;
    private static boolean compositeFailed;
    // The overlay is cleared once per frame, before the first thing that renders into it (RT world overlays,
    // the hand/screen-effects redirects in HDR mode, or the GUI). Reset at the start of GameRenderer.render
    // via beginFrame().
    private static boolean overlayClearedThisFrame;

    private record RetiredTarget(long closeAfterFrame, TextureTarget target) {
    }

    private RtUiOverlay() {
    }

    /**
     * Runs regardless of HDR mode (the GUI redirect + composite-back reproduces vanilla exactly in SDR —
     * GPU-verified during HDR Phase 2 step A) since {@code RtWorldOverlay}'s composite point is this same
     * seam and needs it to fire every frame. Active only once the game has finished loading: the composite
     * pipeline lazily compiles its shaders, which are not available during the loading screen (would crash
     * with "Couldn't find source for core/screenquad"). Gating the redirect here keeps the loading-screen
     * GUI on the normal path.
     */
    public static boolean enabled() {
        return !compositeFailed && !VanillaRenderController.INSTANCE.isResizeTransitionFrame()
                && Minecraft.getInstance().isGameLoadFinished();
    }

    /** Whether the overlay was freshly populated this frame (for HDR present and DLSS-FG UI input). */
    public static boolean populatedThisFrame() {
        return populatedThisFrame && targetBackingMatches(overlay, overlay != null ? overlay.width : 0,
                overlay != null ? overlay.height : 0);
    }

    /** Whether this frame's overlay is valid for a consumer with the supplied output extent. */
    public static boolean populatedThisFrame(int width, int height) {
        return populatedThisFrame && targetBackingMatches(overlay, width, height);
    }

    /** Mark the overlay consumed by the HDR present composite (so it isn't reused next frame). */
    public static void markConsumed() {
        usedThisFrame = false;
    }

    public static int overlayWidth() {
        return overlay != null ? overlay.width : 0;
    }

    public static int overlayHeight() {
        return overlay != null ? overlay.height : 0;
    }

    /** Monotonic identity for the current overlay backing, independent of recycled Vulkan handles. */
    public static long overlayGeneration() {
        return overlayGeneration;
    }

    /** The overlay color image view, for the HDR composite compute pass (0 if not available). */
    public static long overlayColorView() {
        if (overlay == null || overlay.getColorTextureView() == null) {
            return 0L;
        }
        if (overlay.getColorTextureView() instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView v) {
            return v.vkImageView();
        }
        return 0L;
    }

    /** The overlay color image (0 if not available) — pairs with {@link #overlayColorView()} for callers
     * (e.g. the DLSSG "ui" optional resource) that need both the view and the raw image. */
    public static long overlayColorImage() {
        if (overlay == null || overlay.getColorTexture() == null) {
            return 0L;
        }
        if (overlay.getColorTexture() instanceof com.mojang.blaze3d.vulkan.VulkanGpuTexture t) {
            return t.vkImage();
        }
        return 0L;
    }

    /**
     * Prepare the overlay (sized to {@code main}, cleared transparent with depth cleared to 0.0) and return
     * it so {@code GuiRenderer.draw} renders the GUI into it instead of the main target. Called from the
     * {@code GuiRendererMixin} redirect on the render thread.
     */
    public static RenderTarget beginAndRedirect(RenderTarget main) {
        TextureTarget target = prepare(main);
        return target != null ? target : main;
    }

    /**
     * Prepare the shared transparent overlay for non-GUI contributors such as RT world overlays. Call before
     * the GUI redirect so the GUI draws over those contributors in the same image.
     */
    public static RenderTarget beginCompositeLayer(RenderTarget main) {
        return prepare(main);
    }

    /** Reset the per-frame clear latch. Called at the start of {@code GameRenderer.render} (every frame). */
    public static void beginFrame() {
        frameSerial++;
        closeRetiredTargets();
        populatedThisFrame = false;
        usedThisFrame = false;
        overlayClearedThisFrame = false;
    }

    /**
     * Ensure the overlay exists, sized to {@code main}, and cleared (transparent + depth 0.0) exactly once
     * this frame, then mark it used. Both the hand redirect and the GUI redirect funnel through here so the
     * overlay is cleared before the hand (which renders first) and not wiped before the GUI.
     */
    private static TextureTarget prepare(RenderTarget main) {
        if (!canRedirect(main)) {
            return null;
        }
        TextureTarget target = ensureSized(main);
        if (!overlayClearedThisFrame) {
            CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
            if (target.useDepth && target.getDepthTexture() != null) {
                enc.clearColorAndDepthTextures(target.getColorTexture(), TRANSPARENT, target.getDepthTexture(), 0.0);
            } else {
                enc.clearColorTexture(target.getColorTexture(), TRANSPARENT);
            }
            overlayClearedThisFrame = true;
        }
        populatedThisFrame = true;
        usedThisFrame = true;
        return target;
    }

    /**
     * HDR mode: redirect a world-space overlay render (the held-item/hand, then the fire/underwater/
     * view-blocking screen effects) into the overlay so it composites over the HDR world at paper white,
     * via the render-system output overrides honoured by {@code PreparedRenderType}. Both share the overlay's
     * color+depth (cleared once per frame), matching vanilla where hand and screen effects share the main
     * target's depth without a clear between them. Must be paired with {@link #endOutputRedirect()}.
     */
    public static boolean beginOutputRedirect(RenderTarget main) {
        TextureTarget target = prepare(main);
        if (target == null) {
            return false;
        }
        RenderSystem.outputColorTextureOverride = target.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
        return true;
    }

    public static void endOutputRedirect() {
        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
    }

    /**
     * Composite the overlay over the real main target. Called once per frame from {@code GameRendererMixin}
     * after {@code GuiRenderer.render} returns (the {@code GuiRenderer.draw} TAIL did not fire on in-game HUD
     * frames). A compile/render failure latches the overlay off rather than crashing the frame.
     */
    public static void compositeIfUsed() {
        if (!usedThisFrame || overlay == null) {
            usedThisFrame = false;
            return;
        }
        if (RtComposite.INSTANCE.isHdrPresentActive()) {
            // HDR path composites the overlay over the PQ HDR image at present; leave usedThisFrame set so
            // presentHdr can consume it. Do NOT composite over the SDR main target (it isn't presented).
            return;
        }
        usedThisFrame = false;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (!canRedirect(main) || !targetBackingMatches(overlay, main.width, main.height)) {
            return;
        }
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = enc.createRenderPass(() -> "UI overlay composite", main.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(COMPOSITE_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", overlay.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        } catch (Throwable t) {
            compositeFailed = true;
            org.slf4j.LoggerFactory.getLogger("Caustica")
                    .error("UI overlay composite failed; disabling overlay", t);
        }
    }

    private static TextureTarget ensureSized(RenderTarget main) {
        if (overlay == null || overlay.width != main.width || overlay.height != main.height
                || !canRedirect(overlay) || !overlayBackingGenerationMatches()) {
            // Do not resize in place. resize() closes the old Java texture/view objects immediately even
            // though commands recorded earlier in this or a generated frame can still reference them. A
            // separate generation keeps those handles and their backing allocations alive off-queue.
            TextureTarget previous = overlay;
            overlay = new TextureTarget("caustica UI overlay", main.width, main.height, true, GpuFormat.RGBA8_UNORM);
            overlayGeneration++;
            rememberOverlayBackingGeneration();
            if (previous != null) {
                retiredTargets.addLast(new RetiredTarget(frameSerial + TARGET_RETIRE_FRAMES, previous));
            }
            populatedThisFrame = false;
            usedThisFrame = false;
            overlayClearedThisFrame = false;
        }
        return overlay;
    }

    /**
     * A RenderTarget's public extent changes independently from its backing textures during a GLFW resize.
     * Rendering a full-size HUD against that transition state can use the wrong viewport/scissor and later
     * feed a mismatched image to HDR or frame generation. Let vanilla own that transition frame.
     */
    public static boolean canRedirect(RenderTarget target) {
        if (target == null || target.width <= 0 || target.height <= 0) {
            return false;
        }
        var color = target.getColorTexture();
        var colorView = target.getColorTextureView();
        if (color == null || colorView == null || color.isClosed() || colorView.isClosed()
                || color.getWidth(0) != target.width || color.getHeight(0) != target.height) {
            return false;
        }
        if (!target.useDepth) {
            return true;
        }
        var depth = target.getDepthTexture();
        var depthView = target.getDepthTextureView();
        return depth != null && depthView != null && !depth.isClosed() && !depthView.isClosed()
                && depth.getWidth(0) == target.width && depth.getHeight(0) == target.height;
    }

    private static boolean targetBackingMatches(TextureTarget target, int width, int height) {
        return target != null && target == overlay && target.width == width && target.height == height
                && canRedirect(target) && overlayBackingGenerationMatches();
    }

    private static boolean overlayBackingGenerationMatches() {
        return overlay != null
                && overlayColorTexture == overlay.getColorTexture()
                && overlayColorView == overlay.getColorTextureView()
                && overlayDepthTexture == overlay.getDepthTexture()
                && overlayDepthView == overlay.getDepthTextureView();
    }

    private static void rememberOverlayBackingGeneration() {
        overlayColorTexture = overlay.getColorTexture();
        overlayColorView = overlay.getColorTextureView();
        overlayDepthTexture = overlay.getDepthTexture();
        overlayDepthView = overlay.getDepthTextureView();
    }

    private static void forgetOverlayBackingGeneration() {
        overlayColorTexture = null;
        overlayColorView = null;
        overlayDepthTexture = null;
        overlayDepthView = null;
    }

    private static void closeRetiredTargets() {
        while (!retiredTargets.isEmpty() && retiredTargets.peekFirst().closeAfterFrame <= frameSerial) {
            retiredTargets.removeFirst().target.destroyBuffers();
        }
    }

    public static void destroy() {
        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
        populatedThisFrame = false;
        usedThisFrame = false;
        overlayClearedThisFrame = false;
        if (overlay != null) {
            overlay.destroyBuffers();
            overlay = null;
        }
        forgetOverlayBackingGeneration();
        while (!retiredTargets.isEmpty()) {
            retiredTargets.removeFirst().target.destroyBuffers();
        }
    }
}
