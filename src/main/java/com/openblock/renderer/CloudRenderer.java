package com.openblock.renderer;

import com.openblock.terrain.SimplexNoise;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

/**
 * Renders blocky clouds that scroll slowly in the +X direction.
 * The cloud grid is rebuilt around the player whenever they move far enough,
 * keeping clouds "infinite" without storing a large fixed mesh.
 *
 * Noise-space trick: sampling noise at (worldX - buildScrollX, worldZ) at build time
 * is equivalent to sampling at (currentWorldX - currentScrollX, worldZ) at render time,
 * because the model matrix shifts geometry by (scrollX - buildScrollX). So the pattern
 * stays consistent as clouds drift.
 */
public class CloudRenderer {
    private static final float SCROLL_SPEED   = 0.75f; // world units per second
    private static final int   CLOUD_Y        = 150;   // bottom of cloud layer
    private static final int   CLOUD_H        = 4;     // cloud block height (world units)
    private static final int   BLOCK_SIZE     = 8;     // XZ size of each cloud block
    private static final int   GRID_RADIUS    = 38;    // half-grid in cloud blocks
    /** Rebuild mesh when player moves this far from build center. */
    private static final float REBUILD_DIST   = GRID_RADIUS * BLOCK_SIZE * 0.45f;
    private static final float THRESHOLD      = 0.18f;

    private final ShaderProgram shader;
    private final Texture whiteTexture;
    private final Matrix4f model = new Matrix4f();
    private final SimplexNoise noise = new SimplexNoise(77777L);

    private Mesh mesh = null;
    private float scrollX       = 0;
    private float buildScrollX  = 0;
    private float buildCenterX  = Float.MAX_VALUE;
    private float buildCenterZ  = Float.MAX_VALUE;

    public CloudRenderer() {
        shader      = new ShaderProgram("/shaders/chunk.vert", "/shaders/chunk.frag");
        whiteTexture = buildWhiteTexture();
    }

    private static Texture buildWhiteTexture() {
        ByteBuffer buf = MemoryUtil.memAlloc(4);
        buf.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xE0);
        buf.flip();
        Texture t = new Texture(buf, 1, 1);
        MemoryUtil.memFree(buf);
        return t;
    }

    /** Must be called every tick with current delta and player world position. */
    public void update(float delta, float playerX, float playerZ) {
        scrollX += SCROLL_SPEED * delta;

        float dx = Math.abs(playerX - buildCenterX);
        float dz = Math.abs(playerZ - buildCenterZ);
        if (mesh == null || dx > REBUILD_DIST || dz > REBUILD_DIST) {
            rebuild(playerX, playerZ);
        }
    }

    private void rebuild(float cx, float cz) {
        buildCenterX = cx;
        buildCenterZ = cz;
        buildScrollX = scrollX;

        int diameter = GRID_RADIUS * 2 + 1;
        List<Float>   verts = new ArrayList<>(diameter * diameter * 6 * 4 * 6);
        List<Integer> idxs  = new ArrayList<>(diameter * diameter * 6 * 6);
        int vi = 0;

        for (int gx = 0; gx < diameter; gx++) {
            for (int gz = 0; gz < diameter; gz++) {
                // World position of this cell at build time
                float wx = cx + (gx - GRID_RADIUS) * BLOCK_SIZE;
                float wz = cz + (gz - GRID_RADIUS) * BLOCK_SIZE;

                // Sample noise in scroll-invariant "noise space":
                // noiseX = wx - buildScrollX ensures pattern stays consistent as scrollX changes
                double noiseX = (wx - buildScrollX) * 0.012;
                double noiseZ = wz * 0.012;
                double n = noise.noise(noiseX, noiseZ) * 0.70
                         + noise.noise(noiseX * 2.5, noiseZ * 2.5) * 0.30;

                if (n < THRESHOLD) continue;

                float x0 = wx, x1 = wx + BLOCK_SIZE;
                float y0 = CLOUD_Y, y1 = CLOUD_Y + CLOUD_H;
                float z0 = wz, z1 = wz + BLOCK_SIZE;

                // TOP (+Y): CCW from above, normal = +Y
                vi += quad(verts, idxs, vi,
                    x0,y1,z1,  x1,y1,z1,  x1,y1,z0,  x0,y1,z0,  1.0f);
                // BOTTOM (-Y): CCW from below, normal = -Y
                vi += quad(verts, idxs, vi,
                    x0,y0,z0,  x1,y0,z0,  x1,y0,z1,  x0,y0,z1,  0.6f);
                // NORTH (-Z)
                vi += quad(verts, idxs, vi,
                    x1,y0,z0,  x0,y0,z0,  x0,y1,z0,  x1,y1,z0,  0.8f);
                // SOUTH (+Z)
                vi += quad(verts, idxs, vi,
                    x0,y0,z1,  x1,y0,z1,  x1,y1,z1,  x0,y1,z1,  0.8f);
                // EAST (+X)
                vi += quad(verts, idxs, vi,
                    x1,y0,z1,  x1,y0,z0,  x1,y1,z0,  x1,y1,z1,  0.85f);
                // WEST (-X)
                vi += quad(verts, idxs, vi,
                    x0,y0,z0,  x0,y0,z1,  x0,y1,z1,  x0,y1,z0,  0.85f);
            }
        }

        float[] va = new float[verts.size()];
        for (int i = 0; i < va.length; i++) va[i] = verts.get(i);
        int[] ia = new int[idxs.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = idxs.get(i);

        if (mesh != null) mesh.cleanup();
        mesh = new Mesh();
        if (va.length > 0) mesh.upload(va, ia);
    }

    /** Emits 4 vertices + 6 indices for one quad. Returns 4 (vertex count added). */
    private static int quad(List<Float> v, List<Integer> idx, int base,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float light) {
        vert(v, x0, y0, z0, 0, 0, light);
        vert(v, x1, y1, z1, 1, 0, light);
        vert(v, x2, y2, z2, 1, 1, light);
        vert(v, x3, y3, z3, 0, 1, light);
        idx.add(base); idx.add(base+1); idx.add(base+2);
        idx.add(base+2); idx.add(base+3); idx.add(base);
        return 4;
    }

    private static void vert(List<Float> v, float x, float y, float z, float u, float vv, float l) {
        v.add(x); v.add(y); v.add(z); v.add(u); v.add(vv); v.add(l);
    }

    public void render(Matrix4f projection, Matrix4f view,
                       org.joml.Vector3f fogColor, float ambient) {
        if (mesh == null || mesh.isEmpty()) return;

        // Shift entire mesh by scroll delta — this is what makes clouds visually move
        model.identity().translate(scrollX - buildScrollX, 0, 0);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uModel", model);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uFogColor", fogColor);
        shader.setUniform("uAmbient", ambient);
        whiteTexture.bind(0);
        mesh.render();
        shader.detach();

        glDisable(GL_BLEND);
    }

    public void cleanup() {
        if (mesh        != null) mesh.cleanup();
        if (shader      != null) shader.cleanup();
        if (whiteTexture!= null) whiteTexture.cleanup();
    }
}
