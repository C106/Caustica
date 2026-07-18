package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link CausticaConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dcaustica.*} startup surface. DLSS-RR quality is the exception: the render resolution
 * is queried from NGX for the chosen quality mode on every resize (see
 * {@code RtDlssRr.queryOptimalRenderSize}), and the RR feature itself is recreated live whenever
 * {@code quality} changes (see {@code RtDlssRr.ensureFeature}), so it is safe to expose here.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /** Runtime-tunable RT options, in display order. Paired two-per-row by {@code OptionsList.addSmall}. */
    public static OptionInstance<?>[] runtimeOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
            spp(),
            maxBounces(),
            sunSize(),
            waterWaves(),
            entities(),
            particles(),
            fogEnabled(),
            fogDensity(),
            fogAmbientDensityFloor(),
            fogAmbientLightCoupling(),
            fogNoiseStrength(),
            fogNoiseScale(),
            fogHeightFalloff(),
            fogVerticalOpticalDepth(),
            fogBaseHeight(),
            atmosphericScattering(),
            scatteringStrength(),
            dlssQuality(),
            hdrEnabled(),
            hdrPaperWhite(),
            hdrPeak(),
            debugView(),
        };
    }

    /** Runtime-tunable volumetric cloud options shown on the dedicated cloud settings screen. */
    public static OptionInstance<?>[] cloudOptions() {
        return new OptionInstance<?>[] {
            volumetricClouds(),
            cloudHeight(),
            cloudThickness(),
            cloudCoverage(),
            cloudDensity(),
            cloudShape(),
            cloudBottomVariation(),
            cloudDetailStrength(),
            cloudEdgeErosion(),
        };
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "caustica.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.value(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        // Stored in radians via the degrees->radians sanitizer; the slider works in tenths of a degree.
        FloatSetting setting = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "caustica.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> volumetricClouds() {
        return bool("caustica.rt.volumetricClouds", CausticaConfig.Rt.Composite.VOLUMETRIC_CLOUDS);
    }

    private static OptionInstance<Integer> cloudHeight() {
        IntSetting setting = CausticaConfig.Rt.Composite.CLOUD_HEIGHT;
        return new OptionInstance<>(
            "caustica.rt.cloudHeight",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.rt.cloudHeight.tooltip")),
            (caption, blocks) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.rt.cloudHeight.value", blocks)),
            new OptionInstance.IntRange(64, 384),
            Math.clamp(setting.value(), 64, 384),
            setting::set);
    }

    private static OptionInstance<Integer> cloudThickness() {
        IntSetting setting = CausticaConfig.Rt.Composite.CLOUD_THICKNESS;
        return new OptionInstance<>(
            "caustica.rt.cloudThickness",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.rt.cloudThickness.tooltip")),
            (caption, blocks) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.rt.cloudThickness.value", blocks)),
            new OptionInstance.IntRange(32, 256),
            Math.clamp(setting.value(), 32, 256),
            setting::set);
    }

    private static OptionInstance<Integer> cloudCoverage() {
        FloatSetting setting = CausticaConfig.Rt.Composite.CLOUD_COVERAGE;
        return new OptionInstance<>(
            "caustica.rt.cloudCoverage",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.rt.cloudCoverage.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Integer> cloudDensity() {
        FloatSetting setting = CausticaConfig.Rt.Composite.CLOUD_DENSITY;
        return new OptionInstance<>(
            "caustica.rt.cloudDensity",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.rt.cloudDensity.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(10, 200),
            Math.clamp(Math.round(setting.value() * 100.0f), 10, 200),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Integer> cloudShape() {
        return cloudPercent("caustica.rt.cloudShape", CausticaConfig.Rt.Composite.CLOUD_SHAPE);
    }

    private static OptionInstance<Integer> cloudBottomVariation() {
        return cloudPercent("caustica.rt.cloudBottomVariation",
                CausticaConfig.Rt.Composite.CLOUD_BOTTOM_VARIATION);
    }

    private static OptionInstance<Integer> cloudDetailStrength() {
        return cloudPercent("caustica.rt.cloudDetailStrength",
                CausticaConfig.Rt.Composite.CLOUD_DETAIL_STRENGTH);
    }

    private static OptionInstance<Integer> cloudEdgeErosion() {
        return cloudPercent("caustica.rt.cloudEdgeErosion",
                CausticaConfig.Rt.Composite.CLOUD_EDGE_EROSION);
    }

    private static OptionInstance<Integer> cloudPercent(String key, FloatSetting setting) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 200),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 200),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Boolean> entities() {
        return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
    }

    private static OptionInstance<Boolean> fogEnabled() {
        return bool("caustica.options.rt.fog", CausticaConfig.Rt.Fog.ENABLED);
    }

    private static OptionInstance<Integer> fogDensity() {
        FloatSetting setting = CausticaConfig.Rt.Fog.DENSITY;
        int initialPosition = encodeFogDensity(setting.value());
        return new OptionInstance<>(
            "caustica.options.rt.fogDensity",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fogDensity.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.5f", decodeFogDensity(position)))),
            new OptionInstance.IntRange(0, 1000),
            initialPosition,
            position -> setting.set(decodeFogDensity(position)));
    }

    private static int encodeFogDensity(float density) {
        double normalized = Math.clamp(density / 0.02, 0.0, 1.0);
        return Math.clamp((int) Math.round(Math.sqrt(normalized) * 1000.0), 0, 1000);
    }

    private static float decodeFogDensity(int position) {
        float normalized = Math.clamp(position, 0, 1000) / 1000.0f;
        return 0.02f * normalized * normalized;
    }

    private static OptionInstance<Integer> fogAmbientDensityFloor() {
        FloatSetting setting = CausticaConfig.Rt.Fog.AMBIENT_DENSITY_FLOOR;
        return new OptionInstance<>(
            "caustica.options.rt.fogAmbientDensityFloor",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.fogAmbientDensityFloor.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Integer> fogAmbientLightCoupling() {
        FloatSetting setting = CausticaConfig.Rt.Fog.AMBIENT_LIGHT_COUPLING;
        return new OptionInstance<>(
            "caustica.options.rt.fogAmbientLightCoupling",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.fogAmbientLightCoupling.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 25),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 25),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Integer> fogNoiseStrength() {
        FloatSetting setting = CausticaConfig.Rt.Fog.NOISE_STRENGTH;
        return new OptionInstance<>(
            "caustica.options.rt.fogNoiseStrength",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.fogNoiseStrength.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Integer> fogNoiseScale() {
        FloatSetting setting = CausticaConfig.Rt.Fog.NOISE_SCALE;
        int initialSize = Math.round(1.0f / Math.max(setting.value(), 1.0e-6f));
        return new OptionInstance<>(
            "caustica.options.rt.fogNoiseScale",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.fogNoiseScale.tooltip")),
            (caption, blocks) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.fogNoiseScale.value", blocks)),
            new OptionInstance.IntRange(25, 500),
            Math.clamp(initialSize, 25, 500),
            blocks -> setting.set(1.0f / blocks));
    }

    private static OptionInstance<Integer> fogHeightFalloff() {
        FloatSetting setting = CausticaConfig.Rt.Fog.HEIGHT_FALLOFF;
        return new OptionInstance<>(
            "caustica.options.rt.fogHeightFalloff",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fogHeightFalloff.tooltip")),
            (caption, tenThousandths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.4f", tenThousandths / 10000.0))),
            new OptionInstance.IntRange(0, 1000),
            Math.clamp(Math.round(setting.value() * 10000.0f), 0, 1000),
            tenThousandths -> setting.set(tenThousandths / 10000.0f));
    }

    private static OptionInstance<Integer> fogVerticalOpticalDepth() {
        FloatSetting setting = CausticaConfig.Rt.Fog.VERTICAL_OPTICAL_DEPTH;
        return new OptionInstance<>(
            "caustica.options.rt.fogVerticalOpticalDepth",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.fogVerticalOpticalDepth.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.1fx", tenths / 10.0))),
            new OptionInstance.IntRange(10, 120),
            Math.clamp(Math.round(setting.value() * 10.0f), 10, 120),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> fogBaseHeight() {
        IntSetting setting = CausticaConfig.Rt.Fog.BASE_HEIGHT;
        return new OptionInstance<>(
            "caustica.options.rt.fogBaseHeight",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fogBaseHeight.tooltip")),
            (caption, height) -> Options.genericValueLabel(caption, height),
            new OptionInstance.IntRange(-64, 320),
            Math.clamp(setting.value(), -64, 320),
            setting::set);
    }

    private static OptionInstance<Boolean> atmosphericScattering() {
        return bool("caustica.options.rt.atmosphericScattering", CausticaConfig.Rt.Fog.ATMOSPHERIC_SCATTERING);
    }

    private static OptionInstance<Integer> scatteringStrength() {
        FloatSetting setting = CausticaConfig.Rt.Fog.SCATTERING_STRENGTH;
        return new OptionInstance<>(
            "caustica.options.rt.scatteringStrength",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.scatteringStrength.tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2fx", hundredths / 100.0))),
            new OptionInstance.IntRange(0, 400),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 400),
            hundredths -> setting.set(hundredths / 100.0f));
    }

    // NVSDK_NGX_PerfQuality_Value, ordered performance -> quality for the slider. Per NVIDIA's DLSS-RR
    // programming guide, Ray Reconstruction only supports Performance(0), Balanced(1), Quality(2),
    // Ultra-Performance(3), and DLAA(5) — Ultra Quality(4) is not a valid PerfQualityValue for RR (its
    // optimal-settings query returns a zeroed render size for it) and is deliberately excluded here.
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        int initialQuality = DLSS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = DLSS_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "caustica.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssQuality." + DLSS_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, DLSS_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(DLSS_QUALITY_ORDER.get(position)));
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        return bool("caustica.options.rt.hdr", CausticaConfig.Rt.Hdr.ENABLED);
    }

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.value()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> hdrPeak() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.value()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Codec.INT),
            Math.clamp(setting.value(), 0, 9),
            setting::set);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }
}
