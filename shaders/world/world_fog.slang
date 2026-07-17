// Shared analytic height fog and bounded world-stable volumetric density integration.
module world_fog;

import world_common;

static const int   FOG_VOLUME_MAX_STEPS = 16;
static const float FOG_VOLUME_MAX_DISTANCE = 1536.0;
static const float FOG_MAX_OPTICAL_DEPTH = 80.0;

// Vertical shaping is reciprocal because it depends only on the absolute ray slope. Horizontal visibility
// remains controlled by the configured density; steep sightlines gain the requested additional depth cue.
float fogDirectionalScale(float rayY, WorldPush pc) {
    float verticalScale = max(pc.fogShapeParams.x, 1.0);
    float verticalWeight = smoothstep(0.15, 0.8, abs(rayY));
    return lerp(1.0, verticalScale, verticalWeight);
}

// Overflow-safe analytic integrals of the constant ambient floor (x) and exponential envelope (y) for
// density(y) = density0 * (floorRatio + (1 - floorRatio) * exp(-falloff * (y - baseHeight))).
// Keeping the components separate prevents cancellation high above the base and lets the volume march
// modulate only the structured exponential component, never punching holes through the ambient medium.
float2 analyticFogOpticalDepthComponentsScaled(float originY, float rayY, float distance,
                                               float directionScale, WorldPush pc) {
    float density0 = pc.fogParams.x;
    if (density0 <= 0.0 || distance <= 0.0) return float2(0.0, 0.0);

    directionScale = max(directionScale, 0.0);
    float floorRatio = clamp(pc.fogAmbientParams.x, 0.0, 1.0);
    // The homogeneous floor is already isotropic, so only the exponential layer needs the artistic
    // vertical compensation. Applying it here would make long upward sky rays opaque at the default 8x.
    float floorDepth = min(density0 * floorRatio * distance, FOG_MAX_OPTICAL_DEPTH);
    float exponentialDensity = density0 * (1.0 - floorRatio);
    if (exponentialDensity <= 0.0) return float2(floorDepth, 0.0);

    float falloff = max(pc.fogParams.y, 0.0);
    if (falloff <= 1.0e-7) {
        float exponentialDepth = min(exponentialDensity * distance * directionScale,
                                     FOG_MAX_OPTICAL_DEPTH);
        return float2(floorDepth, exponentialDepth);
    }

    float startExponent = -falloff * (originY - pc.fogParams.z);
    float exponentDelta = -falloff * rayY * distance;
    float span = abs(exponentDelta);
    float averageScale;
    if (span < 1.0e-3) {
        // (1 - exp(-span)) / span, expanded to avoid cancellation for near-horizontal rays.
        averageScale = 1.0 - 0.5 * span + (span * span) / 6.0;
    } else {
        averageScale = (1.0 - exp(-min(span, FOG_MAX_OPTICAL_DEPTH))) / span;
    }

    float maxExponent = startExponent + max(exponentDelta, 0.0);
    float logDepth = log(exponentialDensity) + log(distance) + maxExponent
            + log(averageScale) + log(directionScale);
    float exponentialDepth = 0.0;
    if (logDepth >= log(FOG_MAX_OPTICAL_DEPTH)) {
        exponentialDepth = FOG_MAX_OPTICAL_DEPTH;
    } else if (logDepth > -FOG_MAX_OPTICAL_DEPTH) {
        exponentialDepth = exp(logDepth);
    }
    return float2(floorDepth, exponentialDepth);
}

float2 analyticFogOpticalDepthComponents(float originY, float rayY, float distance, WorldPush pc) {
    return analyticFogOpticalDepthComponentsScaled(
            originY, rayY, distance, fogDirectionalScale(rayY, pc), pc);
}

float fogAmbientLightCoupling(WorldPush pc) {
    return clamp(pc.fogAmbientParams.y, 0.0, 1.0);
}

// Export the smooth full view-extinction total for callers that do not need separate density components.
public float analyticFogOpticalDepth(float originY, float rayY, float distance, WorldPush pc) {
    float2 components = analyticFogOpticalDepthComponents(originY, rayY, distance, pc);
    return min(components.x + components.y, FOG_MAX_OPTICAL_DEPTH);
}

