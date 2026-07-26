package com.openblock.renderer;

import com.openblock.world.BlockType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.stb.STBImage.stbi_image_free;

/**
 * A 256x16 programmatic atlas (16 tiles of 16x16 px each, laid out in one row).
 * Tile indices:
 *   0 = grass top        (green)
 *   1 = grass side       (green top half, brown bottom)
 *   2 = dirt             (brown)
 *   3 = stone            (stone visual; cobblestone becomes the mined drop later)
 *   4 = sand             (tan)
 *   5 = bedrock          (dark gray)
 *   6 = snow grass side  (snow-capped side)
 *   7 = snow top         (white)
 */
public class TextureAtlas {
    public static final int TILE_SIZE   = 16;
    public static final int TILE_COUNT  = 66; // tiles in one row
    public static final int ATLAS_W     = TILE_SIZE * TILE_COUNT; // 1056
    public static final int ATLAS_H     = TILE_SIZE;               // 16

    private final Texture texture;

    // Water animation — frames precomputed at load time, uploaded each tick via glTexSubImage2D
    private byte[] waterStillFrames;
    private int    waterStillFrameCount;
    private byte[] waterFlowFrames;
    private int    waterFlowFrameCount;
    /** Normalized position in the still-water animation cycle [0, 1). */
    private float waterStillAnimNorm = 0f;
    /** Normalized position in the flowing-water animation cycle [0, 1). */
    private float waterFlowAnimNorm  = 0f;
    private static final float WATER_STILL_CYCLE = 8.0f;  // still water: slow, subtle ripples
    private static final float WATER_FLOW_CYCLE  = 2.5f;  // flowing water: faster current

    public enum Face { TOP, BOTTOM, NORTH, SOUTH, EAST, WEST }

    // Maps (BlockType, Face) → tile column index
    private static final Map<Long, Integer> TILE_MAP = new HashMap<>();

    /** Tool tiles 46-65. Atlas column = TOOL_TILE_BASE + index; the texture
     *  is /textures/item/&lt;enum name lowercased&gt;.png. */
    private static final int TOOL_TILE_BASE = 46;
    private static final BlockType[] TOOL_TILES = {
        BlockType.WOODEN_PICKAXE,  BlockType.WOODEN_AXE,  BlockType.WOODEN_SWORD,  BlockType.WOODEN_HOE,
        BlockType.STONE_PICKAXE,   BlockType.STONE_AXE,   BlockType.STONE_SWORD,   BlockType.STONE_HOE,
        BlockType.IRON_PICKAXE,    BlockType.IRON_AXE,    BlockType.IRON_SWORD,    BlockType.IRON_HOE,
        BlockType.GOLDEN_PICKAXE,  BlockType.GOLDEN_AXE,  BlockType.GOLDEN_SWORD,  BlockType.GOLDEN_HOE,
        BlockType.DIAMOND_PICKAXE, BlockType.DIAMOND_AXE, BlockType.DIAMOND_SWORD, BlockType.DIAMOND_HOE,
    };

