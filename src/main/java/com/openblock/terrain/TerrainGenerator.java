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

    // ---- Cave carving (all true 3D noise — the old 2D+shear carvers made
    // ---- slice-like caves; 3D iso-surfaces curve in every axis) ----
    private static final int CAVE_MIN_Y = 5;

    // Spaghetti tunnels: carve where TWO 3D noises are simultaneously near zero.
    // Their zero-surfaces intersect along continuous 1D curves through 3D space —
    // round winding tubes (same principle as Minecraft 1.18 spaghetti caves).
    // Lower vertical frequency → long gentle ramps rather than bouncy tubes.
    private static final double SPAG_FREQ_H = 0.020;
    private static final double SPAG_FREQ_V = 0.014;
    private static final double SPAG_RADIUS = 0.12;   // tube radius in noise units

    // Cheese caverns: single 3D noise above a threshold → naturally rounded
    // room-sized voids deep underground. Higher vertical frequency squashes the
    // rooms wide and low, like real chambers.
    private static final double CHEESE_FREQ_H = 0.017;
    private static final double CHEESE_FREQ_V = 0.028;
    private static final int    CAVERN_TOP_Y  = 38;

    // Cave entrances: outside rare "entrance zones" tunnels seal themselves over
    // the top ENTRANCE_TAPER_DEPTH blocks (no random potholes / cliff slits).
    // Inside a zone the taper is cancelled and the tunnel breaches the surface at
    // full width — rare, wide, walkable cave mouths.
    private static final int    ENTRANCE_TAPER_DEPTH = 12;
    private static final double ENTRANCE_FREQ        = 0.006;

    // Ravines
    private static final double RAVINE_FREQ      = 0.003;  // ridge line spacing ~333 blocks
    private static final double RAVINE_THRESHOLD = 0.90;   // normal width ~17 blocks
    private static final int    RAVINE_MAX_DEPTH = 60;

    // Rivers — two-noise tube (same principle as tunnel caves, but 2D)
    // The path is where both noises are simultaneously near zero — creates natural
    // branching/winding curves with no straight-line artefacts.
    private static final double RIVER_FREQ    = 0.006;  // river path density (~167 block scale)
    private static final double RIVER_RADIUS  = 0.24;   // valley influence in noise space
    private static final int    RIVER_BED_MIN = 2;      // shallowest bed: SEA_LEVEL-2 = y 62
    private static final int    RIVER_BED_MAX = 6;      // deepest bed:    SEA_LEVEL-6 = y 58



    private final SimplexNoise noise;
    private final SimplexNoise noiseErode;
    private final SimplexNoise noisePeaks;
    private final SimplexNoise noiseDetail;
    private final SimplexNoise noiseSpag1;      // spaghetti tunnel pair (3D)
    private final SimplexNoise noiseSpag2;
    private final SimplexNoise noiseCheese;     // cheese cavern rooms (3D)
    private final SimplexNoise noiseTunnelSize; // swells/pinches tunnel radius along its length
    private final SimplexNoise noiseCaviness;   // regional cave richness: honeycombed vs barren areas
    private final SimplexNoise noiseEntrance;   // rare zones where tunnels may breach the surface
    private final SimplexNoise noiseRavine;
    private final SimplexNoise noiseRavineCtrl; // mask + size variation
    private final SimplexNoise noiseRiver;
    private final SimplexNoise noiseRiverWarp; // domain-warp noise for river meanders

    private final SimplexNoise noiseArid;  // drives desert biome independently of height
    private final long seed;

    public TerrainGenerator(long seed) {
        this.seed        = seed;
        this.noise       = new SimplexNoise(seed);
        this.noiseErode  = new SimplexNoise(seed ^ 0xABCDEF012345L);
        this.noisePeaks  = new SimplexNoise(seed ^ 0xDEADBEEFCAFEL);
        this.noiseDetail = new SimplexNoise(seed ^ 0x123456789ABL);
        this.noiseSpag1      = new SimplexNoise(seed ^ 0xB00B135D00D1L);
        this.noiseSpag2      = new SimplexNoise(seed ^ 0xFEEDFACE9876L);
        this.noiseCheese     = new SimplexNoise(seed ^ 0xCAFEBABE1111L);
        this.noiseTunnelSize = new SimplexNoise(seed ^ 0x7E515EE0FFL);
        this.noiseCaviness   = new SimplexNoise(seed ^ 0xCA71E55B10BL);
        this.noiseEntrance   = new SimplexNoise(seed ^ 0x0DEAD00E5B1CL);
        this.noiseRavine     = new SimplexNoise(seed ^ 0xDEAD10CC0000L);
        this.noiseRavineCtrl = new SimplexNoise(seed ^ 0x1337C0DEB00BL);
        this.noiseRiver      = new SimplexNoise(seed ^ 0xA11EC0FFEEL);
        this.noiseRiverWarp  = new SimplexNoise(seed ^ 0x3A7B5C2D91EFL);

        this.noiseArid       = new SimplexNoise(seed ^ 0x9A71D05E17CL);
    }

    public void generate(Chunk chunk) {
        int worldX0 = chunk.getWorldX();
        int worldZ0 = chunk.getWorldZ();

        for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
            for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                double wx = worldX0 + lx;
                double wz = worldZ0 + lz;

                int height = computeHeight(wx, wz);
                double continental = noise.octaves(wx, wz, 4, 0.0022, 0.55);
                double arid = noiseArid.octaves(wx, wz, 2, 0.005, 0.5);
                BlockType surface = surfaceBlock(height, continental, arid);
                boolean isSandy  = (surface == BlockType.SAND);
                boolean isGravel = (surface == BlockType.GRAVEL);

                // Thinner dirt layer on high mountains so stone shows on cliff faces
                int dirtDepth = height > 105 ? 2 : (height > 80 ? 3 : 4);

                // If a tunnel reaches the block directly under the surface, remove the
                // surface block too — opens the cave mouth. The entrance-zone taper
                // inside isTunnelCave means this only happens in designated zones,
                // where the worm breaches at full width (walkable mouth) instead of
                // random potholes everywhere.
                // Only open mouths in terrain well above sea level: near rivers/lakes
                // (height just above 64) a hole would reveal water through the cave wall.
                boolean tunnelUnderSurface = (height > SEA_LEVEL + 4) &&
                        isTunnelCave(wx, height - 1, wz, height);

                for (int ly = 0; ly < Chunk.SIZE_Y; ly++) {
                    BlockType block;
                    if (ly == 0) {
                        block = BlockType.BEDROCK;
                    } else if (ly > height) {
                        // Fill with water up to sea level for submerged columns (oceans, rivers, ponds)
                        block = (ly <= SEA_LEVEL && height < SEA_LEVEL) ? BlockType.WATER : BlockType.AIR;
                    } else if (isRavine(wx, ly, wz, height)) {
                        // Ravine carves through the surface block so ravines are open sky
                        block = BlockType.AIR;
                    } else if (ly == height) {
                        block = tunnelUnderSurface ? BlockType.AIR : surface;
                    } else {
                        // Underground: check for cave carving first
                        boolean carved = isTunnelCave(wx, ly, wz, height)
                                      || isCavern(wx, ly, wz, height);
                        if (carved) {
                            block = BlockType.AIR;
                        } else if (isGravel && ly >= height - 1) {
                            // Submerged: 2 layers of gravel (surface + 1 below)
                            block = BlockType.GRAVEL;
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
                double continental = noise.octaves(wx, wz, 4, 0.0022, 0.55);
                double arid = noiseArid.octaves(wx, wz, 2, 0.005, 0.5);
                BlockType surface = surfaceBlock(height, continental, arid);

                // Skip vegetation at or below sea level — prevents cacti/trees at the waterline
                if (height <= SEA_LEVEL) continue;
                // Skip vegetation if a ravine or tunnel opening carved away the surface
                if (isRavine(wx, height, wz, height)) continue;
                if (isTunnelCave(wx, height - 1, wz, height)) continue;

                // --- Trees (grass only, enforced minimum spacing) ---
                if (surface == BlockType.GRASS) {
                    long hash = treeHash(wx, wz);
                    if ((hash & 0x1FL) == 0 && isTreeLocalMin(wx, wz, hash)) {
                        placeTree(chunk, lx, height + 1, lz, (int)(hash >>> 5));
                    }
                }

                // --- Cactus (desert only — not beaches, not river/lake banks) ---
                if (surface == BlockType.SAND && height > SEA_LEVEL + 2
                        && arid > 0.35 && continental < 0.40
                        && lx >= 0 && lx < Chunk.SIZE_X && lz >= 0 && lz < Chunk.SIZE_Z) {
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

    private boolean isTunnelCave(double wx, int ly, double wz, int surfaceY) {
        // No tunnels in submerged terrain — same reason as isCave.
        if (surfaceY < SEA_LEVEL) return false;
        // surfaceY: surface block itself is never carved (handled by generate loop).
        // Carving at surfaceY-1 is what opens a cave mouth; the generate() loop
        // clears the surface block above it (entrance zones only, see below).
        if (ly >= surfaceY || ly <= CAVE_MIN_Y) return false;

        // Regional caviness: very slow noise splits the world into honeycombed
        // areas and nearly-solid ones, so finding a rich cave system feels like
        // a discovery instead of every hill being uniformly drilled.
        double cav = (noiseCaviness.noise(wx * 0.0025, wz * 0.0025) + 1.0) / 2.0; // 0..1
        double cavScale = 0.50 + 0.90 * cav;                                       // 0.50x..1.40x

        // Radius modulation: tunnels swell into small rooms and pinch into crawl
        // spaces along their length instead of being constant-radius pipes, and
        // widen gradually with depth so the deep network feels grander.
        double sizeN = (noiseTunnelSize.noise(wx * 0.016, wz * 0.016) + 1.0) / 2.0; // 0..1
        double radius = SPAG_RADIUS * (0.70 + 0.65 * sizeN) * cavScale;
        radius *= 1.0 + 0.30 * clamp01((45.0 - ly) / 40.0);                          // up to +30% deep

        // Surface sealing vs entrance zones: by default the radius tapers to
        // nothing over the top ENTRANCE_TAPER_DEPTH blocks, so tunnels never
        // randomly pock fields with potholes or scrape slits into cliff faces.
        // Inside a rare entrance zone the taper is cancelled and the worm meets
        // the surface at full width — a proper walkable cave mouth.
        int depthBelow = surfaceY - ly;
        if (depthBelow < ENTRANCE_TAPER_DEPTH) {
            double surfT = smoothstep(clamp01(depthBelow / (double) ENTRANCE_TAPER_DEPTH));
            double ent   = noiseEntrance.noise(wx * ENTRANCE_FREQ, wz * ENTRANCE_FREQ);
            double entT  = clamp01((ent - 0.50) / 0.22); // 0 outside zone → 1 in core
            radius *= surfT + (1.0 - surfT) * entT;
        }

        // Radius too small to carve anything — skip the tunnel noise entirely
        if (radius < 0.03) return false;

        // Spaghetti tubes: carve where BOTH 3D noises are simultaneously near
        // zero. Each zero-set is a curved 2D surface through 3D space; their
        // intersection is a continuous winding 1D curve — a genuinely round
        // tunnel once thickened by the radius. No slicing artefacts because the
        // noise itself is volumetric.
        double n1 = noiseSpag1.noise3(wx * SPAG_FREQ_H, ly * SPAG_FREQ_V, wz * SPAG_FREQ_H);
        if (Math.abs(n1) >= radius) return false; // fast-out before second noise
        double n2 = noiseSpag2.noise3(wx * SPAG_FREQ_H, ly * SPAG_FREQ_V, wz * SPAG_FREQ_H);
        return n1 * n1 + n2 * n2 < radius * radius;
    }

    /**
     * Cheese caverns: large rounded rooms deep underground (below CAVERN_TOP_Y).
     * A single 3D noise above a threshold carves naturally blobby chambers; the
     * threshold rises toward the top/bottom of the depth band so ceilings and
     * floors dome closed smoothly instead of shearing off flat.
     */
    private boolean isCavern(double wx, int ly, double wz, int surfaceY) {
        if (surfaceY < SEA_LEVEL) return false;
        if (ly > CAVERN_TOP_Y || ly <= CAVE_MIN_Y + 1) return false;

        double band = smoothstep(clamp01((ly - (CAVE_MIN_Y + 1)) / 10.0))
                    * smoothstep(clamp01((CAVERN_TOP_Y - ly) / 10.0));
        if (band < 0.05) return false;

        // Rooms cluster in cave-rich regions, like the tunnels they connect to
        double cav = (noiseCaviness.noise(wx * 0.0025, wz * 0.0025) + 1.0) / 2.0;
        double t = 0.92 - (0.32 + 0.14 * cav) * band; // mid-band: 0.60 barren → 0.46 rich

        double n = noiseCheese.noise3(wx * CHEESE_FREQ_H, ly * CHEESE_FREQ_V, wz * CHEESE_FREQ_H);
        return n > t;
    }

    private boolean isRavine(double wx, int ly, double wz, int surfaceY) {
        // No ravines in submerged terrain — underwater ravines look bizarre.
        if (surfaceY < SEA_LEVEL) return false;
        if (ly <= CAVE_MIN_Y) return false;
        int ravineBottom = surfaceY - RAVINE_MAX_DEPTH;
        // ly > surfaceY handled upstream (AIR), ly == surfaceY allowed so ravine opens sky
        if (ly < ravineBottom || ly > surfaceY) return false;

        double ridge = noiseRavine.ridged(wx, wz, 2, RAVINE_FREQ, 0.5);
        if (ridge < RAVINE_THRESHOLD) return false; // fast-out before slower noise

        // Mask: slow noise that breaks continuous ridge lines into discrete segments.
        // Only ~20% of any ridge line is actually carved — the rest is solid ground.
        // This prevents ravines from stretching infinitely across every world.
        double mask = (noiseRavineCtrl.noise(wx * 0.002, wz * 0.002) + 1.0) / 2.0;
        if (mask < 0.70) return false;

        // Size variation: sample at a different offset so it varies independently of mask.
        // ~50% of ravine segments are narrower (roughly half the width).
        double sizeNoise = (noiseRavineCtrl.noise(wx * 0.001 + 300.0, wz * 0.001 + 300.0) + 1.0) / 2.0;
        double threshold = RAVINE_THRESHOLD + (sizeNoise > 0.5 ? 0.05 : 0.0);

        // V-taper: wide at surface (depthFraction=1), narrow pinch at bottom (depthFraction=0)
        double depthFraction = (double)(ly - ravineBottom) / RAVINE_MAX_DEPTH;
        double adjustedThreshold = threshold + (1.0 - depthFraction) * (1.0 - threshold) * 0.9;

        return ridge > adjustedThreshold;
    }

    private int computeHeight(double wx, double wz) {
        double continental = noise.octaves(wx, wz, 4, 0.0022, 0.55);

        double erosion = noiseErode.octaves(wx, wz, 3, 0.004, 0.5);
        double erodeStrength = Math.max(0, (erosion + 0.15) / 1.15);

        // Continuous spline — each band meets the next at the same value
        double base;
        if (continental < -0.35) {
            double t = (continental + 1.0) / 0.65;
            base = 36 + t * 20;              // 36..56  deeper ocean floor (was 42..58)
        } else if (continental < 0.0) {
            double t = (continental + 0.35) / 0.35;
            base = 56 + smoothstep(t) * 12; // 56..68  coastal / desert (was 58..68)
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
        int height = (int) Math.max(2, Math.min(Chunk.SIZE_Y - 2, h));

        // --- Water body depressions ---
        // Only apply where terrain is currently above sea level to avoid deepening ocean floors.
        if (height > SEA_LEVEL) {
            // Rivers: grassy plains only (continental 0.0..0.22).
            // Two-noise tube: the river path is where both noises are simultaneously near zero.
            // This produces natural winding/branching paths with no straight-line artefacts.
            if (continental >= 0.0 && continental < 0.22) {
                double rn1 = noiseRiver.noise(wx * RIVER_FREQ, wz * RIVER_FREQ);
                double rn2 = noiseRiverWarp.noise(wx * RIVER_FREQ + 31.7, wz * RIVER_FREQ + 47.3);
                double dist = Math.sqrt(rn1 * rn1 + rn2 * rn2);
                if (dist < RIVER_RADIUS) {
                    // Depth varies slowly along the river so some stretches are deeper than others
                    double depthT = (noiseRiverWarp.noise((wx + 200000) * 0.0008, (wz + 200000) * 0.0008) + 1.0) / 2.0;
                    int targetH = SEA_LEVEL - RIVER_BED_MIN - (int)(depthT * (RIVER_BED_MAX - RIVER_BED_MIN));
                    double t = 1.0 - dist / RIVER_RADIUS; // 1 at center, 0 at edge
                    // Two-stage bank: gentle outer slope then steeper channel walls
                    double profile;
                    if (t < 0.40) {
                        profile = (t / 0.40) * 0.15;
                    } else {
                        double inner = (t - 0.40) / 0.60;
                        profile = 0.15 + inner * inner * (3 - 2 * inner) * 0.85;
                    }
                    height = (int)(height - profile * Math.max(0, height - targetH));
                }
            }

        }

        return height;
    }

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double clamp01(double t) {
        return Math.max(0, Math.min(1, t));
    }

    /**
     * Determines the surface block for a column.
     * Desert is driven by a separate arid noise (not just continental proximity),
     * so grassy coasts and plains can exist alongside oceans without a desert wall.
     * Submerged columns return GRAVEL as their floor material.
     */
    private BlockType surfaceBlock(int height, double continental, double arid) {
        // Submerged floor (ocean / river / lake bed) — gravel
        if (height < SEA_LEVEL) return BlockType.GRAVEL;
        // Desert: independent arid noise, only at lower elevations (not mountains)
        if (arid > 0.35 && continental < 0.40 && height > SEA_LEVEL) return BlockType.SAND;
        // Narrow beach: only 1 block above sea level (was 3) so non-arid coasts show grass
        if (height == SEA_LEVEL) return BlockType.SAND;
        if (height >= STONE_ABOVE) return BlockType.STONE;
        if (height >= SNOW_ABOVE)  return BlockType.SNOW_GRASS;
        return BlockType.GRASS;
    }

    /** True if this column is a desert (sandy, above water, driven by arid noise). */
    public boolean isDesert(double wx, double wz) {
        int height = computeHeight(wx, wz);
        if (height <= SEA_LEVEL) return false;
        double continental = noise.octaves(wx, wz, 4, 0.0022, 0.55);
        double arid = noiseArid.octaves(wx, wz, 2, 0.005, 0.5);
        return arid > 0.35 && continental < 0.40;
    }

    /** Returns the surface height at a world (wx, wz) position. */
    public int getHeight(double wx, double wz) {
        return computeHeight(wx, wz);
    }

    /** True if a tree trunk would be generated at this exact column. */
    private boolean isTreeAt(int wx, int wz) {
        int height = computeHeight(wx, wz);
        if (height <= SEA_LEVEL) return false;
        double continental = noise.octaves(wx, wz, 4, 0.0022, 0.55);
        double arid = noiseArid.octaves(wx, wz, 2, 0.005, 0.5);
        if (surfaceBlock(height, continental, arid) != BlockType.GRASS) return false;
        long hash = treeHash(wx, wz);
        return (hash & 0x1FL) == 0 && isTreeLocalMin(wx, wz, hash);
    }

    /** True if any tree within canopy radius (2 blocks) would overlap this column. */
    public boolean isNearTree(int wx, int wz) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (isTreeAt(wx + dx, wz + dz)) return true;
        return false;
    }

    /** True if this column has a cave or ravine opening at the surface (player would fall in). */
    public boolean hasSurfaceOpening(int wx, int wz) {
        int height = computeHeight(wx, wz);
        if (isRavine(wx, height, wz, height)) return true;
        return (height > SEA_LEVEL + 4) && isTunnelCave(wx, height - 1, wz, height);
    }

}
