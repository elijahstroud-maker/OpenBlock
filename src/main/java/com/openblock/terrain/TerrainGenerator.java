package com.openblock.terrain;

import com.openblock.world.BlockType;
import com.openblock.world.Chunk;

public class TerrainGenerator {
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

    public TerrainGenerator(long seed) {
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

        chunk.generated = true;
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
            base = 42 + t * 16;           // 42..58 ocean floor
        } else if (continental < 0.0) {
            // Coastal / desert: 58..68, gentle slope that meets hills cleanly
            double t = (continental + 0.4) / 0.4;
            base = 58 + smoothstep(t) * 10; // 58..68, eased curve
        } else if (continental < 0.35) {
            double t = continental / 0.35;
            base = 68 + t * 22;           // 68..90 rolling hills
        } else {
            double t = Math.min(1, (continental - 0.35) / 0.50);
            base = 90 + t * 65;           // 90..155 mountains
        }

        // Smoothly blend peak & detail suppression across the coastal→hills boundary
        // At continental -0.15 → 0 we gradually turn peaks back on
        double hillBlend = smoothstep(clamp01((continental + 0.15) / 0.20));

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