    static {
        // Face ordinals: TOP=0 BOTTOM=1 NORTH=2 SOUTH=3 EAST=4 WEST=5
        set(BlockType.GRASS, Face.TOP,    0);
        set(BlockType.GRASS, Face.BOTTOM, 2);
        set(BlockType.GRASS, Face.NORTH,  1);
        set(BlockType.GRASS, Face.SOUTH,  1);
        set(BlockType.GRASS, Face.EAST,   1);
        set(BlockType.GRASS, Face.WEST,   1);

        for (Face f : Face.values()) set(BlockType.DIRT,    f, 2);
        for (Face f : Face.values()) set(BlockType.STONE,   f, 3);
        for (Face f : Face.values()) set(BlockType.SAND,    f, 4);
        for (Face f : Face.values()) set(BlockType.BEDROCK, f, 5);

        set(BlockType.SNOW_GRASS, Face.TOP,    7);
        set(BlockType.SNOW_GRASS, Face.BOTTOM, 2);
        set(BlockType.SNOW_GRASS, Face.NORTH,  6);
        set(BlockType.SNOW_GRASS, Face.SOUTH,  6);
        set(BlockType.SNOW_GRASS, Face.EAST,   6);
        set(BlockType.SNOW_GRASS, Face.WEST,   6);

        set(BlockType.LOG, Face.TOP,    9);
        set(BlockType.LOG, Face.BOTTOM, 9);
        set(BlockType.LOG, Face.NORTH,  8);
        set(BlockType.LOG, Face.SOUTH,  8);
        set(BlockType.LOG, Face.EAST,   8);
        set(BlockType.LOG, Face.WEST,   8);

        for (Face f : Face.values()) set(BlockType.LEAVES, f, 10);

        set(BlockType.CACTUS, Face.TOP,    12);
        set(BlockType.CACTUS, Face.BOTTOM, 13);
        set(BlockType.CACTUS, Face.NORTH,  11);
        set(BlockType.CACTUS, Face.SOUTH,  11);
        set(BlockType.CACTUS, Face.EAST,   11);
        set(BlockType.CACTUS, Face.WEST,   11);

        for (Face f : Face.values()) set(BlockType.WATER, f, 14);
        // Flowing water: top surface uses still texture (horizontal, no direction issue)
        //               sides use flow texture (vertical animated current)
        set(BlockType.WATER_FLOWING, Face.TOP,    14);
        set(BlockType.WATER_FLOWING, Face.BOTTOM, 16);
        set(BlockType.WATER_FLOWING, Face.NORTH,  16);
        set(BlockType.WATER_FLOWING, Face.SOUTH,  16);
        set(BlockType.WATER_FLOWING, Face.EAST,   16);
        set(BlockType.WATER_FLOWING, Face.WEST,   16);
        for (Face f : Face.values()) set(BlockType.GRAVEL, f, 15);
        for (Face f : Face.values()) set(BlockType.COBBLESTONE, f, 17);

        for (Face f : Face.values()) set(BlockType.PLANKS, f, 19);
        set(BlockType.CRAFTING_TABLE, Face.TOP,    20);
        set(BlockType.CRAFTING_TABLE, Face.BOTTOM, 19); // planks underneath, like MC
        set(BlockType.CRAFTING_TABLE, Face.NORTH,  22); // front (tools graphic)
        set(BlockType.CRAFTING_TABLE, Face.SOUTH,  22);
        set(BlockType.CRAFTING_TABLE, Face.EAST,   21); // side
        set(BlockType.CRAFTING_TABLE, Face.WEST,   21);
        for (Face f : Face.values()) set(BlockType.STICK, f, 23); // flat item sprite

        // Ores (tiles 24-31) + their drop items (tiles 32-36)
        for (Face f : Face.values()) set(BlockType.COAL_ORE,     f, 24);
        for (Face f : Face.values()) set(BlockType.IRON_ORE,     f, 25);
        for (Face f : Face.values()) set(BlockType.COPPER_ORE,   f, 26);
        for (Face f : Face.values()) set(BlockType.GOLD_ORE,     f, 27);
        for (Face f : Face.values()) set(BlockType.LAPIS_ORE,    f, 28);
        for (Face f : Face.values()) set(BlockType.REDSTONE_ORE, f, 29);
        for (Face f : Face.values()) set(BlockType.DIAMOND_ORE,  f, 30);
        for (Face f : Face.values()) set(BlockType.EMERALD_ORE,  f, 31);
        for (Face f : Face.values()) set(BlockType.COAL,         f, 32);
        for (Face f : Face.values()) set(BlockType.LAPIS_LAZULI, f, 33);
        for (Face f : Face.values()) set(BlockType.REDSTONE,     f, 34);
        for (Face f : Face.values()) set(BlockType.DIAMOND,      f, 35);
        for (Face f : Face.values()) set(BlockType.EMERALD,      f, 36);

        // Furnace (tiles 37-40): unlit front, side, top, lit front
        set(BlockType.FURNACE, Face.TOP,    39);
        set(BlockType.FURNACE, Face.BOTTOM, 39);
        set(BlockType.FURNACE, Face.NORTH,  37); // front
        set(BlockType.FURNACE, Face.SOUTH,  37);
        set(BlockType.FURNACE, Face.EAST,   38); // side
        set(BlockType.FURNACE, Face.WEST,   38);
        set(BlockType.FURNACE_LIT, Face.TOP,    39);
        set(BlockType.FURNACE_LIT, Face.BOTTOM, 39);
        set(BlockType.FURNACE_LIT, Face.NORTH,  40); // glowing front
        set(BlockType.FURNACE_LIT, Face.SOUTH,  40);
        set(BlockType.FURNACE_LIT, Face.EAST,   38);
        set(BlockType.FURNACE_LIT, Face.WEST,   38);
        for (Face f : Face.values()) set(BlockType.GLASS,        f, 41);
        for (Face f : Face.values()) set(BlockType.IRON_INGOT,   f, 42);
        for (Face f : Face.values()) set(BlockType.GOLD_INGOT,   f, 43);
        for (Face f : Face.values()) set(BlockType.COPPER_INGOT, f, 44);
        for (Face f : Face.values()) set(BlockType.CHARCOAL,     f, 45);

        // Tools: one column each starting at TOOL_TILE_BASE, in TOOL_TILES
        // order — the same array buildAtlas blits from, so the mapping and
        // the pixels can never drift apart.
        for (int i = 0; i < TOOL_TILES.length; i++)
            for (Face f : Face.values()) set(TOOL_TILES[i], f, TOOL_TILE_BASE + i);
    }

