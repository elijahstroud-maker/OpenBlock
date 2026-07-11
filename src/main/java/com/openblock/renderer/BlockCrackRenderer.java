package com.openblock.renderer;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.stb.STBImage.*;

/**
 * Renders the mining crack overlay on the block being broken: a cube slightly
 * larger than the block, textured with Minecraft's destroy_stage_0..9 textures
 * chosen by break progress (procedural cracks as fallback if the PNGs are missing).
 */
public class BlockCrackRenderer {
    private static final int   STAGES    = 10;  // destroy_stage_0 .. destroy_stage_9
    private static final int   TILE      = 16;   // crack texture resolution per stage
    private static final float INFLATE   = 0.006f; // avoid z-fighting with the block

    private final ShaderProgram shader;
    private final Texture texture; // horizontal strip: STAGES tiles of TILE x TILE
    private final Mesh mesh;       // unit cube, UVs filled per render via uUvOffset? No — per-stage U offset baked via uniform-free trick below
    private final Matrix4f model = new Matrix4f();

    public BlockCrackRenderer() {
        shader  = new ShaderProgram("/shaders/crack.vert", "/shaders/crack.frag");
        texture = buildCrackStrip();
        mesh    = buildCube();
    }

    /**
     * Builds the destroy-stage strip from Minecraft's destroy_stage_0..9 PNGs
     * on the classpath. Any stage that fails to load falls back to a
     * procedural crack pattern so mining always has feedback.
     */
    private static Texture buildCrackStrip() {
        int w = STAGES * TILE, h = TILE;
        ByteBuffer buf = MemoryUtil.memCalloc(w * h * 4); // transparent

        Random rng = new Random(0xC4ACC5EEDL); // fixed seed → same fallback cracks
        int totalWalks = STAGES * 3;
        int[][][] walks = new int[totalWalks][][];
        for (int i = 0; i < totalWalks; i++) walks[i] = crackWalk(rng);

        for (int s = 0; s < STAGES; s++) {
            if (blitStage(buf, w, s, "/textures/destroy_stage_" + s + ".png")) continue;
            // Fallback: jagged walks accumulating with stage
            int xOff = s * TILE;
            int count = (s + 1) * 3;
            for (int i = 0; i < count && i < totalWalks; i++) {
                for (int[] px : walks[i]) {
                    int x = px[0], y = px[1];
                    if (x < 0 || x >= TILE || y < 0 || y >= TILE) continue;
                    int idx = (y * w + xOff + x) * 4;
                    buf.put(idx,     (byte) 0x14);
                    buf.put(idx + 1, (byte) 0x10);
                    buf.put(idx + 2, (byte) 0x0C);
                    buf.put(idx + 3, (byte) 0xB8);
                }
            }
        }

        buf.flip();
        Texture t = new Texture(buf, w, h);
        MemoryUtil.memFree(buf);
        return t;
    }

