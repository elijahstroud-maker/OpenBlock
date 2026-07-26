package com.openblock.renderer;

import com.openblock.world.BlockType;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static org.lwjgl.stb.STBImage.*;

/**
 * Minecraft's "generated" item model: the 16x16 item texture extruded into a
 * one-pixel-thick 3D sprite. Front and back are full quads (transparent
 * texels are discarded by the shader); side walls are emitted along every
 * alpha edge so the silhouette reads as solid from any angle instead of a
 * paper-thin card. UVs map into the shared terrain atlas.
 *
 * Model space: 1x1 in XY centered on the origin, front facing +Z,
 * thickness 1/16.
 */
final class ItemSpriteMesh {
    private static final int   PX    = 16;         // texture grid
    private static final float HALF_T = 0.5f / PX; // half thickness

    private ItemSpriteMesh() { }

    /**
     * Source PNG for an item type (alpha drives the extrusion). Every item
     * texture lives at /textures/item/&lt;enum name lowercased&gt;.png — the
     * same rule TextureAtlas uses — so there's no per-type table to drift.
     */
    private static String texturePath(BlockType type) {
        return type.item ? "/textures/item/" + type.name().toLowerCase() + ".png" : null;
    }

    static Mesh build(TextureAtlas atlas, BlockType type) {
        boolean[][] solid = loadAlphaGrid(texturePath(type));
        float[] uv = atlas.getUV(type, TextureAtlas.Face.TOP); // [u0, v0(top), u1, v1(bottom)]

        ArrayList<Float> verts = new ArrayList<>();
        ArrayList<Integer> idxs = new ArrayList<>();
        int[] vi = {0};

        // Front (+Z) and back (-Z): one full quad each; alpha-discard trims them
        quad(verts, idxs, vi,
            -0.5f, -0.5f,  HALF_T, uv[0], uv[3],
             0.5f, -0.5f,  HALF_T, uv[2], uv[3],
             0.5f,  0.5f,  HALF_T, uv[2], uv[1],
            -0.5f,  0.5f,  HALF_T, uv[0], uv[1], 1.0f);
        quad(verts, idxs, vi,
             0.5f, -0.5f, -HALF_T, uv[2], uv[3],
            -0.5f, -0.5f, -HALF_T, uv[0], uv[3],
            -0.5f,  0.5f, -HALF_T, uv[0], uv[1],
             0.5f,  0.5f, -HALF_T, uv[2], uv[1], 0.9f);

        // Side walls along every alpha edge (image row 0 = texture top)
        if (solid != null) {
            for (int py = 0; py < PX; py++) {
                for (int px = 0; px < PX; px++) {
                    if (!solid[py][px]) continue;
                    float x0 = -0.5f + px / (float) PX,       x1 = x0 + 1f / PX;
                    float y1 =  0.5f - py / (float) PX;       // pixel top
                    float y0 =  y1 - 1f / PX;                 // pixel bottom
                    float cu = uv[0] + (px + 0.5f) / PX * (uv[2] - uv[0]);
                    float cv = uv[1] + (py + 0.5f) / PX * (uv[3] - uv[1]);
                    if (py == 0      || !solid[py - 1][px])   // top edge
                        wall(verts, idxs, vi, x0, y1, x1, y1, cu, cv, 1.0f);
                    if (py == PX - 1 || !solid[py + 1][px])   // bottom edge
                        wall(verts, idxs, vi, x0, y0, x1, y0, cu, cv, 0.6f);
                    if (px == 0      || !solid[py][px - 1])   // left edge
                        wall(verts, idxs, vi, x0, y0, x0, y1, cu, cv, 0.75f);
                    if (px == PX - 1 || !solid[py][px + 1])   // right edge
                        wall(verts, idxs, vi, x1, y0, x1, y1, cu, cv, 0.75f);
                }
            }
        }

        float[] va = new float[verts.size()];
        for (int i = 0; i < va.length; i++) va[i] = verts.get(i);
        int[] ia = new int[idxs.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = idxs.get(i);
        Mesh m = new Mesh();
        m.upload(va, ia);
        return m;
    }

    /** A z-spanning wall between two XY points, textured with one texel. */
    private static void wall(ArrayList<Float> verts, ArrayList<Integer> idxs, int[] vi,
                             float ax, float ay, float bx, float by,
                             float u, float v, float light) {
        quad(verts, idxs, vi,
            ax, ay, -HALF_T, u, v,
            bx, by, -HALF_T, u, v,
            bx, by,  HALF_T, u, v,
            ax, ay,  HALF_T, u, v, light);
    }

    private static void quad(ArrayList<Float> verts, ArrayList<Integer> idxs, int[] vi,
                             float x0, float y0, float z0, float u0, float v0,
                             float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2,
                             float x3, float y3, float z3, float u3, float v3,
                             float light) {
        float[][] p = {{x0,y0,z0,u0,v0},{x1,y1,z1,u1,v1},{x2,y2,z2,u2,v2},{x3,y3,z3,u3,v3}};
        for (float[] c : p) {
            verts.add(c[0]); verts.add(c[1]); verts.add(c[2]);
            verts.add(c[3]); verts.add(c[4]); verts.add(light);
        }
        int b = vi[0];
        idxs.add(b); idxs.add(b + 1); idxs.add(b + 2);
        idxs.add(b + 2); idxs.add(b + 3); idxs.add(b);
        vi[0] += 4;
    }

    /** 16x16 grid of "pixel is opaque" from the item PNG (null if unreadable). */
    private static boolean[][] loadAlphaGrid(String path) {
        if (path == null) return null;
        byte[] bytes;
        try (InputStream is = ItemSpriteMesh.class.getResourceAsStream(path)) {
            if (is == null) return null;
            bytes = is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
        ByteBuffer raw = MemoryUtil.memAlloc(bytes.length);
        raw.put(bytes).flip();
        int[] w = {0}, h = {0}, ch = {0};
        stbi_set_flip_vertically_on_load(false); // row 0 = image top
        ByteBuffer pixels = stbi_load_from_memory(raw, w, h, ch, 4);
        MemoryUtil.memFree(raw);
        if (pixels == null) return null;

        boolean[][] grid = new boolean[PX][PX];
        for (int py = 0; py < PX; py++) {
            int sy = py * h[0] / PX;
            for (int px = 0; px < PX; px++) {
                int sx = px * w[0] / PX;
                grid[py][px] = (pixels.get((sy * w[0] + sx) * 4 + 3) & 0xFF) > 25;
            }
        }
        stbi_image_free(pixels);
        return grid;
    }
}