    private static void set(BlockType bt, Face f, int col) {
        TILE_MAP.put(key(bt, f), col);
    }

    private static long key(BlockType bt, Face f) {
        return ((long) bt.ordinal() << 4) | f.ordinal();
    }

    public TextureAtlas() {
        texture = new Texture(buildAtlas(), ATLAS_W, ATLAS_H);
        int[] count = {0};
        waterStillFrames     = loadAnimFrames("/textures/water_still.png", 0x3F, 0x76, 0xE4, count);
        waterStillFrameCount = count[0];
        waterFlowFrames      = loadAnimFrames("/textures/water_flow.png",  0x3F, 0x76, 0xE4, count);
        waterFlowFrameCount  = count[0];
    }

    /** Advances the water animation and uploads the current frame to the GPU. */
    public void update(float delta) {
        // Wrap with floor(), not a single -=1: delta is raw wall-clock time between
        // frames, and one long stall (GC pause, alt-tab, busy machine) can push the
        // norm past 2.0 — a lone subtraction then leaves it >= 1.0 and the frame
        // index runs off the end of the animation array (crash).
        waterStillAnimNorm += delta / WATER_STILL_CYCLE;
        waterStillAnimNorm -= (float) Math.floor(waterStillAnimNorm);
        waterFlowAnimNorm  += delta / WATER_FLOW_CYCLE;
        waterFlowAnimNorm  -= (float) Math.floor(waterFlowAnimNorm);
        if (waterStillFrames != null)
            texture.updateTile(14, TILE_SIZE, waterStillFrames,
                Math.min((int)(waterStillAnimNorm * waterStillFrameCount), waterStillFrameCount - 1));
        if (waterFlowFrames != null)
            texture.updateTile(16, TILE_SIZE, waterFlowFrames,
                Math.min((int)(waterFlowAnimNorm  * waterFlowFrameCount),  waterFlowFrameCount - 1));
    }

    /**
     * Returns [u0, v0, u1, v1] in [0,1] for the given block face.
     * v coordinates are flipped because STB flips vertically on load,
     * but since we build the atlas ourselves without STB, keep it straightforward.
     */
    public float[] getUV(BlockType type, Face face) {
        int col = TILE_MAP.getOrDefault(key(type, face), 0);
        float tileW = 1.0f / TILE_COUNT;
        // Atlas is 1 row tall, so v spans [0,1]
        float u0 = col * tileW;
        float u1 = u0 + tileW;
        // Flip V because OpenGL's (0,0) is bottom-left but we painted top-down
        float v0 = 1.0f;
        float v1 = 0.0f;
        return new float[]{u0, v0, u1, v1};
    }

