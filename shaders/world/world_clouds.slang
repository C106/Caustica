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
public static const int CLOUD_PRIMARY_MAX_STEPS = 80;
public static const int CLOUD_SPECULAR_MAX_STEPS = 64;
public static const int CLOUD_DIFFUSE_MAX_STEPS = 40;
static const int   CLOUD_NEAR_SUBSTEPS = 8;
static const int   CLOUD_NEAR_STRATA = 2;
static const int   CLOUD_MID_SUBSTEPS = 2;
static const int   CLOUD_MID_STRATA = 6;
static const int   CLOUD_SHADOW_STEPS  = 5;
public static const float CLOUD_MAX_DISTANCE = 3072.0;
static const float CLOUD_EXTINCTION    = 0.052;
static const float CLOUD_GUIDE_MIN_OPACITY = 0.25;
static const float CLOUD_PI            = 3.14159265359;
static const float CLOUD_GOLDEN_RATIO  = 0.61803398875;
static const float CLOUD_STEP_ROTATION = 0.754877666;
static const float CLOUD_DETAIL_XZ_SCALE = 0.0085;
static const float CLOUD_DETAIL_Y_SCALE  = 0.014;
static const float CLOUD_BODY_TRANSITION = 0.12;
static const float CLOUD_EDGE_EROSION_SCALE = 8.73;

// Derive one weather-adjusted cloud description at every public entry point. Multiplicative/remapped
// controls preserve GUI zero values, while rain and thunder continuously close gaps, deepen the volume and
// expand its vertical support. The same values therefore reach visibility, guides and light probes.
void resolveCloudRenderParams(WorldPush pc, out float bottom, out float top,
                              out float coverage, out float densityScale,
                              out float4 shapeParams) {
    bottom = pc.cloudParams.x;
    top = pc.cloudParams.y;
    float baseCoverage = clamp(pc.cloudParams.z, 0.0, 1.0);
    densityScale = clamp(pc.cloudParams.w, 0.0, 4.0);
    shapeParams = clamp(pc.cloudShapeParams, 0.0, 2.0);

    float2 weather = weatherAmounts(pc);
    float rain = weather.x;
    float thunder = weather.y;
    // The complement-power remap leaves coverage=0 and coverage=1 untouched, but turns an ordinary
    // partially cloudy GUI setting into a coherent overcast during rain instead of adding a separate layer.
    float coverageExponent = 1.0 + 1.8 * rain + 0.8 * thunder;
    coverage = 1.0 - pow(max(1.0 - baseCoverage, 0.0), coverageExponent);
    densityScale *= (1.0 + 0.35 * rain + 0.25 * thunder);

    float baseThickness = max(top - bottom, 0.0);
    bottom -= baseThickness * (0.04 * rain + 0.03 * thunder);
    top += baseThickness * (0.10 * rain + 0.06 * thunder);

    shapeParams.x = min(shapeParams.x * (1.0 + 0.12 * rain + 0.10 * thunder), 2.0);
    shapeParams.y = min(shapeParams.y * (1.0 + 0.10 * rain + 0.12 * thunder), 2.0);
    shapeParams.z = min(shapeParams.z * (1.0 + 0.08 * rain + 0.08 * thunder), 2.0);
    shapeParams.w = min(shapeParams.w * (1.0 + 0.12 * rain + 0.15 * thunder), 2.0);
}

