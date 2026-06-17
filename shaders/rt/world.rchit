#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#extension GL_EXT_nonuniform_qualifier : require

// P2 closest-hit. Geometry is per-section: gl_InstanceCustomIndexEXT indexes a section table (BDA
// array reached from the push constant) holding this section's {prim, index, uv} buffer addresses.
// From there it's the same as before: per-primitive {normal, tint} (gl_PrimitiveID) + per-vertex
// atlas UVs (index buffer -> UV buffer, barycentric-interpolated) -> atlas albedo. Lighting is done
// in raygen (deferred), so this writes albedo/normal/hitT into the payload and does no shading.
struct Prim {
    vec4 normal;
    vec4 tint;
};
struct Section {
    uint64_t primAddr;
    uint64_t idxAddr;
    uint64_t uvAddr;
};
// P5.1b-2 entity geometry record: the entity's {prim, index, uv} buffer addresses (same per-prim
// {normal, tint} + UV layout as a terrain section) plus its per-object world-space displacement since
// the previous frame (xyz; w padding) for the motion vector. std430 packs disp at offset 32 (48-byte
// stride; matches RtEntities' table writes).
struct EntityGeom {
    uint64_t primAddr;
    uint64_t idxAddr;
    uint64_t uvAddr;
    vec4 disp;
};

layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Prims { Prim p[]; };
layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer Indices { uint i[]; };
layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer UVs { vec2 uv[]; };
layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer SectionTable { Section s[]; };
// P5.1b-2: per-entity geometry records, indexed by the entity instance index (the low bits of
// gl_InstanceCustomIndexEXT). See RtEntities.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer EntityTable { EntityGeom e[]; };

layout(binding = 2, set = 0) uniform sampler2D blockAtlas;
// P5.1b-2b: bindless entity textures — a runtime-sized array indexed per-prim (tint.w) by the entity
// hit path. Slot 0 is a fallback. Entities use per-type texture files, so each RenderType gets a slot.
layout(binding = 0, set = 1) uniform sampler2D entityTex[];

layout(push_constant) uniform Push {
    mat4 invViewProj;
    vec3 camOffset;
    uint64_t tableAddr;
    layout(offset = 184) uint64_t entityTableAddr;
} pc;

struct Payload {
    vec3 albedo;
    vec3 normal;
    float hitT;
    float emission; // block light level 0..1 (stashed in prim normal.w during extraction)
    vec3 motionPrev; // world displacement since last frame (entity per-object MV); 0 for static terrain
};
layout(location = 0) rayPayloadInEXT Payload payload;
hitAttributeEXT vec2 attribs;

// P5.1b-2 dynamic entities: instances with this custom-index flag bit carry real captured ModelPart
// geometry. Their gl_InstanceCustomIndexEXT (low bits) indexes the entity geometry table, not the
// section table; shading reads per-prim normal + vertex-colour tint (no atlas — entity textures are
// P5.1b-2b) and the per-object MV displacement.
const int ENTITY_BIT = 0x800000;

void main() {
    if ((gl_InstanceCustomIndexEXT & ENTITY_BIT) != 0) {
        int eidx = gl_InstanceCustomIndexEXT & ~ENTITY_BIT;
        EntityGeom g = EntityTable(pc.entityTableAddr).e[eidx];
        Prim pr = Prims(g.primAddr).p[gl_PrimitiveID];
        vec3 n = normalize(pr.normal.xyz);
        if (dot(n, gl_WorldRayDirectionEXT) > 0.0) {
            n = -n; // orient toward the viewer, like the terrain path below
        }
        // Interpolate the captured entity-texture UV (same scheme as terrain) and sample the bindless
        // per-type texture; tint.rgb is the model colour multiplier (white for most, tinted for sheep/etc.).
        Indices eidxBuf = Indices(g.idxAddr);
        UVs euv = UVs(g.uvAddr);
        uint e0 = eidxBuf.i[3u * gl_PrimitiveID + 0u];
        uint e1 = eidxBuf.i[3u * gl_PrimitiveID + 1u];
        uint e2 = eidxBuf.i[3u * gl_PrimitiveID + 2u];
        vec3 ebary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        vec2 euvCoord = ebary.x * euv.uv[e0] + ebary.y * euv.uv[e1] + ebary.z * euv.uv[e2];
        int texSlot = int(pr.tint.w + 0.5);
        payload.albedo = texture(entityTex[nonuniformEXT(texSlot)], euvCoord).rgb * pr.tint.rgb;
        payload.normal = n;
        payload.hitT = gl_HitTEXT;
        payload.emission = 0.0;
        payload.motionPrev = g.disp.xyz; // per-object MV (P5.1c)
        return;
    }

    Section sec = SectionTable(pc.tableAddr).s[gl_InstanceCustomIndexEXT];
    uint pid = gl_PrimitiveID;
    Prim pr = Prims(sec.primAddr).p[pid];
    vec3 n = normalize(pr.normal.xyz);
    vec3 tint = pr.tint.rgb;

    Indices indices = Indices(sec.idxAddr);
    UVs uvs = UVs(sec.uvAddr);
    uint i0 = indices.i[3u * pid + 0u];
    uint i1 = indices.i[3u * pid + 1u];
    uint i2 = indices.i[3u * pid + 2u];
    vec3 bary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    vec2 uv = bary.x * uvs.uv[i0] + bary.y * uvs.uv[i1] + bary.z * uvs.uv[i2];

    // Orient the normal toward the viewer so the AO/shadow offset and N·L stay correct even if a
    // back-face is hit.
    if (dot(n, gl_WorldRayDirectionEXT) > 0.0) {
        n = -n;
    }

    payload.albedo = textureLod(blockAtlas, uv, 0.0).rgb * tint;
    payload.normal = n;
    payload.hitT = gl_HitTEXT;
    payload.emission = pr.normal.w; // 0..1 light level, written by extraction into the free slot
    payload.motionPrev = vec3(0.0); // static terrain: camera-only motion vector
}
