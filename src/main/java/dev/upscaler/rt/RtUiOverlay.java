package dev.upscaler.rt;

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

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

import dev.upscaler.UpscalerConfig;

/**
 * HDR Phase 2 (step A) — transparent vanilla-UI overlay. The vanilla GUI/HUD is redirected (via
 * {@code GuiRendererMixin}) into a separate transparent {@code RGBA8} target instead of the main render
 * target, then composited back over the world here. In SDR this reproduces vanilla; the point is to keep
 * 2D SDR-authored UI out of the world's HDR tonemap once HDR presentation lands (the compositor will then
 * blend this same overlay over the HDR world at paper white rather than over the SDR main target).
 *
 * <p>Alpha convention: vanilla GUI pipelines use {@code BlendFunction.TRANSLUCENT}
 * ({@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA} colour; {@code ONE, ONE_MINUS_SRC_ALPHA} alpha), so drawing onto
 * a cleared (transparent) target accumulates <em>premultiplied</em> colour ({@code rgb = C*A}, {@code a =
 * A}). The composite back therefore uses premultiplied-over ({@code ONE, ONE_MINUS_SRC_ALPHA}) — NOT
 * {@code ENTITY_OUTLINE_BLIT}, which expects straight alpha and would double-darken semi-transparent UI.
 *
 * <p>Depth: the overlay copies the main target's depth each frame so before-blur GUI depth-tests exactly as
 * vanilla would; the after-blur depth clear that {@code GuiRenderer.draw} performs then targets the overlay,
 * matching vanilla. Blur ({@code GameRenderer.processBlurEffect}) still operates on the real main target, so
 * the world behind screens is blurred as usual and the overlay is composited over the blurred result.
 */
public final class RtUiOverlay {
    private static final Vector4f TRANSPARENT = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);

    /** Fullscreen blit that composites the premultiplied overlay over the destination (premultiplied-over). */
    private static final RenderPipeline COMPOSITE_PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation("pipeline/upscaler_ui_overlay_composite")
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(new ColorTargetState(
                    Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    private static TextureTarget overlay;
    private static boolean usedThisFrame;

    private RtUiOverlay() {
    }

    public static boolean enabled() {
        return UpscalerConfig.Rt.Hdr.UI_OVERLAY.value();
    }

    /**
     * Prepare the overlay (sized to {@code main}, cleared transparent, depth copied from {@code main}) and
     * return it so {@code GuiRenderer.draw} renders the GUI into it instead of the main target. Called from
     * the {@code GuiRendererMixin} redirect on the render thread.
     */
    public static RenderTarget beginAndRedirect(RenderTarget main) {
        TextureTarget target = ensureSized(main);
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        enc.clearColorTexture(target.getColorTexture(), TRANSPARENT);
        // Match vanilla: before-blur GUI tests against the world depth that is in the main target.
        if (target.useDepth && main.getDepthTexture() != null) {
            target.copyDepthFrom(main);
        }
        usedThisFrame = true;
        return target;
    }

    /** Composite the overlay over the real main target. Called at the tail of {@code GuiRenderer.draw}. */
    public static void compositeIfUsed() {
        if (!usedThisFrame || overlay == null) {
            usedThisFrame = false;
            return;
        }
        usedThisFrame = false;
        RenderTarget main = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (main == null || main.getColorTextureView() == null) {
            return;
        }
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = enc.createRenderPass(() -> "UI overlay composite", main.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(COMPOSITE_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", overlay.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    private static TextureTarget ensureSized(RenderTarget main) {
        if (overlay == null) {
            overlay = new TextureTarget("upscaler UI overlay", main.width, main.height, true, GpuFormat.RGBA8_UNORM);
        } else if (overlay.width != main.width || overlay.height != main.height) {
            overlay.resize(main.width, main.height);
        }
        return overlay;
    }
}
