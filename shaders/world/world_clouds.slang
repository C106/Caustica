// Shared world-stable volumetric cloud density, lighting and front-to-back raymarch.
module world_clouds;

import world_common;

public struct CloudLayer {
    public float3 radiance;
    public float transmittance;
    // Beer-contribution centroid of this ordered layer, or -1 when the layer has no contribution.
    public float distance;
};

public static const int CLOUD_LAYER_MAX = 4;

public struct CloudResult {
    // Front guide where cumulative transmittance first reaches 0.9, or -1 below the opacity threshold.
    public float distance;
    // Near-to-far, independently premultiplied cloud runs. Runs after the fourth merge into layers[3].
    public CloudLayer layers[CLOUD_LAYER_MAX];
    public uint layerCount;
};

// Fixed strata avoid moving every earlier density probe when the visible interval changes. Their shared
// spatial dither rotates per frame and stratum only while DLSS-RR jitter is active.
static const int   CLOUD_PRIMARY_MAX_STEPS = 80;
static const int   CLOUD_NEAR_SUBSTEPS = 8;
static const int   CLOUD_NEAR_STRATA = 2;
static const int   CLOUD_MID_SUBSTEPS = 2;
static const int   CLOUD_MID_STRATA = 6;
static const int   CLOUD_SHADOW_STEPS  = 3;
public static const float CLOUD_MAX_DISTANCE = 3072.0;
static const float CLOUD_EXTINCTION    = 0.052;
static const float CLOUD_GUIDE_MIN_OPACITY = 0.25;
static const float CLOUD_PI            = 3.14159265359;
static const float CLOUD_GOLDEN_RATIO  = 0.61803398875;
static const float CLOUD_STEP_ROTATION = 0.754877666;
static const float CLOUD_DETAIL_XZ_SCALE = 0.0085;
static const float CLOUD_DETAIL_Y_SCALE  = 0.014;

// Cheap conservative preflight for callers that need to decide whether a full coloured march is worth
// scheduling. Keep this slab test identical to marchClouds: returning false must guarantee an empty
// CloudResult, while false positives are harmless and only fall through to the marcher's own early-outs.
public bool cloudRayIntersectsLayer(float3 rayOrigin, float3 rayDir,
                                    float maxDistance, WorldPush pc) {
    float bottom = pc.cloudParams.x;
    float top = pc.cloudParams.y;
    float coverage = clamp(pc.cloudParams.z, 0.0, 1.0);
    float densityScale = clamp(pc.cloudParams.w, 0.0, 4.0);
    float traceLimit = min(max(maxDistance, 0.0), CLOUD_MAX_DISTANCE);
    if (top <= bottom + 1.0e-3 || coverage <= 0.0 || densityScale <= 0.0
            || traceLimit <= 0.0) return false;

    if (abs(rayDir.y) <= 1.0e-7) {
        return rayOrigin.y >= bottom && rayOrigin.y <= top;
    }
    float ta = (bottom - rayOrigin.y) / rayDir.y;
    float tb = (top - rayOrigin.y) / rayDir.y;
    float t0 = max(min(ta, tb), 0.0);
    float t1 = min(max(ta, tb), traceLimit);
    return t1 > t0 + 1.0e-3;
}

// Same progressive 8x8 blue-noise-style ordering used for stochastic alpha hits, kept cloud-private so
// all ray-tracing stages importing this module receive an identical stratum sampler.
static const float CLOUD_DITHER_8X8[64] = {
    0.281250, 0.968750, 0.375000, 0.859375, 0.250000, 0.937500, 0.406250, 0.812500,
    0.593750, 0.187500, 0.734375, 0.046875, 0.578125, 0.218750, 0.703125, 0.015625,
    0.453125, 0.765625, 0.328125, 0.921875, 0.484375, 0.796875, 0.343750, 0.890625,
    0.640625, 0.093750, 0.546875, 0.156250, 0.671875, 0.078125, 0.515625, 0.125000,
    0.265625, 0.953125, 0.421875, 0.828125, 0.296875, 0.984375, 0.390625, 0.843750,
    0.562500, 0.234375, 0.687500, 0.000000, 0.609375, 0.203125, 0.718750, 0.031250,
    0.468750, 0.781250, 0.359375, 0.875000, 0.437500, 0.750000, 0.312500, 0.906250,
    0.656250, 0.062500, 0.500000, 0.140625, 0.625000, 0.109375, 0.531250, 0.171875
};

float cloudStratumJitter(int stepIndex, WorldPush pc) {
    uint2 p = DispatchRaysIndex().xy & uint2(7u, 7u);
    float spatial = CLOUD_DITHER_8X8[int(p.y * 8u + p.x)];
    bool rrEnabled = abs(pc.jitter.x) + abs(pc.jitter.y) > 1.0e-6;
    if (!rrEnabled) return spatial;
    return frac(spatial
              + float(pc.frameIndex & 255u) * CLOUD_GOLDEN_RATIO
              + float(stepIndex) * CLOUD_STEP_ROTATION);
}

// Dave Hoskins' hash13, kept private so importing this module does not add a generic hash symbol to
// callers. Its formula matches the sky shader's star hash exactly.
float cloudHash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

