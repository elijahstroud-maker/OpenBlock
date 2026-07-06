package com.openblock.renderer;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Draws a wireframe outline around a targeted block.
 *
 * Each of the 12 cube edges is rendered as two flat quads (cross profile).
 * Where quads from different axes are coplanar at corners, both are black so
 * the overlap is invisible — one just overwrites the other cleanly.
 * No separate corner geometry is needed (corner cubes cause visible bumps).
 *
 * 12 edges × 2 quads × 4 verts = 96 verts
 * 12 edges × 2 quads × 6 indices = 144 indices
 */
public class BlockOutlineRenderer {
    /** How far outside the block the outline sits (avoids z-fighting with block faces). */
    private static final float E = 0.008f;
    /** Half-width of each edge quad in world units. */
    private static final float T = 0.010f;

    private final ShaderProgram shader;
    private final int vaoId;
    private final int vboId;
    private final int eboId;
    private final Matrix4f model = new Matrix4f();

    public BlockOutlineRenderer() {
        shader = new ShaderProgram("/shaders/outline.vert", "/shaders/outline.frag");

        float[] verts = buildVerts();
        int[]   idx   = buildIdx();

        vaoId = glGenVertexArrays();
        vboId = glGenBuffers();
        eboId = glGenBuffers();

        FloatBuffer vb = MemoryUtil.memAllocFloat(verts.length);
        IntBuffer   ib = MemoryUtil.memAllocInt(idx.length);
        try {
            vb.put(verts).flip();
            ib.put(idx).flip();

            glBindVertexArray(vaoId);

            glBindBuffer(GL_ARRAY_BUFFER, vboId);
            glBufferData(GL_ARRAY_BUFFER, vb, GL_STATIC_DRAW);

            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);

            glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
            glEnableVertexAttribArray(0);

            glBindVertexArray(0);
        } finally {
            MemoryUtil.memFree(vb);
            MemoryUtil.memFree(ib);
        }
    }

    private static float[] buildVerts() {
        float lo = -E, hi = 1.0f + E;
        float[] v = new float[24 * 4 * 3]; // 24 quads × 4 verts × 3 floats
        int p = 0;

        // ---- 4 X-axis edges: (fy,fz) ∈ {(lo,lo),(lo,hi),(hi,lo),(hi,hi)} ----
        float[] fy_x = {lo, lo, hi, hi};
        float[] fz_x = {lo, hi, lo, hi};
        for (int i = 0; i < 4; i++) {
            float fy = fy_x[i], fz = fz_x[i];
            p=put(v,p, lo,fy,fz-T); p=put(v,p, hi,fy,fz-T); p=put(v,p, hi,fy,fz+T); p=put(v,p, lo,fy,fz+T);
            p=put(v,p, lo,fy-T,fz); p=put(v,p, hi,fy-T,fz); p=put(v,p, hi,fy+T,fz); p=put(v,p, lo,fy+T,fz);
        }

        // ---- 4 Y-axis edges: (fx,fz) ∈ {(lo,lo),(hi,lo),(lo,hi),(hi,hi)} ----
        // fz uses {lo,lo,hi,hi} — pairing it with {lo,hi,lo,hi} like fx would
        // yield only 2 unique corners instead of 4 (caused the missing-edge bug).
        float[] fx_y = {lo, hi, lo, hi};
        float[] fz_y = {lo, lo, hi, hi};
        for (int i = 0; i < 4; i++) {
            float fx = fx_y[i], fz = fz_y[i];
            p=put(v,p, fx,lo,fz-T); p=put(v,p, fx,hi,fz-T); p=put(v,p, fx,hi,fz+T); p=put(v,p, fx,lo,fz+T);
            p=put(v,p, fx-T,lo,fz); p=put(v,p, fx+T,lo,fz); p=put(v,p, fx+T,hi,fz); p=put(v,p, fx-T,hi,fz);
        }

        // ---- 4 Z-axis edges: (fx,fy) ∈ {(lo,lo),(hi,lo),(lo,hi),(hi,hi)} ----
        float[] fx_z = {lo, hi, lo, hi};
        float[] fy_z = {lo, lo, hi, hi};
        for (int i = 0; i < 4; i++) {
            float fx = fx_z[i], fy = fy_z[i];
            p=put(v,p, fx-T,fy,lo); p=put(v,p, fx+T,fy,lo); p=put(v,p, fx+T,fy,hi); p=put(v,p, fx-T,fy,hi);
            p=put(v,p, fx,fy-T,lo); p=put(v,p, fx,fy-T,hi); p=put(v,p, fx,fy+T,hi); p=put(v,p, fx,fy+T,lo);
        }

        return v;
    }

    private static int[] buildIdx() {
        int[] idx = new int[24 * 6];
        for (int q = 0; q < 24; q++) {
            int b = q * 4, p = q * 6;
            idx[p]   = b;   idx[p+1] = b+1; idx[p+2] = b+2;
            idx[p+3] = b+2; idx[p+4] = b+3; idx[p+5] = b;
        }
        return idx;
    }

    private static int put(float[] v, int p, float x, float y, float z) {
        v[p] = x; v[p+1] = y; v[p+2] = z; return p + 3;
    }

    public void render(Matrix4f projection, Matrix4f view, int[] targetBlock) {
        if (targetBlock == null) return;

        model.identity().translate(targetBlock[0], targetBlock[1], targetBlock[2]);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uModel", model);

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-3.0f, -3.0f);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vaoId);
        glDrawElements(GL_TRIANGLES, 144, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(0.0f, 0.0f);
        glDepthMask(true);
        glDepthFunc(GL_LESS);
        glEnable(GL_CULL_FACE);

        shader.detach();
    }

    public void cleanup() {
        if (shader != null) shader.cleanup();
        glDeleteBuffers(vboId);
        glDeleteBuffers(eboId);
        glDeleteVertexArrays(vaoId);
    }
}