    public void bind(int unit) {
        texture.bind(unit);
    }

    public void cleanup() {
        texture.cleanup();
    }

    // ---------- atlas generation ----------

    private static ByteBuffer buildAtlas() {
        ByteBuffer buf = MemoryUtil.memAlloc(ATLAS_W * ATLAS_H * 4);

        // Tiles 0-2: real textures (fall back to procedural if missing)
        if (!blitResource(buf, 0, "/textures/grass_block_top.png"))
            fillTile(buf, 0, 0x5A, 0xA0, 0x2E, 0xFF);
        if (!blitResource(buf, 1, "/textures/grass_block_side.png"))
            fillTileGrassSide(buf, 1);
        if (!blitResource(buf, 2, "/textures/dirt.png"))
            fillTile(buf, 2, 0x8B, 0x45, 0x13, 0xFF);

        // Tiles 3-5: real textures (fall back to procedural if missing).
        // Stone uses the real stone texture now — cobblestone.png stays on the
        // classpath for the mined-stone drop when block drops arrive.
        if (!blitResource(buf, 3, "/textures/stone.png"))
            fillTileStone(buf, 3);
        if (!blitResource(buf, 4, "/textures/sand.png"))
            fillTile(buf, 4, 0xF2, 0xD1, 0x6E, 0xFF);
        if (!blitResource(buf, 5, "/textures/bedrock.png"))
            fillTileBedrock(buf, 5);
        // Tile 6: snow grass side, tile 7: snow top
        if (!blitResource(buf, 6, "/textures/grass_block_snow.png"))
            fillTileGrassSide(buf, 6); // fallback
        fillTileSnowTop(buf, 7);
        // Tile 8: log side, tile 9: log top, tile 10: leaves
        if (!blitResource(buf, 8, "/textures/log.png"))
            fillTile(buf, 8, 0x6B, 0x43, 0x1D, 0xFF);
        if (!blitResource(buf, 9, "/textures/log_top.png"))
            fillTile(buf, 9, 0x5C, 0x39, 0x17, 0xFF);
        if (!blitResource(buf, 10, "/textures/leaves.png"))
            fillTile(buf, 10, 0x2D, 0x6A, 0x0F, 0xCC);
        // Tile 11: cactus side, tile 12: cactus top, tile 13: cactus bottom
        // Cactus PNGs are designed for an inset model and have transparent corners.
        // Force alpha=255 after blitting so the full block face stays opaque.
        if (!blitResource(buf, 11, "/textures/cactus_side.png"))
            fillTile(buf, 11, 0x4A, 0x7C, 0x1E, 0xFF);
        forceTileOpaque(buf, 11);
        if (!blitResource(buf, 12, "/textures/cactus_top.png"))
            fillTile(buf, 12, 0x3D, 0x6B, 0x18, 0xFF);
        forceTileOpaque(buf, 12);
        if (!blitResource(buf, 13, "/textures/cactus_bottom.png"))
            fillTile(buf, 13, 0x3D, 0x6B, 0x18, 0xFF);
        forceTileOpaque(buf, 13);
        // Tile 14: source water (water_still first frame, tinted with temperate biome water color)
        if (!blitFirstFrame(buf, 14, "/textures/water_still.png"))
            fillTileWater(buf, 14);
        else
            tintTile(buf, 14, 0x3F, 0x76, 0xE4); // Minecraft temperate water color
        setTileAlpha(buf, 14, 0xB4);
        // Tile 15: gravel
        if (!blitResource(buf, 15, "/textures/gravel.png"))
            fillTileGravel(buf, 15);
        // Tile 16: flowing water (water_flow first frame, same tint)
        if (!blitFirstFrame(buf, 16, "/textures/water_flow.png"))
            fillTileWater(buf, 16);
        else
            tintTile(buf, 16, 0x3F, 0x76, 0xE4);
        setTileAlpha(buf, 16, 0xB4);
        // Tile 17: cobblestone (the mined-stone drop)
        if (!blitResource(buf, 17, "/textures/cobblestone.png"))
            fillTileStone(buf, 17);
        // Tile 18: magenta fallback
        fillTile(buf, 18, 0xFF, 0x00, 0xFF, 0xFF);
        // Tiles 19-22: planks + crafting table
        if (!blitResource(buf, 19, "/textures/oak_planks.png"))
            fillTile(buf, 19, 0xA0, 0x81, 0x4B, 0xFF);
        if (!blitResource(buf, 20, "/textures/crafting_table_top.png"))
            fillTile(buf, 20, 0x9E, 0x7A, 0x47, 0xFF);
        if (!blitResource(buf, 21, "/textures/crafting_table_side.png"))
            fillTile(buf, 21, 0x8B, 0x6C, 0x40, 0xFF);
        if (!blitResource(buf, 22, "/textures/crafting_table_front.png"))
            fillTile(buf, 22, 0x8B, 0x6C, 0x40, 0xFF);
        // Tile 23: stick (item sprite — transparency matters, don't force opaque)
        if (!blitResource(buf, 23, "/textures/item/stick.png"))
            fillTile(buf, 23, 0x6B, 0x43, 0x1D, 0xFF);
        // Tiles 24-31: ore blocks
        String[] ores = {"coal", "iron", "copper", "gold", "lapis", "redstone", "diamond", "emerald"};
        for (int i = 0; i < ores.length; i++) {
            if (!blitResource(buf, 24 + i, "/textures/" + ores[i] + "_ore.png"))
                fillTileStone(buf, 24 + i);
        }
        // Tiles 32-36: ore drop items (sprites, keep transparency)
        String[] drops = {"coal", "lapis_lazuli", "redstone", "diamond", "emerald"};
        for (int i = 0; i < drops.length; i++) {
            if (!blitResource(buf, 32 + i, "/textures/item/" + drops[i] + ".png"))
                fillTile(buf, 32 + i, 0xFF, 0x00, 0xFF, 0xFF);
        }
        // Tiles 37-40: furnace (front, side, top, lit front); tile 41: glass
        String[] furn = {"furnace_front", "furnace_side", "furnace_top", "furnace_front_on"};
        for (int i = 0; i < furn.length; i++) {
            if (!blitResource(buf, 37 + i, "/textures/" + furn[i] + ".png"))
                fillTileStone(buf, 37 + i);
        }
        if (!blitResource(buf, 41, "/textures/glass.png"))
            fillTile(buf, 41, 0xC0, 0xE8, 0xF0, 0x60);
        // Tiles 42-44: smelted ingots (sprites, keep transparency)
        String[] ingots = {"iron_ingot", "gold_ingot", "copper_ingot"};
        for (int i = 0; i < ingots.length; i++) {
            if (!blitResource(buf, 42 + i, "/textures/item/" + ingots[i] + ".png"))
                fillTile(buf, 42 + i, 0xFF, 0x00, 0xFF, 0xFF);
        }
        // Tile 45: charcoal
        if (!blitResource(buf, 45, "/textures/item/charcoal.png"))
            fillTile(buf, 45, 0x33, 0x28, 0x20, 0xFF);
        // Tiles 46-65: tools, straight from the TOOL_TILES mapping order
        for (int i = 0; i < TOOL_TILES.length; i++) {
            String path = "/textures/item/" + TOOL_TILES[i].name().toLowerCase() + ".png";
            if (!blitResource(buf, TOOL_TILE_BASE + i, path))
                fillTile(buf, TOOL_TILE_BASE + i, 0xFF, 0x00, 0xFF, 0xFF);
        }

        buf.flip();
        return buf;
    }

