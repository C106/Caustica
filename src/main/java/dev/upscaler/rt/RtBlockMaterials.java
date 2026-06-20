package dev.upscaler.rt;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.upscaler.UpscalerMod;
import dev.upscaler.client.SodiumCompat;
import dev.upscaler.mixin.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P6.2 LabPBR material ingestion for terrain. Vanilla MC builds no {@code _n}/{@code _s} atlas, so we
 * build our own <b>parallel atlases</b> that mirror the block atlas's sprite layout: a {@link NativeImage}
 * sized to the block atlas, into which each sprite's {@code _s} (specular, P6.2a) and {@code _n} (normal,
 * P6.2b) texture is blitted at the <em>same</em> rect the albedo occupies. The closest-hit then samples
 * them at the same UV as albedo — one plain sampler each, no bindless (that is only for the per-type
 * entity textures).
 *
 * <p>Built <b>lazily</b> from the sprites terrain extraction already encounters
 * ({@code BakedQuad.materialInfo().sprite()} in {@link RtTerrain}) — no atlas enumeration. A sprite whose
 * {@code _s}/{@code _n} map is missing keeps the {@link RtMaterials} heuristic / the flat geometric normal
 * (signalled per-prim via the free {@code mat.z}/{@code mat.w} lanes).
 *
 * <p>Upload reuses MC's own texture path ({@link DynamicTexture}); each {@code GpuTextureView} handle is
 * stable across re-uploads, so the RT descriptors are bound once.
 */
public final class RtBlockMaterials {
    public static final RtBlockMaterials INSTANCE = new RtBlockMaterials();

    /** Per-prim flag bits returned by {@link #ensure} (stored in {@code mat.z}/{@code mat.w}). */
    public static final int HAS_S = 1;
    public static final int HAS_N = 2;

    private final Atlas specAtlas = new Atlas("rt/blocks_s", "_s.png", "rt_blocks_s");
    private final Atlas normalAtlas = new Atlas("rt/blocks_n", "_n.png", "rt_blocks_n");
    private int atlasW, atlasH;
    // Per-sprite result cache: bitmask of HAS_S | HAS_N (which maps were found + blitted). Concurrent
    // because prepareAll() populates it from parallel worker threads (one entry per sprite, disjoint).
    private final Map<TextureAtlasSprite, Integer> seen = new ConcurrentHashMap<>();
    private boolean loggedFailure;
    private boolean loggedLazyFallback;

    private RtBlockMaterials() {
    }

    /** One parallel atlas (the {@code _s} or {@code _n} map) backed by a CPU image + an MC DynamicTexture. */
    private final class Atlas {
        final Identifier id;
        final String suffix;     // resource suffix, e.g. "_s.png"
        final String label;
        NativeImage image;
        DynamicTexture tex;
        boolean dirty;

        Atlas(String path, String suffix, String label) {
            this.id = Identifier.fromNamespaceAndPath("upscaler", path);
            this.suffix = suffix;
            this.label = label;
        }

        void create() {
            close();
            image = new NativeImage(atlasW, atlasH, true); // zeroed: unfilled texels are gated by mat.z/.w
            tex = new DynamicTexture(() -> label, image);  // creates + uploads the GpuTexture
            dirty = false;
        }

        void close() {
            if (tex != null) {
                tex.close();
                tex = null;
                image = null;
            }
            dirty = false;
        }

        /** Load+blit this sprite's map; returns true if the resource existed. */
        boolean load(TextureAtlasSprite sprite, Identifier name) throws Exception {
            if (image == null) {
                return false;
            }
            Identifier loc = Identifier.fromNamespaceAndPath(name.getNamespace(),
                    "textures/" + name.getPath() + suffix);
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isEmpty()) {
                return false;
            }
            try (InputStream in = res.get().open(); NativeImage src = NativeImage.read(in)) {
                blit(src, sprite, image);
                dirty = true;
            }
            return true;
        }

        void flush() {
            if (dirty && tex != null) {
                tex.upload();
                dirty = false;
            }
        }

