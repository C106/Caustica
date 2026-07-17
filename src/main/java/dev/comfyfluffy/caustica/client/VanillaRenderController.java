package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

public final class VanillaRenderController {
	public static final VanillaRenderController INSTANCE = new VanillaRenderController();

	private boolean frameStarted;
	private boolean baseReady;
	private boolean projectionCaptured;
	private boolean worldSkipped;
	private boolean failureLatched;
	private boolean loggedActive;
	private boolean loggedWaitingForRtPlayerSection;
	private boolean loggedRtPlayerSectionReady;
	private boolean rtActive = true;
	private boolean resizeTransitionFrame;
	private GpuTexture observedMainColor;
	private GpuTextureView observedMainColorView;
	private GpuTexture observedMainDepth;
	private GpuTextureView observedMainDepthView;
	private Boolean lastLoggedRtActive;
	private String inactiveReason;
	private String lastLoggedInactiveReason;

	private VanillaRenderController() {
	}

	/**
	 * Observe the main target at the earliest render-frame boundary. A backing replacement counts as a resize
	 * transition even when its extent and recycled Vulkan handles are unchanged. Holding RT for this frame also
	 * coalesces continuous drag-resize events: RT rebuilds once, after the backing identity stays stable.
	 */
	public void observeRenderFrame(RenderTarget mainTarget, int windowWidth, int windowHeight) {
		GpuTexture color = mainTarget != null ? mainTarget.getColorTexture() : null;
		GpuTextureView colorView = mainTarget != null ? mainTarget.getColorTextureView() : null;
		GpuTexture depth = mainTarget != null ? mainTarget.getDepthTexture() : null;
		GpuTextureView depthView = mainTarget != null ? mainTarget.getDepthTextureView() : null;
		boolean backingReplaced = observedMainColor != null
				&& (observedMainColor != color || observedMainColorView != colorView
						|| observedMainDepth != depth || observedMainDepthView != depthView);
		this.resizeTransitionFrame = backingReplaced
				|| !hasStableExtent(mainTarget, color, colorView, depth, depthView, windowWidth, windowHeight);
		if (color != null && colorView != null && depth != null && depthView != null) {
			this.observedMainColor = color;
			this.observedMainColorView = colorView;
			this.observedMainDepth = depth;
			this.observedMainDepthView = depthView;
		}
	}

	public boolean isResizeTransitionFrame() {
		return this.resizeTransitionFrame;
	}

	public void beginFrame(RenderTarget mainTarget) {
		this.frameStarted = true;
		this.projectionCaptured = false;
		this.worldSkipped = false;
		this.baseReady = false;
		this.inactiveReason = null;
		this.rtActive = RtComposite.enabled();

		if (!Boolean.valueOf(this.rtActive).equals(this.lastLoggedRtActive)) {
			this.lastLoggedRtActive = this.rtActive;
			CausticaMod.LOGGER.info("RT output mode: {}", this.rtActive ? "rt" : "vanilla");
		}

		if (!this.rtActive) {
			return;
		}

		this.inactiveReason = findInactiveReason(mainTarget);
		this.baseReady = this.inactiveReason == null;
		if (this.baseReady) {
			if (!this.loggedActive) {
				this.loggedActive = true;
				CausticaMod.LOGGER.info("Vanilla world rendering cancellation active; using existing RT composite seam");
			}
		} else {
			logInactive(this.inactiveReason);
		}
	}

	public void markProjectionCaptured() {
		this.projectionCaptured = true;
	}

	public boolean shouldCancelLevelRenderer() {
		return this.shouldCancelLevelRenderer(false);
	}

	public boolean shouldCancelLevelRenderer(boolean waitingForRtPlayerSection) {
		if (!this.rtActive) {
			return false;
		}
		if (!this.frameStarted) {
			logInactive("frame controller was not started");
			return false;
		}
		if (!this.baseReady) {
			return false;
		}
		if (!this.projectionCaptured) {
			logInactive("level projection was not captured");
			return false;
		}
		if (waitingForRtPlayerSection && !this.loggedWaitingForRtPlayerSection) {
			this.loggedWaitingForRtPlayerSection = true;
			CausticaMod.LOGGER.info("Keeping vanilla LevelRenderer canceled while waiting for RT player section residency");
		}
		return true;
	}

