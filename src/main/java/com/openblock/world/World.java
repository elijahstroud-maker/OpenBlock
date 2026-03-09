package com.openblock.world;

import com.openblock.renderer.TextureAtlas;
import com.openblock.terrain.TerrainGenerator;

import java.util.*;
import java.util.concurrent.*;

public class World {
    private static final int RENDER_DISTANCE = 8;
    private static final int UNLOAD_DISTANCE = RENDER_DISTANCE + 3;
    /** Max chunk meshes rebuilt per frame to avoid stutter. */
    private static final int MAX_MESHES_PER_FRAME = 4;

    private final Map<Long, Chunk> loadedChunks = new LinkedHashMap<>();
    private final TerrainGenerator generator;
    private final TextureAtlas atlas;
    private final ChunkMesher mesher;
    private final ExecutorService genPool;

    /** Chunks whose generation completed and need a mesh upload on the main thread. */
    private final ConcurrentLinkedQueue<Chunk> readyToMesh = new ConcurrentLinkedQueue<>();

    /** Chunks currently being generated (to avoid submitting duplicates). */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public World() {
        generator = new TerrainGenerator(new java.util.Random().nextLong());
        atlas     = new TextureAtlas();
        mesher    = new ChunkMesher();
        genPool   = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    /** Called each frame from main thread with the player's current chunk coords. */
    public void update(int playerCX, int playerCZ) {
        loadChunksAround(playerCX, playerCZ);
        processMeshQueue();
        unloadDistantChunks(playerCX, playerCZ);
    }

    private void loadChunksAround(int cx, int cz) {
        // Iterate square, prioritized by distance (closer chunks first)
        List<long[]> toLoad = new ArrayList<>();
        for (int dx = -RENDER_DISTANCE; dx <= RENDER_DISTANCE; dx++) {
            for (int dz = -RENDER_DISTANCE; dz <= RENDER_DISTANCE; dz++) {
                int ccx = cx + dx;
                int ccz = cz + dz;
                long key = chunkKey(ccx, ccz);
                if (!loadedChunks.containsKey(key) && !inFlight.contains(key)) {
                    long dist2 = (long) dx * dx + (long) dz * dz;
                    toLoad.add(new long[]{key, ccx, ccz, dist2});
                }
            }
        }
        // Sort by distance
        toLoad.sort(Comparator.comparingLong(a -> a[3]));

        for (long[] entry : toLoad) {
            long key = entry[0];
            int ccx   = (int) entry[1];
            int ccz   = (int) entry[2];

            Chunk chunk = new Chunk(ccx, ccz);
            loadedChunks.put(key, chunk);
            inFlight.add(key);

            genPool.submit(() -> {
                generator.generate(chunk);
                readyToMesh.add(chunk);
                inFlight.remove(key);
            });
        }
    }

    private void processMeshQueue() {
        int built = 0;
        Chunk chunk;
        while (built < MAX_MESHES_PER_FRAME && (chunk = readyToMesh.poll()) != null) {
            if (!chunk.generated) continue;
            ChunkMesher.MeshData data = mesher.build(chunk, this, atlas);
            if (!data.isEmpty()) {
                chunk.uploadMesh(data.vertices(), data.indices());
            }
            built++;
        }

        // Also rebuild any chunks flagged dirty (e.g., adjacent chunk loaded)
        if (built < MAX_MESHES_PER_FRAME) {
            for (Chunk c : loadedChunks.values()) {
                if (c.generated && c.dirty && built < MAX_MESHES_PER_FRAME) {
                    ChunkMesher.MeshData data = mesher.build(c, this, atlas);
                    c.uploadMesh(data.vertices(), data.indices());
                    built++;
                }
            }
        }
    }

    private void unloadDistantChunks(int playerCX, int playerCZ) {
        Iterator<Map.Entry<Long, Chunk>> it = loadedChunks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Chunk> entry = it.next();
            Chunk c = entry.getValue();
            int dx = Math.abs(c.getChunkX() - playerCX);
            int dz = Math.abs(c.getChunkZ() - playerCZ);
            if (dx > UNLOAD_DISTANCE || dz > UNLOAD_DISTANCE) {
                c.cleanup();
                it.remove();
            }
        }
    }

    /** Get the block type at world coordinates. Returns AIR for out-of-bounds Y or unloaded chunks. */
    public BlockType getBlock(int worldX, int worldY, int worldZ) {
        if (worldY < 0 || worldY >= Chunk.SIZE_Y) return BlockType.AIR;
        int cx = Math.floorDiv(worldX, Chunk.SIZE_X);
        int cz = Math.floorDiv(worldZ, Chunk.SIZE_Z);
        Chunk chunk = loadedChunks.get(chunkKey(cx, cz));
        if (chunk == null || !chunk.generated) return BlockType.AIR;
        int lx = Math.floorMod(worldX, Chunk.SIZE_X);
        int lz = Math.floorMod(worldZ, Chunk.SIZE_Z);
        return chunk.getBlock(lx, worldY, lz);
    }

    public Collection<Chunk> getLoadedChunks() {
        return loadedChunks.values();
    }

    public int getLoadedMeshCount() {
        int count = 0;
        for (Chunk c : loadedChunks.values()) {
            if (c.getMesh() != null && !c.getMesh().isEmpty()) count++;
        }
        return count;
    }

    public void cleanup() {
        genPool.shutdownNow();
        for (Chunk c : loadedChunks.values()) c.cleanup();
        loadedChunks.clear();
        atlas.cleanup();
    }

    private static long chunkKey(int cx, int cz) {
        return ((long)(cx & 0xFFFFFFFFL)) | (((long)(cz & 0xFFFFFFFFL)) << 32);
    }
}