    /**
     * Loads a PNG from the classpath and blits it (nearest-neighbour scaled to TILE_SIZE×TILE_SIZE)
     * into atlas column {@code tileCol}. Returns true on success.
     * Loads with stbi_flip=true so the texture's visual top ends up at high-v (top of block face).
     */
    private static boolean blitResource(ByteBuffer buf, int tileCol, String path) {
        byte[] bytes;
        try (InputStream is = TextureAtlas.class.getResourceAsStream(path)) {
            if (is == null) return false;
            bytes = is.readAllBytes();
        } catch (IOException e) {
            return false;
        }

        ByteBuffer raw = MemoryUtil.memAlloc(bytes.length);
        raw.put(bytes).flip();

        int[] w = {0}, h = {0}, ch = {0};
        stbi_set_flip_vertically_on_load(true);
        ByteBuffer pixels = stbi_load_from_memory(raw, w, h, ch, 4);
        stbi_set_flip_vertically_on_load(false);
        MemoryUtil.memFree(raw);

        if (pixels == null) return false;

        int srcW = w[0], srcH = h[0];
        int xStart = tileCol * TILE_SIZE;
        for (int ty = 0; ty < TILE_SIZE; ty++) {
            int sy = ty * srcH / TILE_SIZE;
            for (int tx = 0; tx < TILE_SIZE; tx++) {
                int sx  = tx * srcW / TILE_SIZE;
                int src = (sy * srcW + sx) * 4;
                int dst = (ty * ATLAS_W + xStart + tx) * 4;
                buf.put(dst,     pixels.get(src));
                buf.put(dst + 1, pixels.get(src + 1));
                buf.put(dst + 2, pixels.get(src + 2));
                buf.put(dst + 3, pixels.get(src + 3));
            }
        }

        stbi_image_free(pixels);
        return true;
    }