// Cheap conservative preflight for callers that need to decide whether a full coloured march is worth
// scheduling. Keep this slab test identical to marchClouds: returning false must guarantee an empty
// CloudResult, while false positives are harmless and only fall through to the marcher's own early-outs.
public bool cloudRayIntersectsLayer(float3 rayOrigin, float3 rayDir,
                                    float maxDistance, WorldPush pc) {
    float bottom, top, coverage, densityScale;
    float4 shapeParams;
    resolveCloudRenderParams(pc, bottom, top, coverage, densityScale, shapeParams);
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

float cloudJitterBase(WorldPush pc, out bool rotatesPerStratum) {
    float spatial = orderedDither8x8(DispatchRaysIndex().xy);
    rotatesPerStratum = abs(pc.jitter.x) + abs(pc.jitter.y) > 1.0e-6;
    return rotatesPerStratum
            ? spatial + float(pc.frameIndex & 255u) * CLOUD_GOLDEN_RATIO
            : spatial;
}

float cloudStratumJitter(int stepIndex, float jitterBase, bool rotatesPerStratum) {
    return rotatesPerStratum
            ? frac(jitterBase + float(stepIndex) * CLOUD_STEP_ROTATION)
            : jitterBase;
}

float2 cloudWind(WorldPush pc) {
    return pc.cloudMotion.zw;
}

public float3 cloudMotionVector(WorldPush pc) {
    return float3(pc.cloudMotion.x, 0.0, pc.cloudMotion.y);
}

// Dave Hoskins' hash13, kept private so importing this module does not add a generic hash symbol to
// callers. Its formula matches the sky shader's star hash exactly.
float cloudHash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

// C2-continuous lattice value noise. Quintic interpolation removes the repeated cubic shoulder visible
// where several cloud octaves cross the same threshold.
float cloudValueNoise3(float3 p) {
    float3 i = floor(p);
    float3 f = frac(p);
    float3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
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
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n00 = cloudHash13(float3(i + float2(0.0, 0.0), 13.0));
    float n10 = cloudHash13(float3(i + float2(1.0, 0.0), 13.0));
    float n01 = cloudHash13(float3(i + float2(0.0, 1.0), 13.0));
    float n11 = cloudHash13(float3(i + float2(1.0, 1.0), 13.0));
    return lerp(lerp(n00, n10, u.x), lerp(n01, n11, u.x), u.y);
}

float2 cloudRotate2(float2 p) {
    return float2(0.8 * p.x - 0.6 * p.y,
                  0.6 * p.x + 0.8 * p.y);
}

// Two proper rotations (Y then X), not an axis permutation. Rotating octave domains prevents the
// lattice curvature of one frequency from being reinforced by every finer frequency.
float3 cloudRotate3(float3 p) {
    float x = 0.8 * p.x - 0.6 * p.z;
    float z = 0.6 * p.x + 0.8 * p.z;
    return float3(x, 0.8660254 * p.y - 0.5 * z,
                  0.5 * p.y + 0.8660254 * z);
}

float cloudWeatherAt(float2 worldXZ, float2 wind) {
    // Synoptic cloud groups remain broad, but no single weather cell should blanket the whole 3 km march.
    float2 weatherP = (worldXZ + wind) * 0.00080;
    return 0.72 * cloudValueNoise2(weatherP)
         + 0.28 * cloudValueNoise2(cloudRotate2(weatherP) * 2.03 + float2(17.7, -9.2));
}

// Coverage selects coherent cloud islands before any 3D lattice work. Keep one continuous transition;
// repeatedly hardening this mask made the broad weather contours visible as regular cut-out silhouettes.
float cloudWeatherMask(float weather, float coverage) {
    float cut = lerp(0.82, 0.30, coverage);
    return smoothstep(cut - 0.06, cut + 0.06, weather);
}

float cloudBodyThreshold(float weather, float coverage, float heightErosion) {
    // Coverage primarily controls the number of weather islands. A narrow body response prevents high
    // coverage from filling every selected island into one solid two-dimensional slab.
    return lerp(0.62, 0.40, coverage) + heightErosion
         + (0.5 - weather) * 0.12;
}

// One wide transition preserves resolved density gradients for both visibility and self-shadowing.
float cloudBodyMask(float field, float threshold) {
    return smoothstep(threshold, threshold + CLOUD_BODY_TRANSITION, field);
}

// Keep density multiplicative and continuous. The old nested hardening plus additive core term reached 4.25
// at the default density, giving the cloud a nearly opaque shell only a few blocks thick.
float cloudStructuredDensity(float body, float heightMask, float weatherMask,
                             float coverage, float densityScale) {
    float structure = clamp(body * heightMask * weatherMask, 0.0, 1.0);
    float coreWeight = smoothstep(0.45, 0.92, structure);
    return densityScale * structure * lerp(0.85, 1.60, coreWeight)
         * smoothstep(0.0, 0.08, coverage);
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

// A fourth, world-stable octave is reserved for the visible body transition. It is deliberately not part
// of the broad density FBM: empty space and established cores can reject it from an exact amplitude bound,
// while resolved edges gain smaller turbulent bites without paying eight more hashes at every cloud sample.
float cloudEdgeErosionLod(float detailFootprint) {
    return cloudOctaveLod(detailFootprint * CLOUD_EDGE_EROSION_SCALE);
}

float cloudEdgeErosionStrength(float slabH, float edgeErosionAmount) {
    // Compact droplets retain a calmer base; growing tops receive stronger small-scale turbulent erosion.
    return edgeErosionAmount * lerp(0.035, 0.060, smoothstep(0.08, 0.88, slabH));
}

float cloudEdgeErosionBound(float erosionLod, float erosionStrength) {
    return max(erosionLod, 0.0) * erosionStrength;
}

float cloudBodyWithEdgeErosion(float field, float threshold, float3 detailP,
                               float erosionLod, float erosionStrength) {
    if (field <= threshold) return 0.0;

    float erosionBound = cloudEdgeErosionBound(erosionLod, erosionStrength);
    if (field >= threshold + CLOUD_BODY_TRANSITION + erosionBound) return 1.0;

    float erosion = 0.0;
    if (erosionLod > 0.0) {
        // Rotate and offset the extra octave so it cannot reinforce the lattice shoulders of detail2. Fade
        // amplitude with LOD instead of filtering toward 0.5, which would shrink distant silhouettes.
        float3 erosionP = cloudRotate3(float3(detailP.y, detailP.z, detailP.x))
                        * CLOUD_EDGE_EROSION_SCALE + float3(23.7, -5.9, 11.3);
        float pockets = smoothstep(0.40, 0.72, cloudValueNoise3(erosionP));
        erosion = erosionBound * pockets;
    }
    return cloudBodyMask(field - erosion, threshold);
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
                      float3 sampleDelta, float2 shapeParams,
                      out float heightMask, out float heightErosion) {
    float layerHeight = max(top - bottom, 1.0e-3);
    float h = clamp((worldP.y - bottom) / layerHeight, 0.0, 1.0);
    float2 advected = worldP.xz + wind;
    float clusterAmount = shapeParams.x;
    float bottomVariation = shapeParams.y;

    // Build two differently oriented column signals. They are reused below, but with different mixtures for
    // horizontal presence, cloud base and tower height so those contours do not remain concentric.
    float2 columnP = advected * 0.0028 + float2(31.7, -14.2);
    float columnA = cloudValueNoise2(columnP);
    float columnB = cloudValueNoise2(cloudRotate2(columnP) * 2.07 + float2(-8.1, 19.4));
    float columnNoise = 0.72 * columnA + 0.28 * columnB;
    // Shape controls the separation between adjoining puffs without changing the coverage/weather mask.
    // At zero the layer remains usable but broad; above one, weak bridges retreat into distinct clusters.
    float clusterGain = lerp(0.65, 1.35, 0.5 * clusterAmount);
    float clusteredColumn = 0.5 + (columnNoise - 0.5) * clusterGain;
    float columnPresence = smoothstep(0.40, 0.60, clusteredColumn);

    // Cumulus clouds share a condensation base. Most puffs stay low; squaring the tower seed reserves the
    // full configured thickness for sparse strong peaks, producing isolated cauliflower-like updrafts.
    float lowCumulus = smoothstep(0.40, 0.66, 0.78 * columnA + 0.22 * columnB);
    float towerSeed = 0.48 * columnA + 0.52 * columnB;
    float towerRaw = smoothstep(0.48, 0.74, towerSeed);
    float tower = towerRaw * towerRaw;
    float2 bottomP = cloudRotate2(columnP) * 3.71 + float2(6.4, 27.1);
    float bottomColumn = cloudValueNoise2(bottomP);
    // A separate horizontal field moves each condensation lobe upward by a different amount. Zero leaves
    // a conventional uniform condensation base; one produces a 13%-of-layer range without moving the slab.
    float rawBottom = 0.07 + 0.13 * bottomVariation
                    * smoothstep(0.18, 0.82, bottomColumn);
    float towerHeight = lerp(0.15, 0.65, 0.5 * clusterAmount);
    float rawTop = min(lerp(0.30, 0.50, lowCumulus) + towerHeight * tower, 0.94);
    // Reserve global-slab guard bands for the complete signed support. The limits follow the actual shape
    // value, so the default does not lose tower height merely because the GUI permits a value of two.
    float boundaryWarpBound = 0.16 * clusterAmount;
    float lowerSupport = 0.025 + boundaryWarpBound;
    float upperSupport = 1.04 + boundaryWarpBound;
    float localBottom = max(rawBottom, (lowerSupport / (1.0 + lowerSupport)) * rawTop);
    float localTop = min(rawTop, localBottom + (1.0 - localBottom) / upperSupport);
    float localSpan = max(localTop - localBottom, 0.08);
    float localH = (h - localBottom) / localSpan;
    if (columnPresence <= 0.0 || localH <= -lowerSupport || localH >= upperSupport) {
        heightMask = 0.0;
        heightErosion = 0.0;
        return 0.5;
    }

    // Keep the macro field vertically correlated inside a tower so it erodes one coherent cumulus body
    // instead of stacking horizontal equal-height bands.
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
    float3 billowP = cloudRotate3(macroP) * 2.03 + float3(7.3, verticalWarp - 11.1, 19.4);
    // The domain warp can only increase local frequency, so bias its footprint conservatively upward.
    float billow = cloudFilteredNoise3(billowP, macroFootprint * 2.35);
    float macroBase = 0.67 * base + 0.33 * billow;
    float macroGain = lerp(0.55, 1.45, 0.5 * clusterAmount);
    float macro = 0.5 + (macroBase - 0.5) * macroGain;

    // Let the 3D field move density across both local boundaries. This turns a column height map into joined
    // billows with overhangs while the conservative rejection above still bounds all density work.
    float boundaryWarp = clusterAmount
                       * ((base - 0.5) * 0.22 + (billow - 0.5) * 0.10);
    float warpedLocalH = localH - boundaryWarp;

    // An independent rotated 3D field cuts cavities into the lower lobes. Sampling only the lower region
    // contains its cost; footprint LOD fades the displacement to zero before coarse shadow/DLSS strata can
    // resolve it as bands. Unlike a 2D height map, this field creates pockets and overhangs inside the base.
    float undersideLift = 0.0;
    if (bottomVariation > 0.0 && warpedLocalH < 0.46) {
        float undersideLod = cloudOctaveLod(macroFootprint * 4.37);
        if (undersideLod > 0.0) {
            float3 undersideP = cloudRotate3(float3(macroP.z, macroP.x, macroP.y)) * 4.37
                              + float3(-12.6, 4.3, 21.8);
            float undersideNoise = cloudFilteredNoise3AtLod(undersideP, undersideLod);
            float pockets = smoothstep(0.38, 0.72, undersideNoise) * undersideLod;
            undersideLift = bottomVariation * (0.018 * undersideLod + 0.105 * pockets);
        }
    }
    float undersideEnvelope = 1.0 - smoothstep(-0.08, 0.40, warpedLocalH);
    float undersideH = warpedLocalH - undersideLift * undersideEnvelope;

    // Overlapping local fades keep the warped boundary continuous. Threshold erosion remains weaker at the
    // base than at the top, matching compact lower droplets and more turbulent upper billows.
    heightMask = columnPresence
               * smoothstep(-0.025, 0.045, undersideH)
               * (1.0 - smoothstep(0.94, 1.04, warpedLocalH));
    float baseErosion = 0.018 * (1.0 - smoothstep(0.0, 0.20, undersideH));
    float topErosion = 0.08 * smoothstep(0.58, 1.0, warpedLocalH);
    heightErosion = baseErosion + topErosion;
    return macro;
}

float cloudDensityAt(float3 worldP, float bottom, float top, float coverage,
                     float densityScale, float2 wind, float3 sampleDelta,
                     float4 shapeParams) {
    float weather = cloudWeatherAt(worldP.xz, wind);
    float weatherMask = cloudWeatherMask(weather, coverage);
    if (weatherMask <= 1.0e-3) return 0.0;

    float heightMask;
    float heightErosion;
    float macro = cloudMacroField(worldP, bottom, top, wind, sampleDelta, shapeParams.xy,
                                  heightMask, heightErosion);
    float envelope = heightMask * weatherMask
                   * smoothstep(0.0, 0.08, coverage) * densityScale;
    if (envelope <= 0.0) return 0.0;

    float threshold = cloudBodyThreshold(weather, coverage, heightErosion);
    float detailFootprint = cloudDetailFootprint(sampleDelta);
    float detailLod0 = cloudOctaveLod(detailFootprint);
    float detailLod1 = cloudOctaveLod(detailFootprint * 2.07);
    float detailLod2 = cloudOctaveLod(detailFootprint * 4.31);
    float slabH = clamp((worldP.y - bottom) / max(top - bottom, 1.0e-3), 0.0, 1.0);
    float detailStrength = shapeParams.z
                         * lerp(0.44, 0.60, smoothstep(0.05, 0.85, slabH));
    float erosionLod = cloudEdgeErosionLod(detailFootprint);
    float erosionStrength = cloudEdgeErosionStrength(slabH, shapeParams.w);
    float erosionBound = cloudEdgeErosionBound(erosionLod, erosionStrength);
    // A filtered octave is lerp(0.5, noise, lod), so its exact deviation from expectation is bounded by
    // 0.5*lod. Use the three actual LODs and the height-dependent erosion strength for exact early-outs.
    float detailBound = 0.5 * detailStrength
                      * (0.58 * detailLod0 + 0.29 * detailLod1 + 0.13 * detailLod2)
                      + 1.0e-6;
    if (macro + detailBound <= threshold) return 0.0;
    if (macro - detailBound >= threshold + CLOUD_BODY_TRANSITION + erosionBound) {
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
    float remainingBound = 0.5 * detailStrength
                         * (0.29 * detailLod1 + 0.13 * detailLod2) + 1.0e-6;
    float partialField = macro + partialDeviation * detailStrength;
    if (partialField + remainingBound <= threshold) return 0.0;
    if (partialField - remainingBound >= threshold + CLOUD_BODY_TRANSITION + erosionBound) {
        return cloudStructuredDensity(1.0, heightMask, weatherMask, coverage, densityScale);
    }

    float detail1 = cloudFilteredNoise3AtLod(
            cloudRotate3(detailP) * 2.07 + float3(7.3, -11.1, 19.4), detailLod1);
    partialDeviation += 0.29 * (detail1 - 0.5);
    remainingBound = 0.5 * detailStrength * (0.13 * detailLod2) + 1.0e-6;
    partialField = macro + partialDeviation * detailStrength;
    if (partialField + remainingBound <= threshold) return 0.0;
    if (partialField - remainingBound >= threshold + CLOUD_BODY_TRANSITION + erosionBound) {
        return cloudStructuredDensity(1.0, heightMask, weatherMask, coverage, densityScale);
    }

    float detail2 = cloudFilteredNoise3AtLod(
            cloudRotate3(float3(detailP.z, detailP.x, detailP.y)) * 4.31
                    + float3(-15.2, 8.4, -3.7), detailLod2);
    float detail = 0.58 * detail0 + 0.29 * detail1 + 0.13 * detail2;
    float field = macro + (detail - 0.5) * detailStrength;
    float body = cloudBodyWithEdgeErosion(field, threshold, detailP,
                                          erosionLod, erosionStrength);
    return cloudStructuredDensity(body, heightMask, weatherMask, coverage, densityScale);
}

// Cheaper density used only by the light probes: retain the exact same 3D macro body, warped height
// envelope and first detail octave while omitting the two finer detail octaves.
float cloudShadowDensityAt(float3 worldP, float bottom, float top, float coverage,
                           float densityScale, float2 wind, float3 sampleDelta,
                           float4 shapeParams) {
    float weather = cloudWeatherAt(worldP.xz, wind);
    float weatherMask = cloudWeatherMask(weather, coverage);
    if (weatherMask <= 1.0e-3) return 0.0;

    float heightMask;
    float heightErosion;
    float macro = cloudMacroField(worldP, bottom, top, wind, sampleDelta, shapeParams.xy,
                                  heightMask, heightErosion);
    float envelope = heightMask * weatherMask
                   * smoothstep(0.0, 0.08, coverage) * densityScale;
    if (envelope <= 0.0) return 0.0;

    float threshold = cloudBodyThreshold(weather, coverage, heightErosion);
    float detailFootprint = cloudDetailFootprint(sampleDelta);
    float detailLod = cloudOctaveLod(detailFootprint);
    float slabH = clamp((worldP.y - bottom) / max(top - bottom, 1.0e-3), 0.0, 1.0);
    float detailStrength = shapeParams.z
                         * lerp(0.44, 0.60, smoothstep(0.05, 0.85, slabH));
    float erosionLod = cloudEdgeErosionLod(detailFootprint);
    float erosionStrength = cloudEdgeErosionStrength(slabH, shapeParams.w);
    float erosionBound = cloudEdgeErosionBound(erosionLod, erosionStrength);
    const float coarseDetailWeight = 0.58;
    float detailBound = 0.5 * detailStrength * coarseDetailWeight * detailLod + 1.0e-6;
    if (macro + detailBound <= threshold) return 0.0;
    if (macro - detailBound >= threshold + CLOUD_BODY_TRANSITION + erosionBound) {
        return cloudStructuredDensity(1.0, heightMask, weatherMask, coverage, densityScale);
    }

    float2 detailXZ = (worldP.xz + wind) * CLOUD_DETAIL_XZ_SCALE;
    float detailY = (worldP.y - bottom) * CLOUD_DETAIL_Y_SCALE
                  + dot(detailXZ, float2(-0.07, 0.11)) + (macro - 0.5) * 0.65;
    float detail = cloudFilteredNoise3AtLod(
            float3(detailXZ.x, detailY, detailXZ.y), detailLod);
    float3 detailP = float3(detailXZ.x, detailY, detailXZ.y);
    float field = macro + (detail - 0.5) * detailStrength * coarseDetailWeight;
    float body = cloudBodyWithEdgeErosion(field, threshold, detailP,
                                          erosionLod, erosionStrength);
    return cloudStructuredDensity(body, heightMask, weatherMask, coverage, densityScale);
}

float cloudLightOpticalDepth(float3 worldP, float3 lightDir, float bottom, float top,
                             float coverage, float densityScale, float2 wind,
                             float4 shapeParams) {
    float boundary = lightDir.y >= 0.0 ? top : bottom;
    float lightDistance;
    if (abs(lightDir.y) > 1.0e-4) {
        lightDistance = max(0.0, (boundary - worldP.y) / lightDir.y);
    } else {
        lightDistance = 768.0;
    }
    lightDistance = min(lightDistance, 768.0);
    float tau = 0.0;
    for (int i = 0; i < CLOUD_SHADOW_STEPS; i++) {
        // Quadratic segment boundaries place short probes near the shaded point, where the visible local
        // billow matters most, and spend progressively wider probes on the low-frequency distant body.
        float u0 = float(i) / float(CLOUD_SHADOW_STEPS);
        float u1 = float(i + 1) / float(CLOUD_SHADOW_STEPS);
        float segmentA = lightDistance * u0 * u0;
        float segmentB = lightDistance * u1 * u1;
        float segmentLength = segmentB - segmentA;
        float t = 0.5 * (segmentA + segmentB);
        // Long sun paths still integrate their full segment length. Preserve local detail in the short near
        // segments, then widen the density footprint toward 128 blocks so one far midpoint cannot represent
        // a 200-block segment with unfiltered high-frequency density. The farthest detail lookup then reaches
        // its exact mean and skips eight lattice hashes.
        float footprintLimit = lerp(48.0, 128.0, u0);
        float footprintLength = min(segmentLength, footprintLimit);
        float density = cloudShadowDensityAt(worldP + lightDir * t, bottom, top,
                                             coverage, densityScale, wind,
                                             lightDir * footprintLength, shapeParams);
        tau += max(density, 0.0) * segmentLength * CLOUD_EXTINCTION;
        // Slow multiple-scattering and sky-visibility exponentials still vary well beyond tau=20. Clamp only
        // once their residual contribution is negligible; a lower ceiling re-quantizes every thick bottom.
        if (tau >= 80.0) return 80.0;
    }
    return min(tau, 80.0);
}

// Density-only counterpart for DLSS-RR motion/depth guides. It deliberately mirrors marchClouds' fixed
// strata, near-camera subdivision, tiny-density gate and T=0.9 crossing calculation, but omits all cloud
// lighting and ordered radiance-layer moments. Once committed transmittance falls below 0.75, the final
// opacity is irreversibly above CLOUD_GUIDE_MIN_OPACITY and the already-recorded crossing can be returned.
public float marchCloudGuideDistance(float3 rayOrigin, float3 rayDir,
                                     float maxDistance, WorldPush pc) {
    float bottom, top, coverage, densityScale;
    float4 shapeParams;
    resolveCloudRenderParams(pc, bottom, top, coverage, densityScale, shapeParams);
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

    float2 wind = cloudWind(pc);
    bool rotatesJitterPerStratum;
    float jitterBase = cloudJitterBase(pc, rotatesJitterPerStratum);

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
            float sampleT = lerp(subA, subB,
                                 cloudStratumJitter(ditherIndex, jitterBase, rotatesJitterPerStratum));
            float3 densitySampleP = rayOrigin + rayDir * sampleT;
            densitySampleP.xz += pc.waterAnchor.zw;
            float density = cloudDensityAt(densitySampleP, bottom, top, coverage, densityScale,
                                           wind, rayDir * subLength, shapeParams);
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

// requestedMaxSteps controls density resolution independently of shadowStride. Primary visibility uses
// the full budget; progressively filtered secondary paths use smaller budgets. A spatial cap below still
// refreshes long/wide shadow strata, and every newly detected cloud run starts with a fresh probe so
// visibility never leaks across a clear interval.
public CloudResult marchClouds(float3 rayOrigin, float3 rayDir, float maxDistance, WorldPush pc,
                               bool trackGuide, int requestedMaxSteps, int shadowStride) {
    CloudResult result;
    result.distance = -1.0;
    result.layerCount = 0u;

    float bottom, top, coverage, densityScale;
    float4 shapeParams;
    resolveCloudRenderParams(pc, bottom, top, coverage, densityScale, shapeParams);
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

    // The layer array is unreachable when layerCount is zero, so initialise it only after the cheap slab
    // rejection. Most terrain-facing and short finite segments avoid these stores entirely.
    for (int i = 0; i < CLOUD_LAYER_MAX; i++) {
        result.layers[i].radiance = float3(0.0, 0.0, 0.0);
        result.layers[i].transmittance = 1.0;
        result.layers[i].distance = 0.0; // contribution moment until final normalisation
    }

    float2 wind = cloudWind(pc);
    bool rotatesJitterPerStratum;
    float jitterBase = cloudJitterBase(pc, rotatesJitterPerStratum);

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
    float2 weather = weatherAmounts(pc);
    float3 rainAmbient = lerp(float3(0.007, 0.012, 0.024),
                              float3(0.105, 0.125, 0.150), day);
    float3 thunderAmbient = lerp(float3(0.004, 0.008, 0.018),
                                 float3(0.042, 0.055, 0.078), day);
    float clearWeatherWeight = 1.0 - weather.x;
    float rainWeatherWeight = max(weather.x - weather.y, 0.0);
    ambient = ambient * clearWeatherWeight
            + rainAmbient * rainWeatherWeight
            + thunderAmbient * weather.y;
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
    float cachedShadowTau = 0.0;
    float cachedShadowDistance = -1.0;
    int occupiedStrataSinceShadow = resolvedShadowStride;

    float interval = t1 - t0;
    float targetStep = min(32.0, 2.0 / max(abs(rayDir.y), 1.0e-4));
    int stepCount = max(1, int(ceil(interval / targetStep)));
    int resolvedMaxSteps = min(CLOUD_PRIMARY_MAX_STEPS, max(requestedMaxSteps, 1));
    // When the requested budget cannot retain the two-block vertical target, widen all fixed strata once
    // rather than dropping the far portion of the cloud interval.
    if (stepCount > resolvedMaxSteps) {
        targetStep = interval / float(resolvedMaxSteps);
        stepCount = resolvedMaxSteps;
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
            float sampleT = lerp(subA, subB,
                                 cloudStratumJitter(ditherIndex, jitterBase, rotatesJitterPerStratum));
            float3 densitySampleP = rayOrigin + rayDir * sampleT;
            // Clouds use the full float XZ origin and must not inherit the wrapped water domain's visible
            // discontinuity whenever the terrain origin crosses a 4096-block boundary.
            densitySampleP.xz += pc.waterAnchor.zw;
            float density = cloudDensityAt(densitySampleP, bottom, top, coverage, densityScale,
                                           wind, rayDir * subLength, shapeParams);
            density *= 1.0 - smoothstep(0.75 * CLOUD_MAX_DISTANCE, CLOUD_MAX_DISTANCE, sampleT);

            float weightedDensity = max(density, 0.0) * subLength;
            float sampleOpticalDepth = min(weightedDensity * CLOUD_EXTINCTION, 20.0);
            if (trackGuide && guideCandidate < 0.0 && sampleOpticalDepth > 0.0) {
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
                // Adjacent occupied strata see a slowly varying five-segment light integral. Reuse it for
                // at most shadowStride strata and 32 world blocks; the latter keeps vertical/grazing paths
                // bounded even when their integration strata have very different lengths.
                bool refreshShadow = startedCloudRun || !shadowCacheValid
                                  || occupiedStrataSinceShadow >= resolvedShadowStride
                                  || abs(lightingDistance - cachedShadowDistance) > 32.0;
                if (refreshShadow) {
                    cachedShadowTau = cloudLightOpticalDepth(sampleP, lightDir, bottom, top,
                                                             coverage, densityScale, wind, shapeParams);
                    cachedShadowDistance = lightingDistance;
                    occupiedStrataSinceShadow = 1;
                    shadowCacheValid = true;
                } else {
                    occupiedStrataSinceShadow++;
                }
                float tau = cachedShadowTau;
                float directT = exp(-tau);
                float wideT = exp(-0.22 * tau);
                float isotropicT = exp(-0.055 * tau);
                float widePhase = lerp(0.020, 0.036, smoothstep(-0.35, 0.85, mu));
                float sunSource = directScale * directT
                                + 0.30 * widePhase * wideT
                                + 0.08 * 0.018 * isotropicT;

                // Broad sky visibility varies much more slowly than direct sunlight, but cannot remain a
                // constant inside every opaque column. Reusing low-frequency tau gives continuous cool fill
                // without an extra shadow march and leaves dense bottoms readable rather than pitch black.
                float skyTauScale = lerp(0.035, 0.065, clamp(lightDir.y, 0.0, 1.0));
                float ambientVisibility = 0.28 + 0.72 * exp(-skyTauScale * tau);
                lighting = ambient * ambientVisibility + pc.lightRadiance.xyz * sunSource;
            }
            // Retain a modest vertical lift; most variation now comes from continuous optical depth rather
            // than a height ramp, so the cloud base does not collapse to one analytic grey value.
            float height = clamp((sampleP.y - bottom) / max(top - bottom, 1.0e-3), 0.0, 1.0);
            lighting *= lerp(0.88, 1.02, height);
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
    if (trackGuide && (1.0 - transmittance) > CLOUD_GUIDE_MIN_OPACITY && guideCandidate >= 0.0) {
        result.distance = guideCandidate;
    }
    return result;
}