	public void markRtPlayerSectionReady() {
		if (!this.loggedRtPlayerSectionReady) {
			this.loggedRtPlayerSectionReady = true;
			CausticaMod.LOGGER.info("Satisfied vanilla terrain-load callback from RT player section residency");
		}
	}

	public void markWorldSkipped() {
		this.worldSkipped = true;
	}

	public boolean wasWorldSkippedThisFrame() {
		return this.worldSkipped;
	}

	public boolean shouldCompositeRt() {
		// Non-resize fallback frames still run composite() so terrain streaming and material-epoch gates can
		// advance in the background. A resize transition is the one case where touching extent-bound resources
		// is unsafe and must be skipped entirely.
		return this.rtActive && !this.resizeTransitionFrame;
	}

	/** Runtime work switch for per-frame RT work; mirrors {@link RtComposite#enabled()}. */
	public static boolean rtRuntimeWorkRequested() {
		return RtComposite.enabled();
	}

	public void markRtCompositeResult(boolean success) {
		if (this.worldSkipped && !success) {
			latchFailure("RT composite did not produce a replacement frame");
		}
	}

	public void markMissedBeforeHandSeam() {
		if (this.worldSkipped) {
			latchFailure("missed before-hand RT composite seam after vanilla world was skipped");
		}
	}

	/** Re-arm vanilla cancellation after an explicit render-state rebuild. */
	public void resetFailureLatch() {
		this.failureLatched = false;
		this.lastLoggedInactiveReason = null;
	}

	private String findInactiveReason(RenderTarget mainTarget) {
		if (this.resizeTransitionFrame) {
			return "main render target resize is still in progress";
		}
		if (this.failureLatched || RtComposite.INSTANCE.hasFailed()) {
			return "RT composite failure latch is set";
		}
		if (!RtComposite.enabled()) {
			return "caustica.rt is false";
		}
		if (RtContext.currentOrNull() == null) {
			return "RT context is not ready";
		}
		if (RtTerrain.currentOrNull() == null) {
			return "RT terrain is not ready";
		}
		if (RtComposite.INSTANCE.requiresVanillaWorldFallback()) {
			return "RT resources are crossing an epoch boundary";
		}
		if (mainTarget == null || mainTarget.getColorTexture() == null || mainTarget.getColorTextureView() == null
				|| mainTarget.getDepthTexture() == null || mainTarget.getDepthTextureView() == null) {
			return "main render target textures are not ready";
		}
		if (mainTarget.width <= 0 || mainTarget.height <= 0) {
			return "main render target has zero extent";
		}
		var color = mainTarget.getColorTexture();
		var colorView = mainTarget.getColorTextureView();
		var depth = mainTarget.getDepthTexture();
		var depthView = mainTarget.getDepthTextureView();
		if (color.isClosed() || colorView.isClosed() || depth.isClosed() || depthView.isClosed()) {
			return "main render target textures are closed";
		}
		if (color.getWidth(0) != mainTarget.width || color.getHeight(0) != mainTarget.height
				|| depth.getWidth(0) != mainTarget.width || depth.getHeight(0) != mainTarget.height) {
			return "main render target resize is still in progress";
		}
		return null;
	}

	private static boolean hasStableExtent(RenderTarget mainTarget, GpuTexture color, GpuTextureView colorView,
			GpuTexture depth, GpuTextureView depthView, int windowWidth, int windowHeight) {
		if (mainTarget == null || color == null || colorView == null || depth == null || depthView == null
				|| windowWidth <= 0 || windowHeight <= 0
				|| mainTarget.width != windowWidth || mainTarget.height != windowHeight
				|| color.isClosed() || colorView.isClosed() || depth.isClosed() || depthView.isClosed()) {
			return false;
		}
		return color.getWidth(0) == windowWidth && color.getHeight(0) == windowHeight
				&& depth.getWidth(0) == windowWidth && depth.getHeight(0) == windowHeight;
	}

	private void latchFailure(String reason) {
		if (!this.failureLatched) {
			CausticaMod.LOGGER.warn("Disabling vanilla world cancellation: {}", reason);
		}
		this.failureLatched = true;
		this.baseReady = false;
		this.inactiveReason = reason;
	}

	private void logInactive(String reason) {
		if (reason == null || reason.equals(this.lastLoggedInactiveReason)) {
			return;
		}
		CausticaMod.LOGGER.info("Vanilla world cancellation inactive: {}", reason);
		this.lastLoggedInactiveReason = reason;
	}
}
