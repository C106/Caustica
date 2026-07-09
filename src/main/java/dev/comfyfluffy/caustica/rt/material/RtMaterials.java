package dev.comfyfluffy.caustica.rt.material;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Heuristic PBR material classifier for blocks that carry only albedo. Assigns each block a
 * {@code (roughness, metalness)} pair from its {@link SoundType} (metal/glass) plus a small set of
 * known smooth dielectrics. Per-prim {@code mat} lanes store the pair; the GGX BRDF reads them.
 */
public final class RtMaterials {
    private RtMaterials() {}

    private static final float DEFAULT_ROUGH = 0.9f;
    private static final float METAL_ROUGH = 0.3f;
    private static final float GLASS_ROUGH = 0.1f;
    private static final float SMOOTH_ROUGH = 0.35f;

    /** Water roughness; near-smooth so DLSS-RR resolves stable reflections. */
    public static final float WATER_ROUGH = 0.08f;
    /** Lava: opaque emitter, moderately rough. */
    public static final float LAVA_ROUGH = 0.7f;
    /** Default entity roughness. */
    public static final float ENTITY_ROUGH = 0.8f;

    private static final Set<Block> SMOOTH = Set.of(
            Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR,
            Blocks.SMOOTH_STONE, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.POLISHED_GRANITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_ANDESITE,
            Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_BLACKSTONE,
            Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE);

    /** Perceptual roughness for this block's surface. */
    public static float roughness(BlockState state) {
        if (state == null) {
            return DEFAULT_ROUGH;
        }
        SoundType sound = state.getSoundType();
        if (isMetal(sound)) {
            return METAL_ROUGH;
        }
        if (sound == SoundType.GLASS) {
            return GLASS_ROUGH;
        }
        if (SMOOTH.contains(state.getBlock())) {
            return SMOOTH_ROUGH;
        }
        return DEFAULT_ROUGH;
    }

    /** Metalness (1 = conductor: F0 tinted by albedo, no diffuse; 0 = dielectric). */
    public static float metalness(BlockState state) {
        return state != null && isMetal(state.getSoundType()) ? 1f : 0f;
    }

    private static boolean isMetal(SoundType sound) {
        return sound == SoundType.METAL || sound == SoundType.COPPER
                || sound == SoundType.NETHERITE_BLOCK || sound == SoundType.ANVIL
                || sound == SoundType.CHAIN;
    }
}
