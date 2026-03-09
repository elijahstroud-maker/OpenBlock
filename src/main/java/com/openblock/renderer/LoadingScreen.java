package com.openblock.renderer;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * Old-school loading screen: tiled dirt background rendered in 2D ortho,
 * shown while the world is generating its first chunks.
 */
public class LoadingScreen {
    /** Dirt tile visual size in screen pixels (2× scale = pixelated look). */
    private static final int TILE_PX = 32;

    private final ShaderProgram shader;
    private final Texture dirtTex;
    private Mesh mesh;
    private final Matrix4f projection = new Matrix4f();

    private int lastW = -1;
    private int lastH = -1;

    public LoadingScreen() {
        shader  = new ShaderProgram("/shaders/loading.vert", "/shaders/loading.frag");
        dirtTex = buildDirtTexture();
    }

    /** Build a 16×16 RGBA dirt texture with GL_REPEAT. */
    private static Texture buildDirtTexture() {
        int size = 16;
        ByteBuffer buf = MemoryUtil.memAlloc(size * size * 4);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                // Simple dirt pattern: base brown + occasional lighter/darker pixel
                boolean lighter = ((px * 3 + py * 7) % 13 < 2);
                boolean darker  = ((px * 7 + py * 3) % 11 < 2);
                int r = lighter ? 0xA0 : (darker ? 0x70 : 0x8B);
                int g = lighter ? 0x60 : (darker ? 0x30 : 0x45);
                int b = lighter ? 0x30 : (darker ? 0x08 : 0x13);
                int idx = (py * size + px) * 4;
                buf.put(idx,     (byte) r);
                buf.put(idx + 1, (byte) g);
                buf.put(idx + 2, (byte) b);
                buf.put(idx + 3, (byte) 0xFF);
            }
        }
        buf.flip();
        Texture t = new Texture(buf, size, size);
        MemoryUtil.memFree(buf);
        return t;
    }

    public void render(int screenW, int screenH) {
        if (screenW != lastW || screenH != lastH) {
            rebuildMesh(screenW, screenH);
            projection.identity().setOrtho(0, screenW, screenH, 0, -1, 1);
            lastW = screenW;
            lastH = screenH;
        }

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uTexture", 0);
        dirtTex.bind(0);
        mesh.render();
        shader.detach();

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void rebuildMesh(int w, int h) {
        // One fullscreen quad; UV coordinates tile the 16×16 texture at TILE_PX screen pixels per tile.
        float uMax = (float) w / TILE_PX;
        float vMax = (float) h / TILE_PX;
        float brightness = 0.4f; // darken for atmosphere

        float[] verts = {
            //  x      y     z      u      v      light
               0,     0,    0,     0,     0,     brightness,
            (float)w,  0,   0,    uMax,   0,     brightness,
            (float)w, (float)h, 0, uMax, vMax,   brightness,
               0,    (float)h, 0,   0,   vMax,   brightness,
        };
        int[] idxs = {0, 1, 2,  2, 3, 0};

        if (mesh != null) mesh.cleanup();
        mesh = new Mesh();
        mesh.upload(verts, idxs);
    }

    public void cleanup() {
        shader.cleanup();
        dirtTex.cleanup();
        if (mesh != null) mesh.cleanup();
    }
}