// C1-continuous lattice value noise. Cloud frequencies stay deliberately low so ray-cone/DLSS jitter
// cannot expose individual cells. The macro field is also 3D; detail is no longer responsible for
// turning a two-dimensional silhouette into apparent volume.
float cloudValueNoise3(float3 p) {
    float3 i = floor(p);
    float3 f = frac(p);
    float3 u = f * f * (3.0 - 2.0 * f);
    float n000 = cloudHash13(i + float3(0.0, 0.0, 0.0) + 37.0);
    float n100 = cloudHash13(i + float3(1.0, 0.0, 0.0) + 37.0);
    float n010 = cloudHash13(i + float3(0.0, 1.0, 0.0) + 37.0);
    float n110 = cloudHash13(i + float3(1.0, 1.0, 0.0) + 37.0);
    float n001 = cloudHash13(i + float3(0.0, 0.0, 1.0) + 37.0);
    float n101 = cloudHash13(i + float3(1.0, 0.0, 1.0) + 37.0);
    float n011 = cloudHash13(i + float3(0.0, 1.0, 1.0) + 37.0);
    float n111 = cloudHash13(i + float3(1.0, 1.0, 1.0) + 37.0);
    float x00 = lerp(n000, n100, u.x);
    float x10 = lerp(n010, n110, u.x);
    float x01 = lerp(n001, n101, u.x);
    float x11 = lerp(n011, n111, u.x);
    return lerp(lerp(x00, x10, u.y), lerp(x01, x11, u.y), u.z);
}