// Smooth source-density integral used to place directional-scattering samples along the camera ray.
// Unlike view extinction, the homogeneous ambient floor contributes only by its configured coupling.
public float analyticFogDirectionalSourceOpticalDepth(float originY, float rayY, float distance,
                                                      WorldPush pc) {
    float2 components = analyticFogOpticalDepthComponents(originY, rayY, distance, pc);
    return min(components.y + components.x * fogAmbientLightCoupling(pc), FOG_MAX_OPTICAL_DEPTH);
}

// Light transport must not inherit the camera-only vertical optical-depth control. The exponential layer
// supplies the physical sun/moon extinction, while only the configured fraction of the ambient view floor
// participates so a long homogeneous light path cannot erase local directional scattering.
public float analyticFogLightOpticalDepth(float originY, float rayY, float distance, WorldPush pc) {
    float2 components = analyticFogOpticalDepthComponentsScaled(originY, rayY, distance, 1.0, pc);
    return min(components.y + components.x * fogAmbientLightCoupling(pc), FOG_MAX_OPTICAL_DEPTH);
}

// Dave Hoskins' compact 3D hash, namespaced to keep imports collision-free.
float fogHash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

float fogValueNoise3(float3 p) {
    float3 i = floor(p);
    float3 f = frac(p);
    float3 u = f * f * (3.0 - 2.0 * f);
    float n000 = fogHash13(i + float3(0.0, 0.0, 0.0) + 71.0);
    float n100 = fogHash13(i + float3(1.0, 0.0, 0.0) + 71.0);
    float n010 = fogHash13(i + float3(0.0, 1.0, 0.0) + 71.0);
    float n110 = fogHash13(i + float3(1.0, 1.0, 0.0) + 71.0);
    float n001 = fogHash13(i + float3(0.0, 0.0, 1.0) + 71.0);
    float n101 = fogHash13(i + float3(1.0, 0.0, 1.0) + 71.0);
    float n011 = fogHash13(i + float3(0.0, 1.0, 1.0) + 71.0);
    float n111 = fogHash13(i + float3(1.0, 1.0, 1.0) + 71.0);
    float x00 = lerp(n000, n100, u.x);
    float x10 = lerp(n010, n110, u.x);
    float x01 = lerp(n001, n101, u.x);
    float x11 = lerp(n011, n111, u.x);
    return lerp(lerp(x00, x10, u.y), lerp(x01, x11, u.y), u.z);
}

// Filtering an undersampled octave toward its exact expectation retains mean density and prevents distant
// cells from becoming view-dependent bands. The high octave fades first; the low octave survives until a
// step spans roughly one full cell, so the finite march still carries broad spatial structure.
float fogFilteredNoise(float3 p, float footprintCells, float filterStart, float filterEnd) {
    float lod = 1.0 - smoothstep(filterStart, filterEnd, max(footprintCells, 0.0));
    if (lod <= 0.0) return 0.5;
    return lerp(0.5, fogValueNoise3(p), lod);
}

float fogEffectiveNoiseStrength(WorldPush pc) {
    float configured = clamp(pc.fogShapeParams.y, 0.0, 1.0);
    float openness = clamp(pc.fogShapeParams.w, 0.0, 1.0);
    // Enclosed caves retain subtle structure, but broad outdoor pockets must not reintroduce locally dense
    // black regions after the CPU-side cave density cap has reduced the medium's mean extinction.
    return configured * lerp(0.35, 1.0, openness);
}

float fogDensityModulation(float3 rebasedP, float3 sampleDelta, WorldPush pc) {
    float strength = fogEffectiveNoiseStrength(pc);
    if (strength <= 0.0) return 1.0;

    // z is world-space frequency in cells per block. XZ is restored to a full terrain world anchor; Y is
    // relative to fog base height, so terrain rebases cannot move the density field in any dimension.
    float frequency = max(pc.fogShapeParams.z, 1.0e-6);
    float3 worldP = rebasedP;
    worldP.xz += pc.waterAnchor.zw;
    float3 noiseP = float3(worldP.x, rebasedP.y - pc.fogParams.z, worldP.z) * frequency;
    float baseFootprint = length(sampleDelta) * frequency;

    float low = fogFilteredNoise(noiseP, baseFootprint, 0.75, 1.50);
    const float highFrequency = 2.07;
    float high = fogFilteredNoise(noiseP * highFrequency + float3(7.3, -11.1, 19.4),
                                  baseFootprint * highFrequency, 0.75, 1.50);
    float meanHalfFbm = 0.72 * low + 0.28 * high;
    // Symmetric contrast shaping makes the default strength visibly cluster without biasing the expected
    // density: x and 1-x remain paired, while filtered octaves at exactly 0.5 still map to modulation 1.
    float shapedNoise = smoothstep(0.28, 0.72, meanHalfFbm);
    return 1.0 + strength * (2.0 * shapedNoise - 1.0);
}

