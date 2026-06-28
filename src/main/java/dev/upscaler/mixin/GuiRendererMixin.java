package dev.upscaler.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.upscaler.rt.RtUiOverlay;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HDR Phase 2 (step A): redirect the vanilla GUI/HUD into a transparent overlay target, then composite it
 * back over the world. {@code GuiRenderer.draw} fetches the destination via
 * {@code gameRenderer.mainRenderTarget()} once and uses it for every GUI draw range (and the after-blur
 * depth clear), so redirecting that single expression routes all GUI rendering into the overlay. Blur is
 * unaffected — {@code GameRenderer.processBlurEffect} operates on the real main target internally. Gated by
 * {@code upscaler.rt.hdr.uiOverlay} (default off); when off this is a no-op and the vanilla path is intact.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@ModifyExpressionValue(
			method = "draw",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget upscaler$redirectGuiToOverlay(RenderTarget original) {
		if (original != null && RtUiOverlay.enabled()) {
			return RtUiOverlay.beginAndRedirect(original);
		}
		return original;
	}

	@Inject(method = "draw", at = @At("TAIL"))
	private void upscaler$compositeOverlay(CallbackInfo ci) {
		RtUiOverlay.compositeIfUsed();
	}
}
