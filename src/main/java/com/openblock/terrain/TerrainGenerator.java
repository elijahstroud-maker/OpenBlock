package com.openblock.terrain;

import com.openblock.world.BlockType;
import com.openblock.world.Chunk;

public class TerrainGenerator {

    // Trees: canopy extends 2 blocks from trunk; min spacing between trunks
    private static final int TREE_CHECK_RADIUS = 2;
    private static final int TREE_MIN_DIST     = 5; // trunks must be this many blocks apart
    private static final int SEA_LEVEL   = 64;
    private static final int SNOW_ABOVE  = 95;
    private static final int STONE_ABOVE = 115;

    // How deep sand fills below a sandy surface
    private static final int SAND_DEPTH  = 4;

    // Cave carving: both noises must exceed this to carve
    private static final double CAVE_THRESHOLD = 0.52;
    private static final double CAVE_FREQ      = 0.042;
    private static final int    CAVE_MIN_Y     = 5;

    private final SimplexNoise noise;
    private final SimplexNoise noiseErode;
    private final SimplexNoise noisePeaks;
    private final SimplexNoise noiseDetail;
    private final SimplexNoise noiseCave1;
    private final SimplexNoise noiseCave2;
    private final long seed;

    public TerrainGenerator(long seed) {
        this.seed        = seed;
        this.noise       = new SimplexNoise(seed);
        this.noiseErode  = new SimplexNoise(seed ^ 0xABCDEF012345L);
        this.noisePeaks  = new SimplexNoise(seed ^ 0xDEADBEEFCAFEL);
        this.noiseDetail = new SimplexNoise(seed ^ 0x123456789ABL);
        this.noiseCave1  = new SimplexNoise(seed ^ 0xC0FFEE1234L);
        this.noiseCave2  = new SimplexNoise(seed ^ 0xFACEB00056L);
    }