// Integrate the ambient floor (x) and structured height envelope (y) separately. Only y is modulated by
// the 3D field; keeping x intact lets view extinction retain the ambient medium while directional light
// couples to only a small fraction of it. The untouched tail remains the exact analytic expectation.
public float2 volumeFogOpticalDepthComponents(float3 rayOrigin, float3 rayDir, float distance,
                                              int requestedMaxSteps, WorldPush pc) {
    float traceDistance = max(distance, 0.0);
    if (pc.fogParams.x <= 0.0 || traceDistance <= 0.0) return float2(0.0, 0.0);

    float strength = fogEffectiveNoiseStrength(pc);
    float frequency = max(pc.fogShapeParams.z, 0.0);
    if (strength <= 0.0 || frequency <= 1.0e-7 || requestedMaxSteps <= 0) {
        return analyticFogOpticalDepthComponents(rayOrigin.y, rayDir.y, traceDistance, pc);
    }

    int stepBudget = min(FOG_VOLUME_MAX_STEPS, max(1, requestedMaxSteps));
    // Never march farther than the budget can resolve at 0.5 low-frequency cells per sample. Beyond this
    // fixed view-relative radius the analytic expectation is both cheaper and less prone to under-sampling.
    float targetStep = 0.5 / frequency;
    float resolvedDistance = float(stepBudget) * targetStep;
    float volumeLimit = min(FOG_VOLUME_MAX_DISTANCE, resolvedDistance);
    float nearDistance = min(traceDistance, volumeLimit);
    int stepCount = min(stepBudget, max(1, int(ceil(nearDistance / targetStep))));
    float2 opticalDepth = float2(0.0, 0.0);
    for (int i = 0; i < FOG_VOLUME_MAX_STEPS; i++) {
        if (i >= stepCount) break;
        // Fixed-width strata keep all earlier sample positions unchanged when the ray grows across a step
        // boundary. Only the final partial stratum grows from zero, avoiding distance-dependent density pops.
        float t0 = float(i) * targetStep;
        float t1 = min(t0 + targetStep, nearDistance);
        float stepLength = t1 - t0;
        float3 sampleDelta = rayDir * stepLength;
        float3 stratumOrigin = rayOrigin + rayDir * t0;
        float2 stratumTau = analyticFogOpticalDepthComponents(
                stratumOrigin.y, rayDir.y, t1 - t0, pc);
        float sampleDistance = 0.5 * (t0 + t1);
        float3 sampleP = rayOrigin + rayDir * sampleDistance;
        float modulation = fogDensityModulation(sampleP, sampleDelta, pc);
        // Fade toward the exact mean before the fixed analytic-tail boundary. Basing the fade on volumeLimit
        // rather than the current hit distance keeps the transition continuous as geometry crosses it.
        float noiseWeight = 1.0 - smoothstep(0.75 * volumeLimit, volumeLimit, sampleDistance);
        modulation = lerp(1.0, modulation, noiseWeight);
        opticalDepth += float2(stratumTau.x, stratumTau.y * modulation);
    }

    if (traceDistance > nearDistance) {
        float3 farOrigin = rayOrigin + rayDir * nearDistance;
        opticalDepth += analyticFogOpticalDepthComponents(
                farOrigin.y, rayDir.y, traceDistance - nearDistance, pc);
    }
    return min(max(opticalDepth, float2(0.0, 0.0)),
               float2(FOG_MAX_OPTICAL_DEPTH, FOG_MAX_OPTICAL_DEPTH));
}

// Compatibility wrapper for callers that need only total view extinction.
public float volumeFogOpticalDepth(float3 rayOrigin, float3 rayDir, float distance,
                                   int requestedMaxSteps, WorldPush pc) {
    float2 components = volumeFogOpticalDepthComponents(
            rayOrigin, rayDir, distance, requestedMaxSteps, pc);
    return min(components.x + components.y, FOG_MAX_OPTICAL_DEPTH);
}