// Low-frequency coverage must be independent of height. Without this weather field every long horizontal
// ray crosses roughly the same amount of 3D noise and converges to a uniform fog band even though close-up
// samples are technically volumetric.
float cloudValueNoise2(float2 p) {
    float2 i = floor(p);
    float2 f = frac(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float n00 = cloudHash13(float3(i + float2(0.0, 0.0), 13.0));
    float n10 = cloudHash13(float3(i + float2(1.0, 0.0), 13.0));
    float n01 = cloudHash13(float3(i + float2(0.0, 1.0), 13.0));
    float n11 = cloudHash13(float3(i + float2(1.0, 1.0), 13.0));
    return lerp(lerp(n00, n10, u.x), lerp(n01, n11, u.x), u.y);
}

float cloudWeatherAt(float2 worldXZ, float2 wind) {
    // Synoptic cloud groups remain broad, but no single weather cell should blanket the whole 3 km march.
    float2 weatherP = (worldXZ + wind) * 0.00080;
    return 0.72 * cloudValueNoise2(weatherP)
         + 0.28 * cloudValueNoise2(weatherP * 2.03 + float2(17.7, -9.2));
}

// Coverage selects coherent cloud islands before any 3D lattice work. A second remap compresses the soft
// weather transition: island cores reach full density sooner while clear-sky edges become exactly empty.
float cloudWeatherMask(float weather, float coverage) {
    float cut = lerp(0.82, 0.30, coverage);
    float raw = smoothstep(cut - 0.03, cut + 0.03, weather);
    return smoothstep(0.18, 0.82, raw);
}

float cloudBodyThreshold(float weather, float coverage, float heightErosion) {
    return lerp(0.72, 0.26, coverage) + heightErosion
         + (0.5 - weather) * 0.16;
}

// Remove the broad low-density tail without changing the body threshold itself. The nested smoothstep
// remains continuous, but reaches zero/full much sooner so a vertical path sees either a solid cloud core
// or a clean opening instead of accumulating many weak samples into a translucent veil.
float cloudBodyMask(float field, float threshold) {
    float raw = smoothstep(threshold, threshold + 0.105, field);
    return smoothstep(0.16, 0.58, raw);
}

// Suppress residual low-density products at overlapping weather/body/height edges while increasing only
// coherent cloud cores. The optical boost starts well inside the silhouette: it makes a cloud body opaque
// enough to read as a navigable cluster from within the layer without turning its eroded boundary into fog.
// Keeping the density control additive in the core preserves useful slider headroom at high values.
float cloudStructuredDensity(float body, float heightMask, float weatherMask,
                             float coverage, float densityScale) {
    float structure = body * heightMask * weatherMask;
    float solidShape = smoothstep(0.20, 0.80, structure);
    float coreWeight = smoothstep(0.60, 0.90, structure);
    float localScale = densityScale + 0.70 * coreWeight;
    float coreOpticalBoost = lerp(1.0, 2.5, smoothstep(0.45, 0.80, structure));
    return solidShape * localScale * coreOpticalBoost * smoothstep(0.0, 0.08, coverage);
}

float cloudOctaveLod(float footprintCells) {
    // Stochastic strata and RR can reconstruct a little beyond the strict half-cell cutoff. The bounded
    // 0.90 fade retains medium cauliflower detail at the 80-step horizon without exposing a full
    // under-sampled octave, which previously produced dense horizontal bands.
    return 1.0 - smoothstep(0.25, 0.90, max(footprintCells, 0.0));
}

// Returning the octave's expectation (0.5) once its footprint exceeds a cell preserves the noise mean
// instead of making distant clouds shrink/darken as high-frequency octaves are omitted.
float cloudFilteredNoise3(float3 p, float footprintCells) {
    float lod = cloudOctaveLod(footprintCells);
    if (lod <= 0.0) return 0.5; // branch skips all eight lattice hashes for this octave
    return lerp(0.5, cloudValueNoise3(p), lod);
}

// Variant for callers that already need the exact octave LOD to derive a conservative value bound.
// Keeping the same expectation/remap as cloudFilteredNoise3 makes progressive detail rejection exact.
float cloudFilteredNoise3AtLod(float3 p, float lod) {
    if (lod <= 0.0) return 0.5;
    return lerp(0.5, cloudValueNoise3(p), lod);
}

float cloudDetailFootprint(float3 sampleDelta) {
    return length(float3(sampleDelta.x * CLOUD_DETAIL_XZ_SCALE,
                         sampleDelta.y * CLOUD_DETAIL_Y_SCALE
                             + dot(sampleDelta.xz * CLOUD_DETAIL_XZ_SCALE,
                                   float2(-0.07, 0.11)),
                         sampleDelta.z * CLOUD_DETAIL_XZ_SCALE));
}

// sampleDelta is an integration interval, not a ray-cone/pixel footprint. Filtering the lowest-frequency
// body octave to its expectation before a step spans even one lattice cell makes distant, shallow paths
// spatially constant. Preserve that octave longer; billow/detail octaves keep the stricter filter above.
float cloudMacroNoise3(float3 p, float footprintCells) {
    float lod = 1.0 - smoothstep(0.75, 1.50, max(footprintCells, 0.0));
    if (lod <= 0.0) return 0.5;
    return lerp(0.5, cloudValueNoise3(p), lod);
}

// Two low-frequency 3D octaves define the cloud body. The first octave vertically warps the second,
// and both displace the height coordinate. XZ shear prevents equal-Y samples from lining up into visible
// horizontal strata. sampleDelta is the distance between raymarch samples, used for anisotropic LOD.
float cloudMacroField(float3 worldP, float bottom, float top, float2 wind,
                      float3 sampleDelta, out float heightMask, out float heightErosion) {
    float layerHeight = max(top - bottom, 1.0e-3);
    float h = clamp((worldP.y - bottom) / layerHeight, 0.0, 1.0);
    float2 advected = worldP.xz + wind;

    // Reject exact empty columns and positions outside their strict local bounds before either 3D body
    // octave. The body warp envelope is exactly zero at localH <= 0 and >= 1, so neither boundary can be
    // displaced back into the cloud. Do not tighten these tests into the lower/upper smooth fades: inside
    // (0,1) the base/billow warp can still move a nominally empty point into visible density.
    float2 columnP = advected * 0.0028 + float2(31.7, -14.2);
    float columnNoise = 0.72 * cloudValueNoise2(columnP)
                      + 0.28 * cloudValueNoise2(columnP * 2.07 + float2(-8.1, 19.4));
    float presenceRaw = smoothstep(0.43, 0.57, columnNoise);
    float columnPresence = smoothstep(0.12, 0.45, presenceRaw);

    // Cumulus clouds share a condensation base. Most puffs stay low; squaring the tower seed reserves the
    // full configured thickness for sparse strong peaks, producing isolated cauliflower-like updrafts.
    float lowCumulus = smoothstep(0.43, 0.62, columnNoise);
    float towerRaw = smoothstep(0.50, 0.72, columnNoise);
    float tower = towerRaw * towerRaw;
    float localBottom = 0.08;
    float localTop = min(lerp(0.30, 0.48, lowCumulus) + 0.505 * tower, 0.985);
    float localSpan = max(localTop - localBottom, 0.08);
    float localH = (h - localBottom) / localSpan;
    if (columnPresence <= 0.0 || localH <= 0.0 || localH >= 1.0) {
        heightMask = 0.0;
        heightErosion = 0.0;
        return 0.5;
    }

    // The envelope below determines tower height. Keep the macro field vertically correlated inside a
    // tower so it erodes one coherent cumulus body instead of stacking horizontal equal-height bands.
    const float macroScale = 0.0032;
    const float macroVerticalSpan = 0.55;
    const float2 verticalShear = float2(0.13, -0.09);

    float2 macroXZ = advected * macroScale;
    float macroY = h * macroVerticalSpan + dot(macroXZ, verticalShear);
    float sampleY = sampleDelta.y * (macroVerticalSpan / layerHeight)
                  + dot(sampleDelta.xz * macroScale, verticalShear);
    float macroFootprint = length(float3(sampleDelta.x * macroScale, sampleY,
                                         sampleDelta.z * macroScale));
    float3 macroP = float3(macroXZ.x, macroY, macroXZ.y);

    float base = cloudMacroNoise3(macroP, macroFootprint);
    float verticalWarp = (base - 0.5) * 0.78;
    float3 billowP = macroP * 2.03 + float3(7.3, verticalWarp - 11.1, 19.4);
    // The domain warp can only increase local frequency, so bias its footprint conservatively upward.
    float billow = cloudFilteredNoise3(billowP, macroFootprint * 2.35);
    float macro = 0.67 * base + 0.33 * billow;

    // Warp within each local column rather than against the global slab. The envelope reaches zero at the
    // column boundaries, so no tower can be clipped into a common flat top by the slab intersection.
    float boundedLocalH = clamp(localH, 0.0, 1.0);
    float baseWarpEnvelope = 4.0 * boundedLocalH * (1.0 - boundedLocalH);
    float localWarpEnvelope = baseWarpEnvelope * (2.0 - baseWarpEnvelope);
    float warpedLocalH = localH
            - ((base - 0.5) * 0.30 + (billow - 0.5) * 0.14) * localWarpEnvelope;

    // Keep the shared condensation altitude as a strict lower bound, but carve the visible underside
    // upward by a varying amount. A signed displacement here could put density below localBottom and,
    // together with the body warp, form detached fog shells. Limiting the lift to the lower third instead
    // produces an uneven cloud base without changing the body or tower silhouette above it.
    float undersideNoise = smoothstep(0.20, 0.80, 0.65 * base + 0.35 * billow);
    float undersideLift = lerp(0.015, 0.075, undersideNoise);
    float undersideEnvelope = 1.0 - smoothstep(0.0, 0.32, boundedLocalH);
    float undersideH = warpedLocalH - undersideLift * undersideEnvelope;

    // Narrow local fades keep the column silhouette continuous without accumulating a shared translucent
    // layer. The remaining threshold erosion adds small-scale variation inside the explicit column bounds.
    heightMask = columnPresence
               * smoothstep(0.005, 0.040, undersideH)
               * (1.0 - smoothstep(0.94, 0.995, warpedLocalH));
    float baseErosion = 0.02 * (1.0 - smoothstep(0.025, 0.18, undersideH));
    float topErosion = 0.12 * smoothstep(0.55, 0.96, warpedLocalH);
    heightErosion = baseErosion + topErosion;
    return macro;
}

float cloudDensityAt(float3 worldP, float bottom, float top, float coverage,
                     float densityScale, float2 wind, float3 sampleDelta) {
    float weather = cloudWeatherAt(worldP.xz, wind);
    float weatherMask = cloudWeatherMask(weather, coverage);
    if (weatherMask <= 1.0e-3) return 0.0;

    float heightMask;
    float heightErosion;
    float macro = cloudMacroField(worldP, bottom, top, wind, sampleDelta,
                                  heightMask, heightErosion);
    float envelope = heightMask * weatherMask
                   * smoothstep(0.0, 0.08, coverage) * densityScale;
    if (envelope <= 0.0) return 0.0;

    float threshold = cloudBodyThreshold(weather, coverage, heightErosion);
    float detailFootprint = cloudDetailFootprint(sampleDelta);
    float detailLod0 = cloudOctaveLod(detailFootprint);
    float detailLod1 = cloudOctaveLod(detailFootprint * 2.07);
    float detailLod2 = cloudOctaveLod(detailFootprint * 4.31);
    // A filtered octave is lerp(0.5, noise, lod), so its exact deviation from the expectation is bounded
    // by 0.5*lod. Use the actual three LODs rather than the old worst-case +/-0.17 bound; the small guard
    // covers floating-point evaluation at the smoothstep thresholds without admitting a false reject.
    float detailBound = 0.17 * (0.58 * detailLod0 + 0.29 * detailLod1 + 0.13 * detailLod2)
                      + 1.0e-6;
    if (macro + detailBound <= threshold) return 0.0;
    if (macro - detailBound >= threshold + 0.105) {
        return cloudStructuredDensity(1.0, heightMask, weatherMask, coverage, densityScale);
    }

    // Advect every density octave by the same world displacement. A different detail wind multiplier
    // would make the field deform relative to its DLSS motion vector and leave residual edge trails.
    float2 detailXZ = (worldP.xz + wind) * CLOUD_DETAIL_XZ_SCALE;
    float detailY = (worldP.y - bottom) * CLOUD_DETAIL_Y_SCALE
                  + dot(detailXZ, float2(-0.07, 0.11)) + (macro - 0.5) * 0.65;
    float3 detailP = float3(detailXZ.x, detailY, detailXZ.y);

    // Resolve the largest octave first. After each lookup, bound every unresolved octave around its 0.5
    // expectation and return only when the final body is mathematically forced to exact zero or one.
    float detail0 = cloudFilteredNoise3AtLod(detailP, detailLod0);
    float partialDeviation = 0.58 * (detail0 - 0.5);
    float remainingBound = 0.17 * (0.29 * detailLod1 + 0.13 * detailLod2) + 1.0e-6;
    float partialField = macro + partialDeviation * 0.34;
    if (partialField + remainingBound <= threshold) return 0.0;
    if (partialField - remainingBound >= threshold + 0.105) {
        return cloudStructuredDensity(1.0, heightMask, weatherMask, coverage, densityScale);
    }

    float detail1 = cloudFilteredNoise3AtLod(
            detailP * 2.07 + float3(7.3, -11.1, 19.4), detailLod1);
    partialDeviation += 0.29 * (detail1 - 0.5);
    remainingBound = 0.17 * (0.13 * detailLod2) + 1.0e-6;
    partialField = macro + partialDeviation * 0.34;
    if (partialField + remainingBound <= threshold) return 0.0;
    if (partialField - remainingBound >= threshold + 0.105) {
        return cloudStructuredDensity(1.0, heightMask, weatherMask, coverage, densityScale);
    }

    float detail2 = cloudFilteredNoise3AtLod(
            detailP * 4.31 + float3(-15.2, 8.4, -3.7), detailLod2);
    float detail = 0.58 * detail0 + 0.29 * detail1 + 0.13 * detail2;
    float body = cloudBodyMask(macro + (detail - 0.5) * 0.34, threshold);
    return cloudStructuredDensity(body, heightMask, weatherMask, coverage, densityScale);
}

// Cheaper density used only by the light probes: retain the exact same 3D macro body, warped height
// envelope and first detail octave while omitting the two finer detail octaves.
float cloudShadowDensityAt(float3 worldP, float bottom, float top, float coverage,
                           float densityScale, float2 wind, float3 sampleDelta) {
    float weather = cloudWeatherAt(worldP.xz, wind);
    float weatherMask = cloudWeatherMask(weather, coverage);
    if (weatherMask <= 1.0e-3) return 0.0;

    float heightMask;
    float heightErosion;
    float macro = cloudMacroField(worldP, bottom, top, wind, sampleDelta,
                                  heightMask, heightErosion);
    float envelope = heightMask * weatherMask
                   * smoothstep(0.0, 0.08, coverage) * densityScale;
    if (envelope <= 0.0) return 0.0;

    float threshold = cloudBodyThreshold(weather, coverage, heightErosion);
    float detailFootprint = cloudDetailFootprint(sampleDelta);
    float detailLod = cloudOctaveLod(detailFootprint);
    float detailBound = 0.17 * detailLod + 1.0e-6;
    if (macro + detailBound <= threshold) return 0.0;
    if (macro - detailBound >= threshold + 0.105) {
        return cloudStructuredDensity(1.0, heightMask, weatherMask, coverage, densityScale);
    }

    float2 detailXZ = (worldP.xz + wind) * CLOUD_DETAIL_XZ_SCALE;
    float detailY = (worldP.y - bottom) * CLOUD_DETAIL_Y_SCALE
                  + dot(detailXZ, float2(-0.07, 0.11)) + (macro - 0.5) * 0.65;
    float detail = cloudFilteredNoise3AtLod(
            float3(detailXZ.x, detailY, detailXZ.y), detailLod);
    float body = cloudBodyMask(macro + (detail - 0.5) * 0.34, threshold);
    return cloudStructuredDensity(body, heightMask, weatherMask, coverage, densityScale);
}

float cloudLightTransmittance(float3 worldP, float3 lightDir, float bottom, float top,
                              float coverage, float densityScale, float2 wind) {
    float boundary = lightDir.y >= 0.0 ? top : bottom;
    float lightDistance;
    if (abs(lightDir.y) > 1.0e-4) {
        lightDistance = max(0.0, (boundary - worldP.y) / lightDir.y);
    } else {
        lightDistance = 768.0;
    }
    lightDistance = min(lightDistance, 768.0);
    float probeStep = lightDistance / float(CLOUD_SHADOW_STEPS);
    float3 sampleDelta = lightDir * probeStep;
    float opticalDepth = 0.0;
    for (int i = 0; i < CLOUD_SHADOW_STEPS; i++) {
        float t = (float(i) + 0.5) * probeStep;
        opticalDepth += cloudShadowDensityAt(worldP + lightDir * t, bottom, top,
                                             coverage, densityScale, wind, sampleDelta) * probeStep;
        // The public result clamps tau to 20. Once reached, all remaining non-negative density probes are
        // guaranteed to leave the returned Beer transmittance exactly exp(-20).
        if (opticalDepth * CLOUD_EXTINCTION >= 20.0) return exp(-20.0);
    }
    return exp(-min(opticalDepth * CLOUD_EXTINCTION, 20.0));
}

// Density-only counterpart for DLSS-RR motion/depth guides. It deliberately mirrors marchClouds' fixed
// strata, near-camera subdivision, tiny-density gate and T=0.9 crossing calculation, but omits all cloud
// lighting and ordered radiance-layer moments. Once committed transmittance falls below 0.75, the final
// opacity is irreversibly above CLOUD_GUIDE_MIN_OPACITY and the already-recorded crossing can be returned.
public float marchCloudGuideDistance(float3 rayOrigin, float3 rayDir,
                                     float maxDistance, WorldPush pc) {
    float bottom = pc.cloudParams.x;
    float top = pc.cloudParams.y;
    float coverage = clamp(pc.cloudParams.z, 0.0, 1.0);
    float densityScale = clamp(pc.cloudParams.w, 0.0, 4.0);
    float traceLimit = min(max(maxDistance, 0.0), CLOUD_MAX_DISTANCE);
    if (top <= bottom + 1.0e-3 || coverage <= 0.0 || densityScale <= 0.0
            || traceLimit <= 0.0) return -1.0;

    float t0, t1;
    if (abs(rayDir.y) > 1.0e-7) {
        float ta = (bottom - rayOrigin.y) / rayDir.y;
        float tb = (top - rayOrigin.y) / rayDir.y;
        t0 = max(min(ta, tb), 0.0);
        t1 = min(max(ta, tb), traceLimit);
    } else {
        if (rayOrigin.y < bottom || rayOrigin.y > top) return -1.0;
        t0 = 0.0;
        t1 = traceLimit;
    }
    if (t1 <= t0 + 1.0e-3) return -1.0;

    float phase = frac(max(pc.waterParams.w, 0.0) * (1.0 / 3600.0));
    float angle = phase * (2.0 * CLOUD_PI);
    float2 wind = float2(cos(angle), sin(angle)) * 600.0;

    float transmittance = 1.0;
    float guideCandidate = -1.0;
    float interval = t1 - t0;
    float targetStep = min(32.0, 2.0 / max(abs(rayDir.y), 1.0e-4));
    int stepCount = max(1, int(ceil(interval / targetStep)));
    if (stepCount > CLOUD_PRIMARY_MAX_STEPS) {
        targetStep = interval / float(CLOUD_PRIMARY_MAX_STEPS);
        stepCount = CLOUD_PRIMARY_MAX_STEPS;
    }

    for (int i = 0; i < CLOUD_PRIMARY_MAX_STEPS; i++) {
        if (i >= stepCount) break;
        float tA = t0 + float(i) * targetStep;
        float tB = min(tA + targetStep, t1);
        float stepLength = tB - tA;
        if (stepLength <= 0.0) continue;

        int densitySampleCount = 1;
        if (t0 <= 1.0e-3 && targetStep > 8.0) {
            if (i < CLOUD_NEAR_STRATA) densitySampleCount = CLOUD_NEAR_SUBSTEPS;
            else if (i < CLOUD_MID_STRATA) densitySampleCount = CLOUD_MID_SUBSTEPS;
        }
        float densityIntegral = 0.0;
        float stepOpticalDepth = 0.0;
        for (int substep = 0; substep < CLOUD_NEAR_SUBSTEPS; substep++) {
            if (substep >= densitySampleCount) break;
            float subA = lerp(tA, tB, float(substep) / float(densitySampleCount));
            float subB = lerp(tA, tB, float(substep + 1) / float(densitySampleCount));
            float subLength = subB - subA;
            int ditherIndex = i * CLOUD_NEAR_SUBSTEPS + substep;
            float sampleT = lerp(subA, subB, cloudStratumJitter(ditherIndex, pc));
            float3 densitySampleP = rayOrigin + rayDir * sampleT;
            densitySampleP.xz += pc.waterAnchor.zw;
            float density = cloudDensityAt(densitySampleP, bottom, top, coverage, densityScale,
                                           wind, rayDir * subLength);
            density *= 1.0 - smoothstep(0.75 * CLOUD_MAX_DISTANCE, CLOUD_MAX_DISTANCE, sampleT);

            float weightedDensity = max(density, 0.0) * subLength;
            float sampleOpticalDepth = min(weightedDensity * CLOUD_EXTINCTION, 20.0);
            if (guideCandidate < 0.0 && sampleOpticalDepth > 0.0) {
                float sampleTBefore = transmittance * exp(-stepOpticalDepth);
                float sampleTAfter = sampleTBefore * exp(-sampleOpticalDepth);
                if (sampleTBefore > 0.9 && sampleTAfter <= 0.9) {
                    float crossing = log(sampleTBefore / 0.9) / sampleOpticalDepth;
                    guideCandidate = lerp(subA, subB, clamp(crossing, 0.0, 1.0));
                }
            }
            stepOpticalDepth = min(stepOpticalDepth + sampleOpticalDepth, 20.0);
            densityIntegral += weightedDensity;
        }

        // Keep this cutoff identical to the coloured marcher: ignored wisps must not attenuate a guide.
        if (densityIntegral <= 1.0e-4 * stepLength) continue;

        float alpha = 1.0 - exp(-stepOpticalDepth);
        transmittance *= 1.0 - alpha;
        if (transmittance < 1.0 - CLOUD_GUIDE_MIN_OPACITY) {
            return guideCandidate;
        }
    }

    return (1.0 - transmittance) > CLOUD_GUIDE_MIN_OPACITY && guideCandidate >= 0.0
            ? guideCandidate : -1.0;
}

// shadowStride controls how many occupied density strata may share one directional-light probe. A
// spatial cap below still refreshes long/wide strata, and every newly detected cloud run starts with a
// fresh probe so visibility never leaks across a clear interval. Density integration and layer moments
// remain independent of this lighting-only quality control.
public CloudResult marchClouds(float3 rayOrigin, float3 rayDir, float maxDistance, WorldPush pc,
                               int shadowStride) {
    CloudResult result;
    result.distance = -1.0;
    result.layerCount = 0u;
    for (int i = 0; i < CLOUD_LAYER_MAX; i++) {
        result.layers[i].radiance = float3(0.0, 0.0, 0.0);
        result.layers[i].transmittance = 1.0;
        result.layers[i].distance = 0.0; // contribution moment until final normalisation
    }

    float bottom = pc.cloudParams.x;
    float top = pc.cloudParams.y;
    float coverage = clamp(pc.cloudParams.z, 0.0, 1.0);
    float densityScale = clamp(pc.cloudParams.w, 0.0, 4.0);
    float traceLimit = min(max(maxDistance, 0.0), CLOUD_MAX_DISTANCE);
    if (top <= bottom + 1.0e-3 || coverage <= 0.0 || densityScale <= 0.0
            || traceLimit <= 0.0) return result;

    // Robust horizontal-slab intersection. Near-horizontal rays only march when their origin is already
    // inside the layer, avoiding division by a vanishing Y direction and horizon NaNs.
    float t0, t1;
    if (abs(rayDir.y) > 1.0e-7) {
        float ta = (bottom - rayOrigin.y) / rayDir.y;
        float tb = (top - rayOrigin.y) / rayDir.y;
        t0 = max(min(ta, tb), 0.0);
        t1 = min(max(ta, tb), traceLimit);
    } else {
        if (rayOrigin.y < bottom || rayOrigin.y > top) return result;
        t0 = 0.0;
        t1 = traceLimit;
    }
    if (t1 <= t0 + 1.0e-3) return result;

    // A circular wind path has matching position and velocity at the 3600 s wrap. Unlike a linear
    // frac(time) translation over aperiodic noise, it cannot pop when the CPU timer wraps to zero.
    float phase = frac(max(pc.waterParams.w, 0.0) * (1.0 / 3600.0));
    float angle = phase * (2.0 * CLOUD_PI);
    float2 wind = float2(cos(angle), sin(angle)) * 600.0;

    float3 lightDir = pc.lightDir.xyz;
    float lightLen2 = dot(lightDir, lightDir);
    bool hasDirectionalLight = lightLen2 > 1.0e-8
                            && max(pc.lightRadiance.x, max(pc.lightRadiance.y, pc.lightRadiance.z)) > 0.0;
    if (lightLen2 > 1.0e-8) lightDir *= rsqrt(lightLen2);
    else lightDir = float3(0.0, 1.0, 0.0);

    float day = clamp(pc.sunDir.w, 0.0, 1.0);
    // Saturated sky fill keeps shadows cool instead of neutral grey. Direct light retains the atmospheric
    // colour temperature already carried by pc.lightRadiance, including its physical sunset warming.
    float3 ambient = lerp(float3(0.008, 0.014, 0.032), float3(0.16, 0.21, 0.27), day);
    float mu = clamp(dot(rayDir, lightDir), -1.0, 1.0);
    const float cloudG = 0.55;
    float phaseHg = (1.0 - cloudG * cloudG)
                  / (4.0 * CLOUD_PI * pow(max(1.0 + cloudG * cloudG - 2.0 * cloudG * mu, 1.0e-3), 1.5));
    // Normalise the g=.55 HG range (~0.015 backscatter to ~0.609 forward scatter) into a bounded
    // silver-lining boost. The peak remains close to the old HDR energy ceiling.
    float forwardLobe = clamp((phaseHg - 0.015) / 0.594, 0.0, 1.0);
    float directScale = lerp(0.032, 0.075, forwardLobe);

    float transmittance = 1.0;
    float guideCandidate = -1.0;
    int currentLayer = -1;
    int emptyStrata = 0;
    int resolvedShadowStride = max(shadowStride, 1);
    bool shadowCacheValid = false;
    float cachedShadowT = 1.0;
    float cachedShadowDistance = -1.0;
    int occupiedStrataSinceShadow = resolvedShadowStride;

    float interval = t1 - t0;
    float targetStep = min(32.0, 2.0 / max(abs(rayDir.y), 1.0e-4));
    int stepCount = max(1, int(ceil(interval / targetStep)));
    // Configured slabs above 160 blocks cannot retain the two-block vertical target within the hard
    // ceiling. Widen all fixed strata once rather than dropping the far portion of the cloud interval.
    if (stepCount > CLOUD_PRIMARY_MAX_STEPS) {
        targetStep = interval / float(CLOUD_PRIMARY_MAX_STEPS);
        stepCount = CLOUD_PRIMARY_MAX_STEPS;
    }

    for (int i = 0; i < CLOUD_PRIMARY_MAX_STEPS; i++) {
        if (i >= stepCount) break;
        float tA = t0 + float(i) * targetStep;
        float tB = min(tA + targetStep, t1);
        float stepLength = tB - tA;
        if (stepLength <= 0.0) continue;
        // A wide shallow stratum with one stochastic density lookup is biased transparent after the
        // nonlinear Beer exponential: a narrow nearby cloud alternates between fully hit and fully missed,
        // then RR averages the two. When the camera starts inside the slab, resolve the first two parents at
        // 8x and the next four at 2x, sum optical depth first, and evaluate the exponential only once below.
        // Lighting remains one probe per parent stratum, keeping the extra near-field cost bounded.
        int densitySampleCount = 1;
        if (t0 <= 1.0e-3 && targetStep > 8.0) {
            if (i < CLOUD_NEAR_STRATA) densitySampleCount = CLOUD_NEAR_SUBSTEPS;
            else if (i < CLOUD_MID_STRATA) densitySampleCount = CLOUD_MID_SUBSTEPS;
        }
        float densityIntegral = 0.0;
        float fixedDistanceMoment = 0.0;
        float lightingDistanceMoment = 0.0;
        float stepOpticalDepth = 0.0;
        for (int substep = 0; substep < CLOUD_NEAR_SUBSTEPS; substep++) {
            if (substep >= densitySampleCount) break;
            float subA = lerp(tA, tB, float(substep) / float(densitySampleCount));
            float subB = lerp(tA, tB, float(substep + 1) / float(densitySampleCount));
            float subLength = subB - subA;
            int ditherIndex = i * CLOUD_NEAR_SUBSTEPS + substep;
            float sampleT = lerp(subA, subB, cloudStratumJitter(ditherIndex, pc));
            float3 densitySampleP = rayOrigin + rayDir * sampleT;
            // Clouds use the full float XZ origin and must not inherit the wrapped water domain's visible
            // discontinuity whenever the terrain origin crosses a 4096-block boundary.
            densitySampleP.xz += pc.waterAnchor.zw;
            float density = cloudDensityAt(densitySampleP, bottom, top, coverage, densityScale,
                                           wind, rayDir * subLength);
            density *= 1.0 - smoothstep(0.75 * CLOUD_MAX_DISTANCE, CLOUD_MAX_DISTANCE, sampleT);

            float weightedDensity = max(density, 0.0) * subLength;
            float sampleOpticalDepth = min(weightedDensity * CLOUD_EXTINCTION, 20.0);
            if (guideCandidate < 0.0 && sampleOpticalDepth > 0.0) {
                float sampleTBefore = transmittance * exp(-stepOpticalDepth);
                float sampleTAfter = sampleTBefore * exp(-sampleOpticalDepth);
                if (sampleTBefore > 0.9 && sampleTAfter <= 0.9) {
                    float crossing = log(sampleTBefore / 0.9) / sampleOpticalDepth;
                    guideCandidate = lerp(subA, subB, clamp(crossing, 0.0, 1.0));
                }
            }
            stepOpticalDepth = min(stepOpticalDepth + sampleOpticalDepth, 20.0);
            densityIntegral += weightedDensity;
            fixedDistanceMoment += weightedDensity * (0.5 * (subA + subB));
            lightingDistanceMoment += weightedDensity * sampleT;
        }

        if (densityIntegral <= 1.0e-4 * stepLength) {
            if (currentLayer >= 0) {
                emptyStrata++;
                if (emptyStrata > 1) {
                    currentLayer = -1;
                    emptyStrata = 0;
                }
            }
            continue;
        }

        bool startedCloudRun = currentLayer < 0;
        if (currentLayer < 0) {
            if (result.layerCount < uint(CLOUD_LAYER_MAX)) {
                currentLayer = int(result.layerCount);
                result.layerCount++;
            } else {
                currentLayer = CLOUD_LAYER_MAX - 1; // later runs composite behind the farthest retained layer
            }
        }
        emptyStrata = 0;

        float alpha = 1.0 - exp(-stepOpticalDepth);
        float transmittanceAfter = transmittance * (1.0 - alpha);
        float stratumCenter = fixedDistanceMoment / max(densityIntegral, 1.0e-6);
        float lightingDistance = lightingDistanceMoment / max(densityIntegral, 1.0e-6);
        float3 sampleP = rayOrigin + rayDir * lightingDistance;
        sampleP.xz += pc.waterAnchor.zw;

        {
            float3 lighting = ambient;
            if (hasDirectionalLight) {
                // Adjacent occupied strata see a slowly varying three-sample light integral. Reuse it for
                // at most shadowStride strata and 48 world blocks; the latter keeps vertical/grazing paths
                // bounded even when their integration strata have very different lengths.
                bool refreshShadow = startedCloudRun || !shadowCacheValid
                                  || occupiedStrataSinceShadow >= resolvedShadowStride
                                  || abs(lightingDistance - cachedShadowDistance) > 48.0;
                if (refreshShadow) {
                    cachedShadowT = cloudLightTransmittance(sampleP, lightDir, bottom, top,
                                                            coverage, densityScale, wind);
                    cachedShadowDistance = lightingDistance;
                    occupiedStrataSinceShadow = 1;
                    shadowCacheValid = true;
                } else {
                    occupiedStrataSinceShadow++;
                }
                float shadowT = cachedShadowT;
                float sqrtT = sqrt(clamp(shadowT, 0.0, 1.0));
                float singleVisibility = 0.06 + 0.94 * sqrtT;
                // Low-order multiple scattering fills dense Mie media with spectrally neutral sunlight.
                // It grows only as the direct probe is occluded, whitening cloud cores without flattening
                // the sun-facing edge contrast or adding a hand-authored sunset tint.
                float multipleVisibility = 0.010 * (1.0 - sqrtT);
                lighting += pc.lightRadiance.xyz
                          * (directScale * singleVisibility + multipleVisibility);
            }
            // A strong analytic height tint turns every cloud into horizontal bands. Keep only a subtle
            // lift and let the 3-probe Beer shadow create the visible edge/interior contrast.
            float height = clamp((sampleP.y - bottom) / max(top - bottom, 1.0e-3), 0.0, 1.0);
            lighting *= lerp(0.92, 1.0, height);
            CloudLayer layer = result.layers[currentLayer];
            float layerContributionWeight = layer.transmittance * alpha;
            layer.radiance += layerContributionWeight * lighting;
            layer.distance += layerContributionWeight * stratumCenter;
            layer.transmittance *= 1.0 - alpha;
            result.layers[currentLayer] = layer;

            transmittance = transmittanceAfter;
            if (transmittance < 0.015) break; // Beer early-out for opaque cloud paths
        }
    }

    for (int i = 0; i < CLOUD_LAYER_MAX; i++) {
        if (i >= int(result.layerCount)) break;
        float layerWeight = 1.0 - result.layers[i].transmittance;
        result.layers[i].distance = layerWeight > 1.0e-5
                ? result.layers[i].distance / layerWeight
                : -1.0;
        result.layers[i].radiance = max(result.layers[i].radiance, float3(0.0, 0.0, 0.0));
    }

    // Visible thin clouds need their own moving guide as well: leaving them on the static sky/terrain guide
    // makes RR accumulate the background through the cloud until a hard opacity threshold is crossed.
    if ((1.0 - transmittance) > CLOUD_GUIDE_MIN_OPACITY && guideCandidate >= 0.0) {
        result.distance = guideCandidate;
    }
    return result;
}
