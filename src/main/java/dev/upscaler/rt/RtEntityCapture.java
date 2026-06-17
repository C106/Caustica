package dev.upscaler.rt;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * P5.1b-2 capture infrastructure: a {@link VertexConsumer} that records the posed entity geometry
 * vanilla emits — exactly the same bulk {@code addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz)} that
 * {@code ModelPart.Cube.compile} calls (4 verts/quad → 2 triangles), so this mirrors the fluid-capture
 * pattern. {@link RtEntityCollector} drives it by calling {@code model.renderToBuffer(pose, this, …)}.
 *
 * <p>The accumulators use the SAME layout as terrain's {@code SectionMesh} (positions, indices, atlas
 * UV, per-prim {@code {normal.xyz, emission}, {tint.rgb, 0}}) so the GPU-side rework (P5.1b-2 step 2)
 * can upload + BLAS them with the existing terrain machinery. Until entity textures land (P5.1b-2b) the
 * tint carries the model's vertex colour (white → grey-lit) and UVs are kept for later. The {@code
 * minX..maxZ} bounds + counts are for the step-1 verification probe (no GPU geometry yet).
 */
public final class RtEntityCapture implements VertexConsumer {
    final FloatArrayList verts = new FloatArrayList();   // 3 floats/vertex (capture-space position)
    final IntArrayList idx = new IntArrayList();         // 3 indices/triangle
    final FloatArrayList uvList = new FloatArrayList();  // 2 floats/vertex (entity-texture UV, for P5.1b-2b)
    final FloatArrayList prim = new FloatArrayList();    // 8 floats/triangle: normal.xyz + 0, tint.rgb + 0

    // Probe stats (step 1): captured bounds + a flag, reset per entity.
    float minX, minY, minZ, maxX, maxY, maxZ;
    boolean any;
    // P5.1b-2b: the bindless texture slot for the geometry currently being submitted (set by the
    // collector per submitModel, so body + feature layers get their own texture). Stored per-prim in
    // tint.w; the hit shader samples entityTex[texSlot].
    int currentTexSlot;

    private int n; // quad vertex accumulator (0..3)
    private final float[] qx = new float[4], qy = new float[4], qz = new float[4];
    private final float[] qu = new float[4], qv = new float[4];
    private final float[] qnx = new float[4], qny = new float[4], qnz = new float[4];
    private final int[] qcol = new int[4];

    /** Clear all accumulators + probe stats for a fresh entity capture. */
    public void reset() {
        verts.clear();
        idx.clear();
        uvList.clear();
        prim.clear();
        n = 0;
        any = false;
        currentTexSlot = 0;
        minX = minY = minZ = Float.MAX_VALUE;
        maxX = maxY = maxZ = -Float.MAX_VALUE;
    }

    public boolean isEmpty() {
        return idx.isEmpty();
    }

    public int vertexCount() {
        return verts.size() / 3;
    }

    public int triangleCount() {
        return idx.size() / 3;
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v,
                          int overlay, int light, float nx, float ny, float nz) {
        qx[n] = x; qy[n] = y; qz[n] = z;
        qu[n] = u; qv[n] = v;
        qnx[n] = nx; qny[n] = ny; qnz[n] = nz;
        qcol[n] = color;
        if (++n == 4) {
            emitQuad();
            n = 0;
        }
    }

    private void emitQuad() {
        int base = verts.size() / 3;
        for (int i = 0; i < 4; i++) {
            verts.add(qx[i]);
            verts.add(qy[i]);
            verts.add(qz[i]);
            uvList.add(qu[i]);
            uvList.add(qv[i]);
            track(qx[i], qy[i], qz[i]);
        }
        idx.add(base);
        idx.add(base + 1);
        idx.add(base + 2);
        idx.add(base);
        idx.add(base + 2);
        idx.add(base + 3);

        // Authored model normal (pose-transformed by compile); planar quad, so vertex 0's normal is the
        // face normal. Normalized defensively. The closest-hit flips it toward the viewer, as for terrain.
        float nx = qnx[0], ny = qny[0], nz = qnz[0];
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        // Vertex colour as a flat per-prim tint (ARGB → rgb). White (-1) for most models → grey when lit;
        // real per-texture colour arrives with entity textures (P5.1b-2b).
        int c = qcol[0];
        float tr = ((c >> 16) & 0xFF) * (1f / 255f);
        float tg = ((c >> 8) & 0xFF) * (1f / 255f);
        float tb = (c & 0xFF) * (1f / 255f);
        for (int t = 0; t < 2; t++) { // one {normal+emission, tint} record per triangle
            prim.add(nx);
            prim.add(ny);
            prim.add(nz);
            prim.add(0f); // emission (entities don't carry block light)
            prim.add(tr);
            prim.add(tg);
            prim.add(tb);
            prim.add((float) currentTexSlot); // tint.w = bindless texture slot (P5.1b-2b)
        }
        any = true;
    }

    private void track(float x, float y, float z) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    // Unused VertexConsumer surface — ModelPart.Cube.compile only calls the bulk addVertex above.
    @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
    @Override public VertexConsumer setColor(int color) { return this; }
    @Override public VertexConsumer setUv(float u, float v) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }
}
