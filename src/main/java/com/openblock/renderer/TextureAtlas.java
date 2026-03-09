package com.openblock.renderer;

import com.openblock.world.BlockType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.stb.STBImage.stbi_image_free;

/**
 * A 256x16 programmatic atlas (16 tiles of 16x16 px each, laid out in one row).
 * Tile indices:
 *   0 = grass top        (green)
 *   1 = grass side       (green top half, brown bottom)
 *   2 = dirt             (brown)
 *   3 = cobblestone      (stone visual)
 *   4 = sand             (tan)
 *   5 = bedrock          (dark gray)
 *   6 = snow grass side  (snow-capped side)
 *   7 = snow top         (white)
 */
public class TextureAtlas {
    public static final int TILE_SIZE   = 16;
    public static final int TILE_COUNT  = 16; // tiles in one row
    public static final int ATLAS_W     = TILE_SIZE * TILE_COUNT; // 256
    public static final int ATLAS_H     = TILE_SIZE;               // 16

    private final Texture texture;

    public enum Face { TOP, BOTTOM, NORTH, SOUTH, EAST, WEST }

    // Maps (BlockType, Face) → tile column index
    private static final Map<Long, Integer> TILE_MAP = new HashMap<>();

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
    }

    private static void set(BlockType bt, Face f, int col) {
        TILE_MAP.put(key(bt, f), col);
    }

    private static long key(BlockType bt, Face f) {
        return ((long) bt.ordinal() << 4) | f.ordinal();
    }

    public TextureAtlas() {
        texture = new Texture(buildAtlas(), ATLAS_W, ATLAS_H);
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

        // Tiles 3-5: real textures (fall back to procedural if missing)
        if (!blitResource(buf, 3, "/textures/cobblestone.png"))
            fillTileStone(buf, 3);
        if (!blitResource(buf, 4, "/textures/sand.png"))
            fillTile(buf, 4, 0xF2, 0xD1, 0x6E, 0xFF);
        if (!blitResource(buf, 5, "/textures/bedrock.png"))
            fillTileBedrock(buf, 5);
        // Tile 6: snow grass side, tile 7: snow top
        if (!blitResource(buf, 6, "/textures/grass_block_snow.png"))
            fillTileGrassSide(buf, 6); // fallback
        fillTileSnowTop(buf, 7);
        for (int i = 8; i < TILE_COUNT; i++)
            fillTile(buf, i, 0xFF, 0x00, 0xFF, 0xFF);

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
