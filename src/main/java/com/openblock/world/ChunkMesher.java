package com.openblock.world;

import com.openblock.renderer.TextureAtlas;
import com.openblock.renderer.TextureAtlas.Face;

import java.util.Arrays;

/**
 * Builds a chunk mesh using face-culling: only emit faces adjacent to transparent blocks.
 * Thread-safe: reads block data only, no OpenGL calls. Results returned as plain arrays.
 * Produces two meshes: opaque (solid blocks) and water (transparent, rendered separately).
 */
public class ChunkMesher {

    public record MeshData(float[] vertices, int[] indices, float[] waterVertices, int[] waterIndices) {
        public boolean isEmpty()      { return indices.length == 0 && waterIndices.length == 0; }
        public boolean isWaterEmpty() { return waterIndices.length == 0; }
    }

    // Light values baked per face direction
    private static final float LIGHT_TOP   = 1.00f;
    private static final float LIGHT_EW    = 0.85f;
    private static final float LIGHT_NS    = 0.80f;
    private static final float LIGHT_BOT   = 0.60f;

    private static final int[][] FACE_OFFSETS = {
        { 0,  1,  0}, // TOP
        { 0, -1,  0}, // BOTTOM
        { 0,  0, -1}, // NORTH (-Z)
        { 0,  0,  1}, // SOUTH (+Z)
        { 1,  0,  0}, // EAST  (+X)
        {-1,  0,  0}, // WEST  (-X)
    };

    private static final Face[] FACES = Face.values();

    private static final float[] FACE_LIGHTS = {
        LIGHT_TOP, LIGHT_BOT, LIGHT_NS, LIGHT_NS, LIGHT_EW, LIGHT_EW
    };

    /**
     * Each face has 4 vertices defined as offsets from (x,y,z).
     * Order is counter-clockwise when viewed from outside, matching back-face culling.
     * Layout: [x,y,z, x,y,z, x,y,z, x,y,z]
     */
    private static final int[][][] FACE_VERTS = {
        // TOP (+Y) — CCW from above, normal = (0,+1,0)
        {{0,1,1},{1,1,1},{1,1,0},{0,1,0}},
        // BOTTOM (-Y) — CCW from below, normal = (0,-1,0)
        {{0,0,0},{1,0,0},{1,0,1},{0,0,1}},
        // NORTH (-Z)
        {{1,0,0},{0,0,0},{0,1,0},{1,1,0}},
        // SOUTH (+Z)
        {{0,0,1},{1,0,1},{1,1,1},{0,1,1}},
        // EAST (+X)
        {{1,0,1},{1,0,0},{1,1,0},{1,1,1}},
        // WEST (-X)
        {{0,0,0},{0,0,1},{0,1,1},{0,1,0}},
    };

    // Reusable primitive scratch buffers — no boxing/GC overhead per build
    private float[] vertBuf      = new float[131072];
    private int[]   idxBuf       = new int  [65536];
    private float[] waterVertBuf = new float[32768];
    private int[]   waterIdxBuf  = new int  [16384];