    /**
     * Like blitResource but treats the PNG as an animation strip: only reads the first
     * srcW rows (one square frame) and ignores the rest of the tall image.
     */
    private static boolean blitFirstFrame(ByteBuffer buf, int tileCol, String path) {
        byte[] bytes;
        try (InputStream is = TextureAtlas.class.getResourceAsStream(path)) {
            if (is == null) return false;
            bytes = is.readAllBytes();
        } catch (IOException e) {
            return false;
        }

        ByteBuffer raw = MemoryUtil.memAlloc(bytes.length);
        raw.put(bytes).flip();

        int[] w = {0}, h = {0}, ch = {0};
        stbi_set_flip_vertically_on_load(false); // don't flip — we read from top (frame 0)
        ByteBuffer pixels = stbi_load_from_memory(raw, w, h, ch, 4);
        stbi_set_flip_vertically_on_load(false);
        MemoryUtil.memFree(raw);

        if (pixels == null) return false;

        int srcW = w[0];
        // Only read the first square frame (top srcW rows of the strip)
        int frameH = srcW;
        int xStart = tileCol * TILE_SIZE;
        for (int ty = 0; ty < TILE_SIZE; ty++) {
            // Sample from frame 0, flip vertically so top of frame = top of block face
            int sy = (TILE_SIZE - 1 - ty) * frameH / TILE_SIZE;
            for (int tx = 0; tx < TILE_SIZE; tx++) {
                int sx  = tx * srcW / TILE_SIZE;
                int src = (sy * srcW + sx) * 4;
                int dst = (ty * ATLAS_W + xStart + tx) * 4;
                buf.put(dst,     pixels.get(src));
                buf.put(dst + 1, pixels.get(src + 1));
                buf.put(dst + 2, pixels.get(src + 2));
                buf.put(dst + 3, pixels.get(src + 3));
            }
        }

        stbi_image_free(pixels);
        return true;
    }

