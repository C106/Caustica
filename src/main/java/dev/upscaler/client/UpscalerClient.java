package dev.upscaler.client;

import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.RtContext;
import dev.upscaler.rt.RtDeviceBringup;
import dev.upscaler.rt.RtComposite;
import dev.upscaler.rt.RtTerrain;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class UpscalerClient implements ClientModInitializer {
	private static boolean rtInitDone = false;

	@Override
	public void onInitializeClient() {
		UpscalerMod.LOGGER.info("Sodium Upscaler client initialized");

		// The GpuDevice exists well before the first tick, so a one-shot at tick start
		// runs on the render thread with the device idle between frames.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			// Bring up the RT device/context once; terrain residency + the composite follow below.
			if (!rtInitDone && RtDeviceBringup.rtRequested()) {
				RtContext ctx = RtContext.get();
				if (ctx != null) {
					rtInitDone = true;
				}
			}

			// P2: once RT is up, keep section residency synced to vanilla's loaded chunks around
			// the player — builds newly-in-range sections, frees out-of-range ones, per tick.
			if (rtInitDone && RtTerrain.ENABLED) {
				RtContext ctx = RtContext.currentOrNull();
				if (ctx != null) {
					RtTerrain.update(ctx);
				}
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			WorldRenderScaler.INSTANCE.destroy();

			RtContext ctx = RtContext.currentOrNull();
			if (ctx != null) {
				ctx.waitIdle();
				RtTerrain.shutdown(ctx);
			}
			RtComposite.INSTANCE.destroy();
			if (ctx != null) {
				ctx.destroy();
			}
		});
	}
}
