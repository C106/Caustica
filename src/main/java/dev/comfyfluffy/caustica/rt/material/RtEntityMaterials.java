package dev.comfyfluffy.caustica.rt.material;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * LabPBR {@code _s}/{@code _n} for block entities (chests, signs, beds, shulker boxes, banners, …),
 * which texture from their own dedicated sprite atlases rather than the block atlas. For each such
 * atlas a parallel {@link RtParallelAtlas} is built and its views are bound into the bindless
 * entity-material arrays at the atlas's albedo slot. The hit shader samples them via the per-type
 * bindless path (prim {@code mat} code 1) — no shader change.
 *
 * <p>Built lazily per atlas on first sight. Render-thread only.
 */
public final class RtEntityMaterials {
    public static final RtEntityMaterials INSTANCE = new RtEntityMaterials();

    private final Map<Identifier, RtParallelAtlas> atlases = new HashMap<>();

    private RtEntityMaterials() {}

    /**
     * The parallel {@code _s}/{@code _n} atlas for a source atlas, created on first sight and retried
     * until the source atlas is ready. Returns null while it isn't. Render-thread only.
     */
    public RtParallelAtlas atlasFor(Identifier atlasLocation) {
        if (atlasLocation == null) {
            return null;
        }
        RtParallelAtlas pa = atlases.get(atlasLocation);
        if (pa == null) {
            pa = new RtParallelAtlas(atlasLocation, label(atlasLocation));
            atlases.put(atlasLocation, pa);
        }
        if (!pa.isReady()) {
            pa.reset(); // source atlas may not have been resolved at first sight; retry until it is
        }
        return pa.isReady() ? pa : null;
    }

    /** The {@code HAS_S}|{@code HAS_N} presence bitmask for a block-entity sprite (0 if atlas not ready). */
    public int ensure(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return 0;
        }
        RtParallelAtlas pa = atlasFor(sprite.atlasLocation());
        return pa != null ? pa.ensure(sprite) : 0;
    }

    /** Re-upload every block-entity atlas that gained sprites since last flush. Pre-trace each frame. */
    public void flushAll() {
        for (RtParallelAtlas pa : atlases.values()) {
            pa.flush();
        }
    }

    /** Free every parallel atlas + drop the registry (pipeline recreate / resource reload). */
    public void reset() {
        for (RtParallelAtlas pa : atlases.values()) {
            pa.close();
        }
        atlases.clear();
    }

    private static String label(Identifier atlasLocation) {
        return "rt_mat_" + atlasLocation.getNamespace() + "_" + atlasLocation.getPath().replace('/', '_');
    }
}