    public void generate(Chunk chunk) {
        int worldX0 = chunk.getWorldX();
        int worldZ0 = chunk.getWorldZ();

        for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
            for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                double wx = worldX0 + lx;
                double wz = worldZ0 + lz;

                int height = computeHeight(wx, wz);
                BlockType surface = surfaceBlock(height);
                boolean isSandy = (surface == BlockType.SAND);

                // Thinner dirt layer on high mountains so stone shows on cliff faces
                int dirtDepth = height > 105 ? 2 : (height > 80 ? 3 : 4);

                for (int ly = 0; ly < Chunk.SIZE_Y; ly++) {
                    BlockType block;
                    if (ly == 0) {
                        block = BlockType.BEDROCK;
                    } else if (ly > height) {
                        block = BlockType.AIR;
                    } else if (ly == height) {
                        block = surface;
                    } else {
                        // Underground: check for cave carving first
                        boolean carved = isCave(wx, ly, wz, height);
                        if (carved) {
                            block = BlockType.AIR;
                        } else if (isSandy && ly >= height - SAND_DEPTH) {
                            // Sandy biome: fill subsurface with sand, no dirt showing
                            block = BlockType.SAND;
                        } else if (ly < height - dirtDepth) {
                            block = BlockType.STONE;
                        } else {
                            block = BlockType.DIRT;
                        }
                    }
                    chunk.setBlock(lx, ly, lz, block);
                }
            }
        }

        // Trees: iterate with canopy-overlap ring; cactus only needs column itself
        for (int lx = -TREE_CHECK_RADIUS; lx < Chunk.SIZE_X + TREE_CHECK_RADIUS; lx++) {
            for (int lz = -TREE_CHECK_RADIUS; lz < Chunk.SIZE_Z + TREE_CHECK_RADIUS; lz++) {
                int wx = worldX0 + lx;
                int wz = worldZ0 + lz;
                int height = computeHeight(wx, wz);
                BlockType surface = surfaceBlock(height);

                // --- Trees (grass only, enforced minimum spacing) ---
                if (surface == BlockType.GRASS) {
                    long hash = treeHash(wx, wz);
                    if ((hash & 0x1FL) == 0 && isTreeLocalMin(wx, wz, hash)) {
                        placeTree(chunk, lx, height + 1, lz, (int)(hash >>> 5));
                    }
                }

                // --- Cactus (sand/desert only, very sparse, within chunk only) ---
                if (surface == BlockType.SAND && lx >= 0 && lx < Chunk.SIZE_X && lz >= 0 && lz < Chunk.SIZE_Z) {
                    long hash = cactusHash(wx, wz);
                    if ((hash & 0x1FFL) == 0) { // ~1 in 512
                        // Height distribution: 1 (rare), 2-3 (common), 4 (rare)
                        int hv = (int)(hash >>> 9 & 0x7L); // 0-7
                        int cactusH = (hv == 0) ? 1 : (hv <= 3) ? 2 : (hv <= 6) ? 3 : 4;
                        for (int dy = 0; dy < cactusH; dy++) {
                            setBlock(chunk, lx, height + 1 + dy, lz, BlockType.CACTUS);
                        }
                    }
                }
            }
        }

        chunk.generated = true;
    }

    /** Full hash for a world column — bits 0-4 decide spawn, higher bits drive shape. */
    private long treeHash(int wx, int wz) {
        long h = (wx * 1664525L + 1013904223L) ^ (wz * 22695477L + 1L) ^ seed;
        h ^= h >>> 17;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        return h;
    }

    /**
     * Returns true if (wx, wz) has the smallest treeHash among all candidate
     * positions within TREE_MIN_DIST. Guarantees trunks are never closer than
     * TREE_MIN_DIST blocks from each other.
     */
    private boolean isTreeLocalMin(int wx, int wz, long myHash) {
        for (int dx = -TREE_MIN_DIST; dx <= TREE_MIN_DIST; dx++) {
            for (int dz = -TREE_MIN_DIST; dz <= TREE_MIN_DIST; dz++) {
                if (dx == 0 && dz == 0) continue;
                long h = treeHash(wx + dx, wz + dz);
                // Another candidate with a lower hash beats us
                if ((h & 0x1FL) == 0 && Long.compareUnsigned(h, myHash) < 0) return false;
            }
        }
        return true;
    }

    private long cactusHash(int wx, int wz) {
        long h = (wx * 2246822519L + 1L) ^ (wz * 3266489917L) ^ (seed * 0x9E3779B97F4A7C15L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }

    /**
     * Plants a tree whose base (first log) is at chunk-local (lx, baseY, lz).
     * {@code variant} is hash bits above the spawn-check bits and drives shape variety.
     *
     * Styles (by variant & 0xF):
     *   0-5  → trunk 4, standard canopy     (~37%)
     *   6-10 → trunk 5, standard canopy     (~31%)
     *   11-12→ trunk 6, standard canopy     (~12%)
     *   13-15→ trunk 4, bushy (extra low ring) (~19%)
     */
    private void placeTree(Chunk chunk, int lx, int baseY, int lz, int variant) {
        int style = variant & 0xF;
        int trunkH;
        boolean bushy;
        if (style <= 5)       { trunkH = 4; bushy = false; }
        else if (style <= 10) { trunkH = 5; bushy = false; }
        else if (style <= 12) { trunkH = 6; bushy = false; }
        else                  { trunkH = 4; bushy = true;  }

        int apex = baseY + trunkH - 1; // topmost LOG y

        // Trunk
        for (int dy = 0; dy < trunkH; dy++) {
            setBlock(chunk, lx, baseY + dy, lz, BlockType.LOG);
        }

        // Lower canopy: 5×5 minus corners; bushy trees get one extra ring below
        int lowStart = bushy ? -2 : -1;
        for (int dy = lowStart; dy <= 0; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue; // corners
                    if (dx == 0 && dz == 0) continue;                      // trunk slot
                    setBlockIfAir(chunk, lx + dx, apex + dy, lz + dz, BlockType.LEAVES);
                }
            }
        }

        // Upper canopy: 3×3 at apex+1 and apex+2 (no top corners on apex+2)
        for (int dy = 1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dy == 2 && Math.abs(dx) == 1 && Math.abs(dz) == 1) continue;
                    setBlockIfAir(chunk, lx + dx, apex + dy, lz + dz, BlockType.LEAVES);
                }
            }
        }
    }

    private void setBlock(Chunk chunk, int lx, int ly, int lz, BlockType type) {
        if (lx < 0 || lx >= Chunk.SIZE_X || lz < 0 || lz >= Chunk.SIZE_Z) return;
        if (ly < 0 || ly >= Chunk.SIZE_Y) return;
        chunk.setBlock(lx, ly, lz, type);
    }

    private void setBlockIfAir(Chunk chunk, int lx, int ly, int lz, BlockType type) {
        if (lx < 0 || lx >= Chunk.SIZE_X || lz < 0 || lz >= Chunk.SIZE_Z) return;
        if (ly < 0 || ly >= Chunk.SIZE_Y) return;
        if (chunk.getBlock(lx, ly, lz) == BlockType.AIR) {
            chunk.setBlock(lx, ly, lz, type);
        }
    }

    private boolean isCave(double wx, int ly, double wz, int surfaceY) {
        // Don't carve near the surface or near the bottom
        if (ly >= surfaceY - 4 || ly <= CAVE_MIN_Y) return false;
        // Two cross-sampled 2D noises produce pseudo-3D cave pockets
        double n1 = noiseCave1.noise(wx * CAVE_FREQ + ly * 0.09, wz * CAVE_FREQ);
        double n2 = noiseCave2.noise(wx * CAVE_FREQ, wz * CAVE_FREQ + ly * 0.09);
        // Normalise from ~[-1,1] to [0,1]
        n1 = (n1 + 1.0) / 2.0;
        n2 = (n2 + 1.0) / 2.0;
        return n1 > CAVE_THRESHOLD && n2 > CAVE_THRESHOLD;
    }

    private int computeHeight(double wx, double wz) {
        double continental = noise.octaves(wx, wz, 4, 0.0022, 0.55);

        double erosion = noiseErode.octaves(wx, wz, 3, 0.004, 0.5);
        double erodeStrength = Math.max(0, (erosion + 0.15) / 1.15);

        // Continuous spline — each band meets the next at the same value
        double base;
        if (continental < -0.4) {
            double t = (continental + 1.0) / 0.6;
            base = 42 + t * 16;              // 42..58  ocean floor
        } else if (continental < 0.0) {
            double t = (continental + 0.4) / 0.4;
            base = 58 + smoothstep(t) * 10; // 58..68  coastal / desert
        } else if (continental < 0.22) {
            double t = continental / 0.22;
            base = 68 + t * 4;              // 68..72  grassy plains (very flat)
        } else if (continental < 0.50) {
            double t = (continental - 0.22) / 0.28;
            base = 72 + t * 20;             // 72..92  rolling hills
        } else {
            double t = Math.min(1, (continental - 0.50) / 0.50);
            base = 92 + t * 63;             // 92..155 mountains
        }

        // hillBlend: 0 in plains (no peaks/detail boost), ramps up into hills
        double hillBlend = smoothstep(clamp01((continental - 0.20) / 0.22));

        double peaks = noisePeaks.ridged(wx, wz, 4, 0.008, 0.55);
        double landFactor = Math.max(0, (continental + 0.1) / 1.1);
        double peakBoost = peaks * landFactor * hillBlend * (1.0 - erodeStrength * 0.92) * 58;

        // Erosion pulls elevated terrain down into valleys
        double erodeDown = erodeStrength * Math.max(0, base - 63) * 0.58;

        // Detail noise scales up gradually from coast to inland
        double detail = noiseDetail.octaves(wx, wz, 2, 0.06, 0.5);
        double detailAmp = 1.2 + hillBlend * 2.8; // 1.2 at coast → 4.0 inland

        double h = base + peakBoost - erodeDown + detail * detailAmp;
        return (int) Math.max(2, Math.min(Chunk.SIZE_Y - 2, h));
    }

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double clamp01(double t) {
        return Math.max(0, Math.min(1, t));
    }

    private BlockType surfaceBlock(int height) {
        if (height <= SEA_LEVEL + 3) {
            return BlockType.SAND;
        } else if (height >= STONE_ABOVE) {
            return BlockType.STONE;
        } else if (height >= SNOW_ABOVE) {
            return BlockType.SNOW_GRASS;
        } else {
            return BlockType.GRASS;
        }
    }
}
