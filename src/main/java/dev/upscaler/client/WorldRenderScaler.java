package dev.upscaler.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.upscaler.rt.RtComposite;

/**
 * RT composite seam. Brackets vanilla's level-rendering section in {@code GameRenderer.render}: the
 * world renders at full resolution, then the ray-traced composite (DLSS-RR denoise + upscale, see
 * {@link RtComposite}) runs once at the before-hand seam, before vanilla's pre-GUI depth clear, so the
 * hand and HUD draw at native resolution on top.
 *
 * <p>This used to host the FSR/DLSS-SR low-res render-scale path; that has been removed — the RT
 * renderer owns reconstruction via DLSS Ray Reconstruction. With {@code -Dupscaler.rt.composite=false}
 * this is an inert passthrough.
 */
public final class WorldRenderScaler {
	public static final WorldRenderScaler INSTANCE = new WorldRenderScaler();

	// Tracks that the level-render window is open so the safety-net end() does not composite twice.
	private boolean rtWindowOpen;

	private WorldRenderScaler() {
	}

	/** Open the level-render window. Called right before level rendering. */
	public void begin(RenderTarget mainTarget) {
		if (RtComposite.ENABLED) {
			this.rtWindowOpen = true;
		}
	}

	/**
	 * Run the RT composite once, at the before-hand seam (the safety-net end() then no-ops because the
	 * window is already closed). The world has fully rendered at full res by this point.
	 */
	public void end(RenderTarget mainTarget) {
		if (RtComposite.ENABLED && this.rtWindowOpen) {
			this.rtWindowOpen = false;
			RtComposite.INSTANCE.composite(mainTarget.getColorTexture(), mainTarget.width, mainTarget.height);
		}
	}

	public void destroy() {
		this.rtWindowOpen = false;
	}
}
