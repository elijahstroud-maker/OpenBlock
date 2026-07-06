package com.openblock.world;

import com.openblock.renderer.TextureAtlas;
import com.openblock.terrain.TerrainGenerator;

import java.util.*;
import java.util.concurrent.*;

public class World {
    public static final int RENDER_DISTANCE = 8;
    private static final int UNLOAD_DISTANCE = RENDER_DISTANCE + 3;
    /** Max chunk meshes rebuilt per frame to avoid stutter. */
    private static final int MAX_MESHES_PER_FRAME = 4;
    /** Seconds between water spread steps — matches Minecraft's 5-game-tick water speed. */
    private static final float WATER_TICK_INTERVAL = 0.25f;
    /** Safety cap on water positions processed per tick (a whole front normally fits). */
    private static final int WATER_MAX_PER_TICK = 4096;

    private final Map<Long, Chunk> loadedChunks = new LinkedHashMap<>();
    private final TerrainGenerator generator;
    private final TextureAtlas atlas;
    private final ChunkMesher mesher;
    private final ExecutorService genPool;
    private final long worldSeed;

    /** Chunks whose generation completed and need a mesh upload on the main thread. */
    private final ConcurrentLinkedQueue<Chunk> readyToMesh = new ConcurrentLinkedQueue<>();

    /** Chunks currently being generated (to avoid submitting duplicates). */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** Pending water spread positions. Each entry is {x, y, z}. */
    private final Deque<int[]> waterQueue = new ArrayDeque<>();
    /** Set of already-queued positions to avoid duplicates (packed as waterKey). */
    private final Set<Long> waterQueued = new HashSet<>();
    /** Hop-count from source for each WATER_FLOWING block (1 = closest, WATER_REACH = farthest). */
    private final Map<Long, Integer> waterLevels = new HashMap<>();
    private float waterTimer = 0f;

    /** Player chunk coords at the last load scan — skips rescanning when unchanged. */
    private int lastLoadCX = Integer.MIN_VALUE;
    private int lastLoadCZ = Integer.MIN_VALUE;

