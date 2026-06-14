#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// P1 step 4a: real model geometry. Per-primitive record (geometric normal + biome tint) is
// fetched from a buffer-reference array indexed by gl_PrimitiveID, then shaded with a fixed sun
// direction + ambient. Albedo is the biome tint (white where untinted) — block-atlas textures
// (per-vertex UV sampling) come in step 4b.
struct Prim {
    vec4 normal;
    vec4 tint;
};

layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Prims {
    Prim p[];
};

layout(push_constant) uniform Push {
    mat4 invViewProj;
    vec3 camOffset;
    uint64_t primAddr;
} pc;

layout(location = 0) rayPayloadInEXT vec3 payload;
hitAttributeEXT vec2 attribs;

void main() {
    Prim pr = Prims(pc.primAddr).p[gl_PrimitiveID];
    vec3 n = normalize(pr.normal.xyz);
    vec3 tint = pr.tint.rgb;

    const vec3 sunDir = normalize(vec3(0.35, 0.9, 0.25));
    float ndl = max(0.0, dot(n, sunDir));
    float ambient = 0.35;
    payload = tint * 0.85 * (ambient + 0.75 * ndl);
}
