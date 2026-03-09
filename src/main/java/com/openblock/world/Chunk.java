package com.openblock.world;

import com.openblock.renderer.Mesh;
import com.openblock.renderer.TextureAtlas;

public class Chunk {
    public static final int SIZE_X = 16;
    public static final int SIZE_Y = 256;
    public static final int SIZE_Z = 16;

    private final int chunkX;
    private final int chunkZ;

    /** Block data stored as ordinal of BlockType (fits in byte). */
    private final byte[] blocks = new byte[SIZE_X * SIZE_Y * SIZE_Z];

    /** Set to true after terrain generation is complete (written by background thread). */
    public volatile boolean generated = false;

    /** Set to true when block data changed and mesh needs rebuilding. */
    public volatile boolean dirty = false;

    private Mesh mesh;

    public Chunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public BlockType getBlock(int lx, int ly, int lz) {
        if (ly < 0 || ly >= SIZE_Y) return BlockType.AIR;
        return BlockType.fromOrdinal(blocks[index(lx, ly, lz)] & 0xFF);
    }

    public void setBlock(int lx, int ly, int lz, BlockType type) {
        blocks[index(lx, ly, lz)] = (byte) type.ordinal();
        dirty = true;
    }

    private static int index(int lx, int ly, int lz) {
        return (lx * SIZE_Y * SIZE_Z) + (ly * SIZE_Z) + lz;
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }

    public int getWorldX() { return chunkX * SIZE_X; }
    public int getWorldZ() { return chunkZ * SIZE_Z; }

    public Mesh getMesh() { return mesh; }

    /** Called on main thread only (OpenGL requirement). */
    public void uploadMesh(float[] vertices, int[] indices) {
        if (mesh == null) {
            mesh = new Mesh();
        }
        mesh.upload(vertices, indices);
        dirty = false;
    }

    public void cleanup() {
        if (mesh != null) {
            mesh.cleanup();
            mesh = null;
        }
    }
}
