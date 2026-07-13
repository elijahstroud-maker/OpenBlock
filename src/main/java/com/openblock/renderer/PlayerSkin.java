package com.openblock.renderer;

import java.util.ArrayList;

/**
 * UV mapping for the standard 64x64 Minecraft-format player skin
 * (textures/entity/player/steve.png). appendBox emits one skin-mapped box in
 * the shared 6-float vertex layout [x, y, z, u, v, light], following MC's
 * box-unwrap convention: for a body part of (w, h, d) skin pixels at texture
 * offset (tu, tv), the cross layout is top/bottom caps on the first row and
 * west/front/east/back across the second.
 *
 * The texture loader flips PNGs vertically (GL convention), so image-space
 * row r maps to GL v = 1 - r/64.
 */
final class PlayerSkin {
    static final String PATH = "/textures/entity/player/steve.png";
    private static final float TEX = 64f;

    private PlayerSkin() { }

    /**
     * One skin-mapped box. flipY maps the model's TOP end to the texture's
     * hand end — used by the first-person arm, which points fist-up while
     * the skin's arm texture is painted shoulder-up.
     */
    static void appendBox(ArrayList<Float> verts, ArrayList<Integer> idxs, int[] vi,
                          float x0, float y0, float z0, float x1, float y1, float z1,
                          int tu, int tv, int w, int h, int d, boolean flipY) {
        float[][][] faces = {
            {{x0,y1,z1},{x1,y1,z1},{x1,y1,z0},{x0,y1,z0}}, // TOP
            {{x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1}}, // BOTTOM
            {{x1,y0,z0},{x0,y0,z0},{x0,y1,z0},{x1,y1,z0}}, // NORTH (back)
            {{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}}, // SOUTH (front)
            {{x1,y0,z1},{x1,y0,z0},{x1,y1,z0},{x1,y1,z1}}, // EAST
            {{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}}, // WEST
        };
        int[][] regions = {
            {tu + d,           tv,     w, d}, // TOP cap
            {tu + d + w,       tv,     w, d}, // BOTTOM cap (hand/foot end)
            {tu + 2 * d + w,   tv + d, w, h}, // NORTH (back)
            {tu + d,           tv + d, w, h}, // SOUTH (front)
            {tu + d + w,       tv + d, d, h}, // EAST
            {tu,               tv + d, d, h}, // WEST
        };
        if (flipY) { // fist points up: the hand cap belongs on the top face
            int[] tmp = regions[0]; regions[0] = regions[1]; regions[1] = tmp;
        }
        float[] lights = {1.0f, 0.5f, 0.65f, 0.95f, 0.75f, 0.75f};

        for (int f = 0; f < 6; f++) {
            int[] r = regions[f];
            float u0   = r[0] / TEX,           u1   = (r[0] + r[2]) / TEX;
            float vTop = 1f - r[1] / TEX,      vBot = 1f - (r[1] + r[3]) / TEX;
            if (flipY && f >= 2) { float t = vTop; vTop = vBot; vBot = t; }
            float[][] uvs = {{u0, vBot}, {u1, vBot}, {u1, vTop}, {u0, vTop}};
            for (int v = 0; v < 4; v++) {
                float[] c = faces[f][v];
                verts.add(c[0]); verts.add(c[1]); verts.add(c[2]);
                verts.add(uvs[v][0]); verts.add(uvs[v][1]);
                verts.add(lights[f]);
            }
            int b = vi[0];
            idxs.add(b); idxs.add(b + 1); idxs.add(b + 2);
            idxs.add(b + 2); idxs.add(b + 3); idxs.add(b);
            vi[0] += 4;
        }
    }
}