        long view() {
            return tex != null ? vkImageView(tex.getTextureView()) : 0L;
        }
    }

    /**
     * (Re)create both parallel atlases sized to the current block atlas. Called when the world pipeline
     * is (re)created — the block atlas is already resolved by then, so the textures/views exist
     * immediately and can be bound once. No-op (views stay 0) if the atlas isn't ready.
     */
    public void reset() {
        seen.clear();
        specAtlas.close();
        normalAtlas.close();
        try {
            GpuTextureView atlas = Minecraft.getInstance().getTextureManager()
                    .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            atlasW = atlas.getWidth(0);
            atlasH = atlas.getHeight(0);
            if (atlasW <= 0 || atlasH <= 0) {
                return;
            }
            specAtlas.create();
            normalAtlas.create();
        } catch (Throwable t) {
            warnOnce("RT material atlas creation failed", t);
            specAtlas.close();
            normalAtlas.close();
        }
    }

    /**
     * Decode + blit a sprite's {@code _s}/{@code _n} maps into the parallel atlases and return the
     * {@link #HAS_S}|{@link #HAS_N} bitmask. Pure CPU (resource read + blit to the sprite's disjoint atlas
     * rect); safe to call from parallel threads. Does not touch {@link #seen} or upload — callers do.
     */
    private int loadSpriteFlags(TextureAtlasSprite sprite) {
        int flags = 0;
        try {
            Identifier name = sprite.contents().name(); // e.g. minecraft:block/stone
            if (specAtlas.load(sprite, name)) {
                flags |= HAS_S;
            }
            if (normalAtlas.load(sprite, name)) {
                flags |= HAS_N;
            }
        } catch (Throwable t) {
            warnOnce("RT material map load failed for a sprite", t);
        }
        return flags;
    }

    /**
     * Build the {@code _s}/{@code _n} atlases for <em>every</em> block-atlas sprite up front, so terrain
     * extraction's {@link #ensure} is a pure cache lookup with no per-sprite PNG decode on the build hot
     * path. The decode + blit run in parallel (each sprite owns a disjoint atlas rect, {@link #seen} is
     * concurrent); the GPU upload is a single {@link #flush} afterward. Called from the world-pipeline
     * texture bring-up, before terrain tessellates. No-op if the atlases didn't initialize.
     */
    public void prepareAll() {
        if (specAtlas.image == null) {
            return;
        }
        List<TextureAtlasSprite> sprites;
        try {
            TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
                    .getTexture(TextureAtlas.LOCATION_BLOCKS);
            sprites = ((TextureAtlasAccessor) atlas).upscaler$sprites();
        } catch (Throwable t) {
            warnOnce("RT material prepareAll: could not enumerate block-atlas sprites", t);
            return;
        }
        if (sprites == null) {
            return;
        }
        sprites.parallelStream().forEach(sprite -> {
            if (sprite == null || seen.containsKey(sprite)) {
                return;
            }
            seen.put(sprite, loadSpriteFlags(sprite));
        });
        flush();
    }

    /**
     * The {@code _s}/{@code _n} presence bitmask ({@link #HAS_S}|{@link #HAS_N}) for a sprite — a pure
     * lookup into the {@link #prepareAll}-populated cache. A sprite not yet prepared (should not happen for
     * block-atlas sprites) is loaded lazily as a fallback; its upload is picked up by the next {@link
     * #flush} (called pre-trace each frame).
     */
    public int ensure(TextureAtlasSprite sprite) {
        if (sprite == null || specAtlas.image == null) {
            return 0;
        }
        Integer cached = seen.get(sprite);
        if (cached != null) {
            return cached;
        }
        // prepareAll() is meant to cover every block-atlas sprite; a miss here means terrain hit a sprite
        // the up-front pass didn't enumerate (or ran before it). Load lazily for correctness, but warn once
        // so the gap is visible rather than silently paying decode cost back on the build path.
        if (!loggedLazyFallback) {
            loggedLazyFallback = true;
            UpscalerMod.LOGGER.warn("RT material ensure() fell back to a lazy load for sprite {} — "
                    + "prepareAll() did not cover it (decode is back on the terrain-build path)", sprite.contents().name());
        }
        int flags = loadSpriteFlags(sprite);
        seen.put(sprite, flags);
        return flags;
    }

    /** Re-upload the atlases that gained sprites since the last flush. Call before the trace records. */
    public void flush() {
        specAtlas.flush();
        normalAtlas.flush();
    }

    /** Vulkan image-view handle of the {@code _s} atlas, or 0 if not created. Stable across uploads. */
    public long viewS() {
        return specAtlas.view();
    }

    /** Vulkan image-view handle of the {@code _n} atlas, or 0 if not created. Stable across uploads. */
    public long viewN() {
        return normalAtlas.view();
    }

    /**
     * Blit a map into the atlas at the sprite's content rect, nearest-sampled to the sprite's resolution
     * (the pack's map may be a different size; animated sprites use frame 0 = the top {@code width×height}
     * region). Content origin derives from the sprite UVs (avoids the private {@code padding} field).
     */
    private void blit(NativeImage src, TextureAtlasSprite sprite, NativeImage dst) {
        int w = sprite.contents().width();
        int h = sprite.contents().height();
        int cx = Math.round(sprite.getU0() * atlasW);
        int cy = Math.round(sprite.getV0() * atlasH);
        int sw = src.getWidth();
        int sh = Math.min(src.getHeight(), src.getWidth()); // animated strip: clamp to a square frame 0
        for (int dy = 0; dy < h; dy++) {
            int sy = Math.min(sh - 1, dy * sh / h);
            int ty = cy + dy;
            if (ty < 0 || ty >= atlasH) {
                continue;
            }
            for (int dx = 0; dx < w; dx++) {
                int sx = Math.min(sw - 1, dx * sw / w);
                int tx = cx + dx;
                if (tx < 0 || tx >= atlasW) {
                    continue;
                }
                dst.setPixel(tx, ty, src.getPixel(sx, sy));
            }
        }
    }

    private void warnOnce(String msg, Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            UpscalerMod.LOGGER.warn(msg, t);
        }
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
