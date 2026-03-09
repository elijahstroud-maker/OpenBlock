package com.openblock.terrain;

import com.openblock.world.BlockType;
import com.openblock.world.Chunk;

public class TerrainGenerator {
    private static final int BASE_HEIGHT    = 64;
    private static final int HEIGHT_RANGE   = 48;  // heights: 64..112
    private static final int SEA_LEVEL      = 64;

    private final SimplexNoise noise;

    public TerrainGenerator(long seed) {
        this.noise = new SimplexNoise(seed);
    }

    /** Fills the chunk's block data. Safe to call from any thread. */
    public void generate(Chunk chunk) {
        int worldX0 = chunk.getWorldX();
        int worldZ0 = chunk.getWorldZ();

        for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
            for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                double wx = worldX0 + lx;
                double wz = worldZ0 + lz;

                // Multi-octave noise
                double n = 0;
                n += noise.noise(wx * 0.004, wz * 0.004) * 1.00;
                n += noise.noise(wx * 0.020, wz * 0.020) * 0.50;
                n += noise.noise(wx * 0.080, wz * 0.080) * 0.25;
                n += noise.noise(wx * 0.200, wz * 0.200) * 0.12;

                // Normalize to [0, 1] (raw sum range roughly ±1.87)
                double normalized = (n / 1.87 + 1.0) / 2.0;
                normalized = Math.max(0, Math.min(1, normalized));

                int height = (int) (BASE_HEIGHT + normalized * HEIGHT_RANGE);

                for (int ly = 0; ly < Chunk.SIZE_Y; ly++) {
                    BlockType block;
                    if (ly == 0) {
                        block = BlockType.BEDROCK;
                    } else if (ly < height - 4) {
                        block = BlockType.STONE;
                    } else if (ly < height) {
                        block = BlockType.DIRT;
                    } else if (ly == height) {
                        block = (height > SEA_LEVEL + 1) ? BlockType.GRASS : BlockType.SAND;
                    } else {
                        block = BlockType.AIR;
                    }
                    chunk.setBlock(lx, ly, lz, block);
                }
            }
        }

        // Mark generation complete (visible to main thread)
        chunk.generated = true;
        // dirty was set by setBlock calls; meshing happens on main thread
    }
}