    /** Loads one destroy-stage PNG into the strip at its slot. True on success. */
    private static boolean blitStage(ByteBuffer buf, int atlasW, int stage, String path) {
        byte[] bytes;
        try (InputStream is = BlockCrackRenderer.class.getResourceAsStream(path)) {
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
        int xStart = stage * TILE;
        for (int ty = 0; ty < TILE; ty++) {
            int sy = ty * srcH / TILE;
            for (int tx = 0; tx < TILE; tx++) {
                int sx  = tx * srcW / TILE;
                int src = (sy * srcW + sx) * 4;
                int dst = (ty * atlasW + xStart + tx) * 4;
                buf.put(dst,     pixels.get(src));
                buf.put(dst + 1, pixels.get(src + 1));
                buf.put(dst + 2, pixels.get(src + 2));
                buf.put(dst + 3, pixels.get(src + 3));
            }
        }
        stbi_image_free(pixels);
        return true;
    }

    /** One jagged random walk from near the centre outward. Returns pixel list. */
    private static int[][] crackWalk(Random rng) {
        int len = 6 + rng.nextInt(8);
        int[][] pixels = new int[len][2];
        int x = 6 + rng.nextInt(4), y = 6 + rng.nextInt(4);
        // Pick a dominant direction so cracks radiate outward
        int dirX = rng.nextBoolean() ? 1 : -1;
        int dirY = rng.nextBoolean() ? 1 : -1;
        for (int i = 0; i < len; i++) {
            pixels[i][0] = x;
            pixels[i][1] = y;
            // Mostly follow the dominant direction with jitter
            if (rng.nextInt(3) != 0) x += rng.nextBoolean() ? dirX : 0;
            if (rng.nextInt(3) != 0) y += rng.nextBoolean() ? dirY : 0;
        }
        return pixels;
    }

    /** Unit cube with per-face UVs covering one stage tile (u scaled at render). */
    private static Mesh buildCube() {
        float lo = -INFLATE, hi = 1f + INFLATE;
        float u0 = 0f, u1 = 1f / STAGES; // stage 0 tile; shifted via uUvShift? No — see render()
        // 6 faces x 4 verts x [x,y,z,u,v,light]
        float[][] corners = {
            // TOP
            {lo,hi,hi}, {hi,hi,hi}, {hi,hi,lo}, {lo,hi,lo},
            // BOTTOM
            {lo,lo,lo}, {hi,lo,lo}, {hi,lo,hi}, {lo,lo,hi},
            // NORTH
            {hi,lo,lo}, {lo,lo,lo}, {lo,hi,lo}, {hi,hi,lo},
            // SOUTH
            {lo,lo,hi}, {hi,lo,hi}, {hi,hi,hi}, {lo,hi,hi},
            // EAST
            {hi,lo,hi}, {hi,lo,lo}, {hi,hi,lo}, {hi,hi,hi},
            // WEST
            {lo,lo,lo}, {lo,lo,hi}, {lo,hi,hi}, {lo,hi,lo},
        };
        float[] verts = new float[24 * 6];
        int[] idx = new int[36];
        float[][] uvs = {{u0, 0}, {u1, 0}, {u1, 1}, {u0, 1}};
        for (int f = 0; f < 6; f++) {
            for (int v = 0; v < 4; v++) {
                int vi = f * 4 + v;
                int p = vi * 6;
                verts[p]     = corners[vi][0];
                verts[p + 1] = corners[vi][1];
                verts[p + 2] = corners[vi][2];
                verts[p + 3] = uvs[v][0];
                verts[p + 4] = uvs[v][1];
                verts[p + 5] = 1f;
            }
            int b = f * 4, q = f * 6;
            idx[q] = b; idx[q+1] = b+1; idx[q+2] = b+2;
            idx[q+3] = b+2; idx[q+4] = b+3; idx[q+5] = b;
        }
        Mesh m = new Mesh();
        m.upload(verts, idx);
        return m;
    }

    /** Draws the crack overlay; does nothing when target is null or progress ~0. */
    public void render(Matrix4f projection, Matrix4f view, int[] target, float progress) {
        if (target == null || progress <= 0.02f) return;
        int stage = Math.min(STAGES - 1, (int) (progress * STAGES));

        model.identity().translate(target[0], target[1], target[2]);

        glEnable(GL_BLEND);
        // Multiplicative blend (result = 2 * src * dst), same as Minecraft's
        // destroy overlay: the grey crack pixels DARKEN the block's own texture
        // instead of painting flat grey over it. Transparent pixels are
        // discarded in crack.frag so they don't black out the face.
        glBlendFunc(GL_DST_COLOR, GL_SRC_COLOR);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uModel", model);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uStageShift", (float) stage / STAGES);
        texture.bind(0);
        mesh.render();
        shader.detach();

        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glDepthFunc(GL_LESS);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // restore standard blending
        glDisable(GL_BLEND);
    }

    public void cleanup() {
        if (shader  != null) shader.cleanup();
        if (texture != null) texture.cleanup();
        if (mesh    != null) mesh.cleanup();
    }
}