    public World() {
        worldSeed = new java.util.Random().nextLong();
        generator = new TerrainGenerator(worldSeed);
        atlas     = new TextureAtlas();
        mesher    = new ChunkMesher();
        genPool   = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    /**
     * Returns [x, y, z] for a safe spawn close to the world origin.
     * Searches in a spiral (4-block steps) for the nearest land column above sea level.
     * Desert, grass, whatever — any solid surface is fine.
     */
    public float[] findSafeSpawn() {
        // Each world seed picks a different search origin near (0,0), like Minecraft
        java.util.Random rng = new java.util.Random(worldSeed);
        int baseX = rng.nextInt(201) - 100;
        int baseZ = rng.nextInt(201) - 100;

        for (int r = 0; r <= 50; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int wx = baseX + dx * 4;
                    int wz = baseZ + dz * 4;
                    int h = generator.getHeight(wx, wz);
                    if (h > 65 && !generator.isNearTree(wx, wz) && !generator.hasSurfaceOpening(wx, wz)) {
                        return new float[]{wx + 0.5f, h + 2.62f, wz + 0.5f};
                    }
                }
            }
        }
        return new float[]{baseX + 0.5f, generator.getHeight(baseX, baseZ) + 2.62f, baseZ + 0.5f};
    }

    /** Returns the terrain surface height at the given world position. */
    public int getTerrainHeight(int wx, int wz) {
        return generator.getHeight(wx, wz);
    }

    /** Called each frame from main thread with the player's current chunk coords. */
    public void update(int playerCX, int playerCZ, float delta) {
        loadChunksAround(playerCX, playerCZ);
        processMeshQueue();
        processWaterUpdates(delta);
        unloadDistantChunks(playerCX, playerCZ);
    }

    private void loadChunksAround(int cx, int cz) {
        // Every missing chunk is submitted the moment it's first seen, so a rescan
        // is only needed when the player crosses into a different chunk.
        if (cx == lastLoadCX && cz == lastLoadCZ) return;
        lastLoadCX = cx;
        lastLoadCZ = cz;

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

        // Dirty chunks first — player block-break/place needs to reflect immediately.
        for (Chunk c : loadedChunks.values()) {
            if (c.generated && c.dirty && built < MAX_MESHES_PER_FRAME) {
                ChunkMesher.MeshData data = mesher.build(c, this, atlas);
                c.uploadMesh(data.vertices(), data.indices());
                c.uploadWaterMesh(data.waterVertices(), data.waterIndices());
                built++;
            }
        }

        // Then newly-generated chunks waiting for their first mesh upload.
        Chunk chunk;
        while (built < MAX_MESHES_PER_FRAME && (chunk = readyToMesh.poll()) != null) {
            if (!chunk.generated) continue;
            // Chunk was unloaded (or replaced) while its generation was in flight.
            if (loadedChunks.get(chunkKey(chunk.getChunkX(), chunk.getChunkZ())) != chunk) continue;
            ChunkMesher.MeshData data = mesher.build(chunk, this, atlas);
            if (!data.isEmpty()) {
                chunk.uploadMesh(data.vertices(), data.indices());
            }
            if (!data.isWaterEmpty()) {
                chunk.uploadWaterMesh(data.waterVertices(), data.waterIndices());
            }
            built++;

            // Neighbors meshed before this chunk generated saw AIR across the
            // border and baked stale faces (phantom water walls inside oceans,
            // hidden terrain curtains). Remesh them against the real blocks.
            remeshIfMeshed(chunk.getChunkX() - 1, chunk.getChunkZ());
            remeshIfMeshed(chunk.getChunkX() + 1, chunk.getChunkZ());
            remeshIfMeshed(chunk.getChunkX(), chunk.getChunkZ() - 1);
            remeshIfMeshed(chunk.getChunkX(), chunk.getChunkZ() + 1);
        }
    }

    /** Marks a chunk dirty only if it already has a mesh built from stale neighbor data. */
    private void remeshIfMeshed(int cx, int cz) {
        Chunk c = loadedChunks.get(chunkKey(cx, cz));
        if (c != null && c.generated && c.getMesh() != null) c.dirty = true;
    }

    private void unloadDistantChunks(int playerCX, int playerCZ) {
        Set<Long> unloaded = null;
        Iterator<Map.Entry<Long, Chunk>> it = loadedChunks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Chunk> entry = it.next();
            Chunk c = entry.getValue();
            int dx = Math.abs(c.getChunkX() - playerCX);
            int dz = Math.abs(c.getChunkZ() - playerCZ);
            if (dx > UNLOAD_DISTANCE || dz > UNLOAD_DISTANCE) {
                c.cleanup();
                it.remove();
                if (unloaded == null) unloaded = new HashSet<>();
                unloaded.add(entry.getKey());
            }
        }
        // Drop water levels belonging to unloaded chunks so the map doesn't grow forever.
        if (unloaded != null) {
            final Set<Long> gone = unloaded;
            waterLevels.keySet().removeIf(k -> {
                int x = (int) ((k << 40) >> 40); // sign-extend bits 0-23
                int z = (int) ((k << 8)  >> 40); // sign-extend bits 32-55
                return gone.contains(chunkKey(
                    Math.floorDiv(x, Chunk.SIZE_X), Math.floorDiv(z, Chunk.SIZE_Z)));
            });
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

    /** Set a block at world coordinates and mark affected chunks dirty for remeshing. */
    public void setBlock(int worldX, int worldY, int worldZ, BlockType type) {
        if (worldY < 0 || worldY >= Chunk.SIZE_Y) return;
        int cx = Math.floorDiv(worldX, Chunk.SIZE_X);
        int cz = Math.floorDiv(worldZ, Chunk.SIZE_Z);
        Chunk chunk = loadedChunks.get(chunkKey(cx, cz));
        if (chunk == null || !chunk.generated) return;
        int lx = Math.floorMod(worldX, Chunk.SIZE_X);
        int lz = Math.floorMod(worldZ, Chunk.SIZE_Z);
        chunk.setBlock(lx, worldY, lz, type);
        chunk.dirty = true;

        // Mark neighboring chunks dirty when the modified block is on a chunk boundary,
        // so their meshes expose the newly-revealed faces.
        if (lx == 0)              markDirty(cx - 1, cz);
        if (lx == Chunk.SIZE_X-1) markDirty(cx + 1, cz);
        if (lz == 0)              markDirty(cx, cz - 1);
        if (lz == Chunk.SIZE_Z-1) markDirty(cx, cz + 1);

        // When a block is removed, clean up its water level and trigger adjacent water to spread.
        if (type == BlockType.AIR) {
            waterLevels.remove(waterKey(worldX, worldY, worldZ));
            // Schedule the fill rather than resolving it synchronously this frame, so
            // water visibly flows into the gap over the next ticks instead of snapping full.
            if (isWaterAny(getBlock(worldX, worldY + 1, worldZ))) {
                // Water directly above: it will fall into this gap on its next tick.
                scheduleWaterUpdate(worldX, worldY + 1, worldZ, WATER_REACH);
            } else if (countAdjacentSources(worldX, worldY, worldZ) >= 2) {
                // Two or more adjacent sources (ocean interior or dam break): fill as source
                // so the block is full-height and no gap-fill side faces appear.
                setBlock(worldX, worldY, worldZ, BlockType.WATER);
                scheduleWaterUpdate(worldX, worldY, worldZ, WATER_REACH);
            }
            // If only 1 side is source, scheduleIfWater below will queue it as flowing.
            // Schedule re-spread for adjacent water (handles flowing water and downward fall).
            scheduleIfWater(worldX + 1, worldY, worldZ);
            scheduleIfWater(worldX - 1, worldY, worldZ);
            scheduleIfWater(worldX, worldY + 1, worldZ);
            scheduleIfWater(worldX, worldY - 1, worldZ);
            scheduleIfWater(worldX, worldY, worldZ + 1);
            scheduleIfWater(worldX, worldY, worldZ - 1);
        }
    }

    private void markDirty(int cx, int cz) {
        Chunk c = loadedChunks.get(chunkKey(cx, cz));
        if (c != null && c.generated) c.dirty = true;
    }

    /** How many blocks water spreads horizontally from its source before stopping. */
    private static final int WATER_REACH = 7;

    /** Returns hop-count from source for WATER_FLOWING (1=closest), 0 for source WATER. */
    public int getWaterLevel(int x, int y, int z) {
        return waterLevels.getOrDefault(waterKey(x, y, z), 0);
    }

    private void scheduleIfWater(int x, int y, int z) {
        BlockType b = getBlock(x, y, z);
        if (b == BlockType.WATER) {
            scheduleWaterUpdate(x, y, z, WATER_REACH);
        } else if (b == BlockType.WATER_FLOWING) {
            // Remaining distance = how many more blocks this flowing water can still spread
            int level = waterLevels.getOrDefault(waterKey(x, y, z), WATER_REACH);
            int remaining = WATER_REACH - level; // level 1→6 more blocks, level 7→0 more blocks
            scheduleWaterUpdate(x, y, z, remaining);
        }
    }

    private void scheduleWaterUpdate(int x, int y, int z, int distance) {
        long key = waterKey(x, y, z);
        if (waterQueued.add(key)) {
            waterQueue.addLast(new int[]{x, y, z, distance});
        }
    }

    private void processWaterUpdates(float delta) {
        waterTimer += delta;
        if (waterTimer < WATER_TICK_INTERVAL) return;
        waterTimer -= WATER_TICK_INTERVAL;
        // Advance the entire pending front one step per tick (a BFS wave): every
        // queued position moves together, so floods spread as a uniform wave
        // instead of a few cells at a time trickling ahead of the rest.
        // Positions scheduled during processing land after the snapshot count
        // and wait for the next tick — that's what sets the visible flow rate.
        int snapshot = Math.min(waterQueue.size(), WATER_MAX_PER_TICK);
        for (int i = 0; i < snapshot && !waterQueue.isEmpty(); i++) {
            int[] pos = waterQueue.pollFirst();
            waterQueued.remove(waterKey(pos[0], pos[1], pos[2]));
            spreadWater(pos[0], pos[1], pos[2], pos[3]);
        }
    }

    private static boolean isWaterAny(BlockType b) {
        return b == BlockType.WATER || b == BlockType.WATER_FLOWING;
    }

    /** Count horizontal WATER source blocks adjacent to (x, y, z). */
    private int countAdjacentSources(int x, int y, int z) {
        int n = 0;
        if (getBlock(x + 1, y, z) == BlockType.WATER) n++;
        if (getBlock(x - 1, y, z) == BlockType.WATER) n++;
        if (getBlock(x, y, z + 1) == BlockType.WATER) n++;
        if (getBlock(x, y, z - 1) == BlockType.WATER) n++;
        return n;
    }

    private void spreadWater(int x, int y, int z, int distance) {
        if (!isWaterAny(getBlock(x, y, z))) return;

        // Air below → water falls one block this tick, then continues next tick.
        // Stepping (instead of an instant while-chain) keeps waterfalls and cave
        // floods visibly gradual rather than filling a whole column at once.
        if (y > 0 && getBlock(x, y - 1, z) == BlockType.AIR) {
            setBlock(x, y - 1, z, BlockType.WATER);
            // Landing block keeps falling next tick, then spreads with fresh reach.
            scheduleWaterUpdate(x, y - 1, z, WATER_REACH);
            return; // falling water does not also spread sideways
        }

        // On solid ground — spread one block sideways per tick
        if (distance <= 0) return;
        int level = WATER_REACH - distance + 1; // 1 = closest to source, WATER_REACH = farthest
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : sides) {
            int nx = x + d[0], nz = z + d[1];
            if (getBlock(nx, y, nz) == BlockType.AIR) {
                setBlock(nx, y, nz, BlockType.WATER_FLOWING);
                waterLevels.put(waterKey(nx, y, nz), level);
                scheduleWaterUpdate(nx, y, nz, distance - 1);
            }
        }
    }

    private static long waterKey(int x, int y, int z) {
        // Pack x (24 bits), y (8 bits), z (24 bits) into a long
        return ((long)(x & 0xFFFFFF)) | (((long)(y & 0xFF)) << 24) | (((long)(z & 0xFFFFFF)) << 32);
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