    /**
     * Loads a PNG animation strip and returns all frames pre-scaled to TILE_SIZE×TILE_SIZE,
     * tinted, and with alpha forced to 0xB4. Each frame occupies TILE_SIZE*TILE_SIZE*4 bytes.
     * outFrameCount[0] is set to the number of frames decoded.
     */
    private static byte[] loadAnimFrames(String path, int tr, int tg, int tb, int[] outFrameCount) {
        byte[] bytes;
        try (InputStream is = TextureAtlas.class.getResourceAsStream(path)) {
            if (is == null) { outFrameCount[0] = 0; return null; }
            bytes = is.readAllBytes();
        } catch (IOException e) {
            outFrameCount[0] = 0;
            return null;
        }

        ByteBuffer raw = MemoryUtil.memAlloc(bytes.length);
        raw.put(bytes).flip();

        int[] w = {0}, h = {0}, ch = {0};
        stbi_set_flip_vertically_on_load(false);
        ByteBuffer pixels = stbi_load_from_memory(raw, w, h, ch, 4);
        MemoryUtil.memFree(raw);

        if (pixels == null) { outFrameCount[0] = 0; return null; }

        int srcW      = w[0];
        int frameH    = srcW; // animation strip: each frame is srcW × srcW pixels
        int totalFrames = h[0] / frameH;
        // Sample at most 32 frames evenly spaced across the full strip so that the
        // full animation cycle is represented even when the strip has 1500+ smooth frames.
        int numFrames = Math.min(totalFrames, 32);
        outFrameCount[0] = numFrames;

        int frameBytes = TILE_SIZE * TILE_SIZE * 4;
        byte[] result  = new byte[numFrames * frameBytes];

        for (int f = 0; f < numFrames; f++) {
            int srcFrame = (int)((float) f / numFrames * totalFrames);
            int frameStartRow = srcFrame * frameH;
            int base = f * frameBytes;
            for (int ty = 0; ty < TILE_SIZE; ty++) {
                // flip vertically within frame: image-top → block-top
                int sy = frameStartRow + (TILE_SIZE - 1 - ty) * frameH / TILE_SIZE;
                for (int tx = 0; tx < TILE_SIZE; tx++) {
                    int sx  = tx * srcW / TILE_SIZE;
                    int src = (sy * srcW + sx) * 4;
                    int dst = base + (ty * TILE_SIZE + tx) * 4;
                    result[dst]     = (byte) ((pixels.get(src)     & 0xFF) * tr / 255);
                    result[dst + 1] = (byte) ((pixels.get(src + 1) & 0xFF) * tg / 255);
                    result[dst + 2] = (byte) ((pixels.get(src + 2) & 0xFF) * tb / 255);
                    result[dst + 3] = (byte) 0xB4;
                }
            }
        }

        stbi_image_free(pixels);
        return result;
    }

