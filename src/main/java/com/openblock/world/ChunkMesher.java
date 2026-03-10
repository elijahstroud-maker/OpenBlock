package com.openblock.world;

import com.openblock.renderer.TextureAtlas;
import com.openblock.renderer.TextureAtlas.Face;

import java.util.Arrays;

/**
 * Builds a chunk mesh using face-culling: only emit faces adjacent to transparent blocks.
 * Thread-safe: reads block data only, no OpenGL calls. Results returned as plain arrays.
 */
public class ChunkMesher {

    public record MeshData(float[] vertices, int[] indices) {
        public boolean isEmpty() { return indices.length == 0; }
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
    private float[] vertBuf = new float[131072];
    private int[]   idxBuf  = new int  [65536];

    public MeshData build(Chunk chunk, World world, TextureAtlas atlas) {
        int vp = 0;          // next write position in vertBuf
        int ip = 0;          // next write position in idxBuf
        int vertexBase = 0;

        for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
            for (int ly = 0; ly < Chunk.SIZE_Y; ly++) {
                for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                    BlockType block = chunk.getBlock(lx, ly, lz);
                    if (block == BlockType.AIR) continue;

                    for (int fi = 0; fi < 6; fi++) {
                        int[] off = FACE_OFFSETS[fi];
                        int nx = chunk.getWorldX() + lx + off[0];
                        int ny = ly + off[1];
                        int nz = chunk.getWorldZ() + lz + off[2];

                        if (world.getBlock(nx, ny, nz).opaque) continue;

                        float[] uv = atlas.getUV(block, FACES[fi]);
                        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
                        float light = FACE_LIGHTS[fi];

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

        return new MeshData(Arrays.copyOf(vertBuf, vp), Arrays.copyOf(idxBuf, ip));
    }
}
