package dev.upscaler.rt;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.upscaler.UpscalerMod;
import dev.upscaler.client.SodiumCompat;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * P5.1b-2b: resolves the texture backing an entity {@link RenderType} to a Vulkan image-view handle,
 * for the bindless entity-texture array. Entities use per-type texture files (zombie.png, …), not the
 * block atlas, so each distinct {@link RenderType} maps to its own texture/bindless slot.
 *
 * <p>The view is obtained through the <b>public</b> {@code RenderType.prepare()} → {@link
 * PreparedRenderType#textures()} API (a list of {@code Texture(name, GpuTextureView, sampler)}): the
 * primary sampler is {@code "Sampler0"} ({@code "Sampler1"}/{@code "Sampler2"} are the overlay/lightmap
 * the prepared list prepends). Resolution is cached per {@code RenderType} (they are stable singletons),
 * so the prepare() cost is paid once per distinct texture.
 *
 * <p>Step b1 here is resolution only (verified by the entity probe); the bindless descriptor array +
 * slot assignment + shader sampling are step b2.
 */
public final class RtEntityTextures {
    public static final RtEntityTextures INSTANCE = new RtEntityTextures();
    /** Bindless array capacity (slot 0 reserved as a fallback texture). {@code -Dupscaler.rt.maxEntityTextures}. */
    public static final int MAX_TEXTURES = Integer.getInteger("upscaler.rt.maxEntityTextures", 256);

    private final Map<RenderType, Long> viewCache = new IdentityHashMap<>();
    // Append-only RenderType → bindless slot registry (slot 0 = fallback). Slots never change once
    // assigned, so update-after-bind writes for new slots never disturb in-flight frames.
    private final Map<RenderType, Integer> slotCache = new IdentityHashMap<>();
    private final List<Pending> pending = new ArrayList<>(); // slots resolved this frame, awaiting upload
    private int nextSlot = 1;
    private boolean loggedFailure;

    private record Pending(int slot, long view) {
    }

    private RtEntityTextures() {
    }

    /**
     * The bindless slot for {@code renderType}'s texture (cached, append-only). Resolves + assigns a new
     * slot on first sight (queued for upload via {@link #uploadPending}); returns 0 (the fallback slot)
     * if the texture can't be resolved or the array is full.
     */
    public int slotFor(RenderType renderType) {
        if (renderType == null) {
            return 0;
        }
        Integer cached = slotCache.get(renderType);
        if (cached != null) {
            return cached;
        }
        long view = resolveView(renderType);
        if (view == 0L || nextSlot >= MAX_TEXTURES) {
            slotCache.put(renderType, 0);
            return 0;
        }
        int slot = nextSlot++;
        slotCache.put(renderType, slot);
        pending.add(new Pending(slot, view));
        return slot;
    }

    /** Write any newly-registered entity textures into the pipeline's bindless set (before the trace). */
    public void uploadPending(RtPipeline pipeline, long sampler) {
        if (pending.isEmpty()) {
            return;
        }
        for (Pending p : pending) {
            pipeline.setBindlessTexture(p.slot(), p.view(), sampler);
        }
        pending.clear();
    }

    /** Drop the registry (call when the world pipeline / bindless set is recreated). */
    public void reset() {
        viewCache.clear();
        slotCache.clear();
        pending.clear();
        nextSlot = 1;
    }

    /** The Vulkan image-view handle of {@code renderType}'s primary texture, or 0 if it can't be resolved. */
    public long resolveView(RenderType renderType) {
        if (renderType == null) {
            return 0L;
        }
        Long cached = viewCache.get(renderType);
        if (cached != null) {
            return cached;
        }
        long handle = 0L;
        try {
            PreparedRenderType prepared = renderType.prepare();
            GpuTextureView chosen = null;
            GpuTextureView firstNonAux = null;
            for (PreparedRenderType.Texture t : prepared.textures()) {
                String name = t.name();
                if ("Sampler0".equals(name)) {
                    chosen = t.textureView();
                    break;
                }
                if (firstNonAux == null && !"Sampler1".equals(name) && !"Sampler2".equals(name)) {
                    firstNonAux = t.textureView();
                }
            }
            if (chosen == null) {
                chosen = firstNonAux;
            }
            if (chosen != null) {
                handle = vkImageView(chosen);
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                UpscalerMod.LOGGER.warn("RT entity texture resolution failed for {}", renderType, t);
            }
        }
        viewCache.put(renderType, handle);
        return handle;
    }

    private static long vkImageView(GpuTextureView view) {
        Long sodiumHandle = SodiumCompat.vkImageView(view);
        if (sodiumHandle != null) {
            return sodiumHandle;
        }
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