    public MeshData build(Chunk chunk, World world, TextureAtlas atlas) {
        int vp = 0, ip = 0, vertexBase = 0;
        int wvp = 0, wip = 0, waterVertexBase = 0;

        // Cap the Y scan at the chunk's highest block — everything above is sky.
        int yMax = Math.min(Chunk.SIZE_Y - 1, chunk.getMaxNonAirY());

        for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
            for (int ly = 0; ly <= yMax; ly++) {
                for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                    BlockType block = chunk.getBlock(lx, ly, lz);
                    if (block == BlockType.AIR) continue;

                    boolean isWater = (block == BlockType.WATER || block == BlockType.WATER_FLOWING);
                    // Each hop from source lowers the top face by 1/8 block (Minecraft-style levels).
                    // Exception: water with water above renders as a FULL cube — the column is
                    // continuous, so reduced heights would open see-through slivers mid-body.
                    float waterTop = 1.0f;
                    if (isWater && block == BlockType.WATER_FLOWING) {
                        BlockType above = chunk.getBlock(lx, ly + 1, lz);
                        if (above != BlockType.WATER && above != BlockType.WATER_FLOWING) {
                            int level = world.getWaterLevel(chunk.getWorldX() + lx, ly, chunk.getWorldZ() + lz);
                            waterTop = 1.0f - level * 0.125f; // level 1→0.875, 2→0.75, 3→0.625, 4→0.5
                        }
                    }

                    for (int fi = 0; fi < 6; fi++) {
                        int[] off = FACE_OFFSETS[fi];
                        int nlx = lx + off[0];
                        int nly = ly + off[1];
                        int nlz = lz + off[2];
                        int nx = chunk.getWorldX() + nlx;
                        int ny = nly;
                        int nz = chunk.getWorldZ() + nlz;
                        // In-chunk neighbors read the chunk directly — skips the
                        // floorDiv + chunk-map lookup that world.getBlock does.
                        BlockType neighbor =
                            (nlx >= 0 && nlx < Chunk.SIZE_X && nlz >= 0 && nlz < Chunk.SIZE_Z)
                                ? chunk.getBlock(nlx, nly, nlz)
                                : world.getBlock(nx, ny, nz);

                        float gapBottom = 0.0f; // for gap-fill faces: y of the lower neighbour's top
                        boolean neighborWater = (neighbor == BlockType.WATER || neighbor == BlockType.WATER_FLOWING);
                        if (isWater) {
                            // Water faces render against anything non-opaque except water itself.
                            // sideToLower → taller block renders a notch-fill toward shorter (gapBottom..waterTop)
                            boolean sideToLower = false;
                            if (fi >= 2 && neighborWater) {
                                // Neighbor's effective top follows the same full-cube rule
                                // as waterTop: water above → full height.
                                BlockType nAbove =
                                    (nlx >= 0 && nlx < Chunk.SIZE_X && nlz >= 0 && nlz < Chunk.SIZE_Z)
                                        ? chunk.getBlock(nlx, nly + 1, nlz)
                                        : world.getBlock(nx, ny + 1, nz);
                                float neighborTop = 1.0f;
                                if (neighbor == BlockType.WATER_FLOWING
                                        && nAbove != BlockType.WATER && nAbove != BlockType.WATER_FLOWING) {
                                    neighborTop = 1.0f - world.getWaterLevel(nx, ny, nz) * 0.125f;
                                }
                                if (waterTop > neighborTop + 0.001f) {
                                    sideToLower = true;
                                    gapBottom = neighborTop;
                                }
                                // equal or taller neighbor → no face (avoids internal faces inside ocean bodies)
                            }
                            if (neighborWater && !sideToLower) continue; // internal water face
                            // An opaque neighbor only hides faces that lie on the shared
                            // block plane. A reduced-height water top sits INSET below the
                            // plane, so a solid ceiling (tunnel roof) doesn't cover it —
                            // always draw the inset top surface.
                            boolean insetTop = (fi == 0 && waterTop < 0.999f);
                            if (!neighborWater && neighbor.opaque && !insetTop) continue; // hidden by solid
                        } else {
                            // Solid faces render against any non-opaque neighbor (AIR, LEAVES, WATER)
                            if (neighbor.opaque) continue;
                        }

                        float[] uv = atlas.getUV(block, FACES[fi]);
                        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
                        float light = FACE_LIGHTS[fi];

                        if (isWater) {
                            if (wvp + 24 > waterVertBuf.length) waterVertBuf = Arrays.copyOf(waterVertBuf, waterVertBuf.length * 2);
                            if (wip + 6  > waterIdxBuf.length)  waterIdxBuf  = Arrays.copyOf(waterIdxBuf,  waterIdxBuf.length  * 2);

                            int[][] fv = FACE_VERTS[fi];
                            float faceTop    = waterTop;
                            float faceBottom = (fi >= 2 && neighborWater) ? gapBottom : 0.0f;
                            float y0 = fv[0][1] == 1 ? faceTop : faceBottom;
                            float y1 = fv[1][1] == 1 ? faceTop : faceBottom;
                            float y2 = fv[2][1] == 1 ? faceTop : faceBottom;
                            float y3 = fv[3][1] == 1 ? faceTop : faceBottom;
                            waterVertBuf[wvp++] = lx+fv[0][0]; waterVertBuf[wvp++] = ly+y0; waterVertBuf[wvp++] = lz+fv[0][2]; waterVertBuf[wvp++] = u0; waterVertBuf[wvp++] = v1; waterVertBuf[wvp++] = light;
                            waterVertBuf[wvp++] = lx+fv[1][0]; waterVertBuf[wvp++] = ly+y1; waterVertBuf[wvp++] = lz+fv[1][2]; waterVertBuf[wvp++] = u1; waterVertBuf[wvp++] = v1; waterVertBuf[wvp++] = light;
                            waterVertBuf[wvp++] = lx+fv[2][0]; waterVertBuf[wvp++] = ly+y2; waterVertBuf[wvp++] = lz+fv[2][2]; waterVertBuf[wvp++] = u1; waterVertBuf[wvp++] = v0; waterVertBuf[wvp++] = light;
                            waterVertBuf[wvp++] = lx+fv[3][0]; waterVertBuf[wvp++] = ly+y3; waterVertBuf[wvp++] = lz+fv[3][2]; waterVertBuf[wvp++] = u0; waterVertBuf[wvp++] = v0; waterVertBuf[wvp++] = light;

                            waterIdxBuf[wip++] = waterVertexBase;   waterIdxBuf[wip++] = waterVertexBase+1; waterIdxBuf[wip++] = waterVertexBase+2;
                            waterIdxBuf[wip++] = waterVertexBase+2; waterIdxBuf[wip++] = waterVertexBase+3; waterIdxBuf[wip++] = waterVertexBase;
                            waterVertexBase += 4;
                        } else {
                            if (vp + 24 > vertBuf.length) vertBuf = Arrays.copyOf(vertBuf, vertBuf.length * 2);
                            if (ip + 6  > idxBuf.length)  idxBuf  = Arrays.copyOf(idxBuf,  idxBuf.length  * 2);

                            int[][] fv = FACE_VERTS[fi];
                            vertBuf[vp++] = lx+fv[0][0]; vertBuf[vp++] = ly+fv[0][1]; vertBuf[vp++] = lz+fv[0][2]; vertBuf[vp++] = u0; vertBuf[vp++] = v1; vertBuf[vp++] = light;
                            vertBuf[vp++] = lx+fv[1][0]; vertBuf[vp++] = ly+fv[1][1]; vertBuf[vp++] = lz+fv[1][2]; vertBuf[vp++] = u1; vertBuf[vp++] = v1; vertBuf[vp++] = light;
                            vertBuf[vp++] = lx+fv[2][0]; vertBuf[vp++] = ly+fv[2][1]; vertBuf[vp++] = lz+fv[2][2]; vertBuf[vp++] = u1; vertBuf[vp++] = v0; vertBuf[vp++] = light;
                            vertBuf[vp++] = lx+fv[3][0]; vertBuf[vp++] = ly+fv[3][1]; vertBuf[vp++] = lz+fv[3][2]; vertBuf[vp++] = u0; vertBuf[vp++] = v0; vertBuf[vp++] = light;

                            idxBuf[ip++] = vertexBase;     idxBuf[ip++] = vertexBase+1; idxBuf[ip++] = vertexBase+2;
                            idxBuf[ip++] = vertexBase+2;   idxBuf[ip++] = vertexBase+3; idxBuf[ip++] = vertexBase;
                            vertexBase += 4;
                        }
                    }
                }
            }
        }

        return new MeshData(
            Arrays.copyOf(vertBuf, vp),      Arrays.copyOf(idxBuf, ip),
            Arrays.copyOf(waterVertBuf, wvp), Arrays.copyOf(waterIdxBuf, wip)
        );
    }
}