    /** Multiplies each pixel's RGB in the tile by the given tint color (component-wise). */
    private static void tintTile(ByteBuffer buf, int col, int tr, int tg, int tb) {
        int xStart = col * TILE_SIZE;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = xStart; px < xStart + TILE_SIZE; px++) {
                int idx = (py * ATLAS_W + px) * 4;
                buf.put(idx,     (byte) ((buf.get(idx)     & 0xFF) * tr / 255));
                buf.put(idx + 1, (byte) ((buf.get(idx + 1) & 0xFF) * tg / 255));
                buf.put(idx + 2, (byte) ((buf.get(idx + 2) & 0xFF) * tb / 255));
            }
        }
    }

    /** Overrides the alpha channel for every pixel in a tile to the given value. */
    private static void setTileAlpha(ByteBuffer buf, int col, int alpha) {
        int xStart = col * TILE_SIZE;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = xStart; px < xStart + TILE_SIZE; px++) {
                buf.put((py * ATLAS_W + px) * 4 + 3, (byte) alpha);
            }
        }
    }

    /** Sets alpha=255 for every pixel in the tile, leaving RGB untouched. */
    private static void forceTileOpaque(ByteBuffer buf, int col) {
        int xStart = col * TILE_SIZE;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = xStart; px < xStart + TILE_SIZE; px++) {
                buf.put((py * ATLAS_W + px) * 4 + 3, (byte) 0xFF);
            }
        }
    }

    private static void fillTile(ByteBuffer buf, int col, int r, int g, int b, int a) {
        int xStart = col * TILE_SIZE;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = xStart; px < xStart + TILE_SIZE; px++) {
                int idx = (py * ATLAS_W + px) * 4;
                buf.put(idx,     (byte) r);
                buf.put(idx + 1, (byte) g);
                buf.put(idx + 2, (byte) b);
                buf.put(idx + 3, (byte) a);
            }
        }
    }

    private static void fillTileGrassSide(ByteBuffer buf, int col) {
        int xStart = col * TILE_SIZE;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = xStart; px < xStart + TILE_SIZE; px++) {
                int idx = (py * ATLAS_W + px) * 4;
                if (py >= TILE_SIZE - 3) {
                    // green strip — these rows land at OpenGL top (v=1), visible at block top
                    buf.put(idx,     (byte) 0x5A);
                    buf.put(idx + 1, (byte) 0xA0);
                    buf.put(idx + 2, (byte) 0x2E);
                } else {
                    // dirt below
                    buf.put(idx,     (byte) 0x8B);
                    buf.put(idx + 1, (byte) 0x45);
                    buf.put(idx + 2, (byte) 0x13);
                }
                buf.put(idx + 3, (byte) 0xFF);
            }
        }
    }

    private static void fillTileStone(ByteBuffer buf, int col) {
        // Stone: gray base with slightly darker spots
        int xStart = col * TILE_SIZE;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = xStart; px < xStart + TILE_SIZE; px++) {
                int lx = px - xStart;
                boolean spot = ((lx + py) % 5 == 0) || ((lx * py) % 7 == 0);
                int v = spot ? 0x88 : 0xA0;
                int idx = (py * ATLAS_W + px) * 4;
                buf.put(idx,     (byte) v);
                buf.put(idx + 1, (byte) v);
                buf.put(idx + 2, (byte) v);
                buf.put(idx + 3, (byte) 0xFF);
            }
        }
    }

    private static void fillTileSnowTop(ByteBuffer buf, int col) {
        int xStart = col * TILE_SIZE;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = xStart; px < xStart + TILE_SIZE; px++) {
                int lx = px - xStart;
                // slight blue-white variation for snow texture
                boolean speckle = ((lx * 3 + py * 7) % 9 == 0);
                int v = speckle ? 0xE0 : 0xF8;
                int idx = (py * ATLAS_W + px) * 4;
                buf.put(idx,     (byte) v);
                buf.put(idx + 1, (byte) v);
                buf.put(idx + 2, (byte) 0xFF);
                buf.put(idx + 3, (byte) 0xFF);
            }
        }
    }

    private static void fillTileWater(ByteBuffer buf, int col) {
        int xStart = col * TILE_SIZE;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = xStart; px < xStart + TILE_SIZE; px++) {
                int lx = px - xStart;
                boolean ripple = ((lx * 3 + py * 5) % 7 == 0);
                int r = ripple ? 0x30 : 0x3F;
                int g = ripple ? 0x6E : 0x76;
                int b = ripple ? 0xD4 : 0xE4;
                int idx = (py * ATLAS_W + px) * 4;
                buf.put(idx,     (byte) r);
                buf.put(idx + 1, (byte) g);
                buf.put(idx + 2, (byte) b);
                buf.put(idx + 3, (byte) 0xB4); // ~71% opacity
            }
        }
    }

    private static void fillTileGravel(ByteBuffer buf, int col) {
        int xStart = col * TILE_SIZE;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = xStart; px < xStart + TILE_SIZE; px++) {
                int lx = px - xStart;
                boolean dark = ((lx * 5 + py * 3) % 7 < 2) || ((lx + py * 7) % 11 < 3);
                int v = dark ? 0x78 : 0xA8;
                int idx = (py * ATLAS_W + px) * 4;
                buf.put(idx,     (byte) v);
                buf.put(idx + 1, (byte)(v - 5));
                buf.put(idx + 2, (byte)(v - 10));
                buf.put(idx + 3, (byte) 0xFF);
            }
        }
    }

    private static void fillTileBedrock(ByteBuffer buf, int col) {
        int xStart = col * TILE_SIZE;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = xStart; px < xStart + TILE_SIZE; px++) {
                int lx = px - xStart;
                boolean spot = ((lx * 3 + py * 7) % 11 < 3);
                int v = spot ? 0x20 : 0x44;
                int idx = (py * ATLAS_W + px) * 4;
                buf.put(idx,     (byte) v);
                buf.put(idx + 1, (byte) v);
                buf.put(idx + 2, (byte) v);
                buf.put(idx + 3, (byte) 0xFF);
            }
        }
    }
}
