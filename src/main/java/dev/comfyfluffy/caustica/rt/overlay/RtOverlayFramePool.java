package dev.comfyfluffy.caustica.rt.overlay;

import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtBufferPool;

/**
 * Per-frame host-visible vertex/index scratch for overlay passes, shared by every {@link RtOverlayFeature}.
 * Buffers acquired during a frame are queued at {@link #endFrame} and released back to the pool only
 * {@value #KEEP_FRAMES} frames later (the same frames-in-flight-safe deferred-release convention
 * {@code RtEntities} uses), so a buffer is never recycled while a prior frame's GPU reads are still in
 * flight.
 */
public final class RtOverlayFramePool {
    private static final int KEEP_FRAMES = 4;

    private final RtBufferPool pool = new RtBufferPool();
    private final List<RtBuffer> acquiredThisFrame = new ArrayList<>();
    private final List<Deferred> deferred = new ArrayList<>();

    private record Deferred(long freeFrame, RtBuffer buffer) {
    }

    /** Release buffers whose in-flight window has passed. Call once at the start of the overlay frame. */
    public void beginFrame(long frameCounter) {
        Iterator<Deferred> it = deferred.iterator();
        while (it.hasNext()) {
            Deferred d = it.next();
            if (d.freeFrame <= frameCounter) {
                pool.release(d.buffer);
                it.remove();
            }
        }
    }

    /** A host-visible vertex buffer of at least {@code bytes}, valid for this frame only. */
    public RtBuffer acquireVertex(RtContext ctx, long bytes, String label) {
        return acquire(ctx, bytes, VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, label);
    }

    /** A host-visible index buffer of at least {@code bytes}, valid for this frame only. */
    public RtBuffer acquireIndex(RtContext ctx, long bytes, String label) {
        return acquire(ctx, bytes, VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT, label);
    }

    private RtBuffer acquire(RtContext ctx, long bytes, int usage, String label) {
        RtBuffer b = pool.acquire(ctx, bytes, usage, true, label);
        acquiredThisFrame.add(b);
        return b;
    }

    /** Queue everything acquired this frame for release once it is safely out of flight. */
    public void endFrame(long frameCounter) {
        for (RtBuffer b : acquiredThisFrame) {
            deferred.add(new Deferred(frameCounter + KEEP_FRAMES, b));
        }
        acquiredThisFrame.clear();
    }

    /** Immediate teardown; only valid once the device is idle (mirrors {@code RtComposite.destroy}). */
    public void destroy() {
        for (RtBuffer b : acquiredThisFrame) {
            pool.release(b);
        }
        acquiredThisFrame.clear();
        for (Deferred d : deferred) {
            pool.release(d.buffer);
        }
        deferred.clear();
        pool.destroyAll();
    }
}
