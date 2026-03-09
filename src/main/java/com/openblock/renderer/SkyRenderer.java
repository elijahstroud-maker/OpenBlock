package com.openblock.renderer;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;

/**
 * Renders sun and moon as billboard quads in the sky.
 * Drawn before terrain with depth writes disabled so they never occlude anything.
 */
public class SkyRenderer {
    private static final float SIZE = 18.0f;  // angular size of sun/moon (world units at dist 200)
    private static final float DIST = 200.0f; // distance from player

    private final ShaderProgram shader;
    private final Mesh mesh;
    private final Matrix4f model = new Matrix4f();

    public SkyRenderer() {
        shader = new ShaderProgram("/shaders/sky.vert", "/shaders/sky.frag");
        mesh = buildQuad();
    }

    /** Build a unit quad centred at origin in the XY plane. */
    private static Mesh buildQuad() {
        float h = 0.5f;
        float[] verts = {
            // x,    y,    z,   u,   v,  light
            -h, -h, 0,   0, 1, 1,
             h, -h, 0,   1, 1, 1,
             h,  h, 0,   1, 0, 1,
            -h,  h, 0,   0, 0, 1,
        };
        int[] idx = {0, 1, 2, 2, 3, 0};
        Mesh m = new Mesh();
        m.upload(verts, idx);
        return m;
    }

    /**
     * @param projection camera projection
     * @param view       camera view matrix
     * @param camPos     camera world position (for billboard placement)
     * @param dayNight   provides sun/moon directions and colours
     */
    public void render(Matrix4f projection, Matrix4f view, Vector3f camPos, DayNightCycle dayNight) {
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uTexture", 0);

        // Sun
        if (dayNight.isSunUp()) {
            renderBody(camPos, dayNight.getSunDirection(),
                       1.0f, 0.95f, 0.70f, 1.0f);
        }

        // Moon (always render, fade with ambient so it dims at noon)
        float moonAlpha = Math.max(0.0f, 1.0f - dayNight.getAmbient() * 1.5f);
        if (moonAlpha > 0.01f) {
            renderBody(camPos, dayNight.getMoonDirection(),
                       0.90f, 0.90f, 0.95f, moonAlpha);
        }

        shader.detach();
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    private void renderBody(Vector3f camPos, Vector3f dir,
                            float r, float g, float b, float a) {
        // Centre of the billboard: camera + dir * DIST
        Vector3f centre = new Vector3f(camPos).add(
            dir.x * DIST, dir.y * DIST, dir.z * DIST);

        // Build billboard axes: right = cross(dir, worldUp), up = cross(right, dir)
        Vector3f worldUp = new Vector3f(0, 1, 0);
        if (Math.abs(dir.y) > 0.99f) worldUp.set(1, 0, 0); // avoid degenerate
        Vector3f right = new Vector3f(dir).cross(worldUp).normalize();
        Vector3f up    = new Vector3f(right).cross(dir).normalize();

        // Build rotation+translation matrix (column-major: right | up | -dir | centre)
        model.set(
            right.x,    right.y,    right.z,    0,
            up.x,       up.y,       up.z,       0,
            -dir.x,    -dir.y,     -dir.z,      0,
            centre.x,   centre.y,   centre.z,   1
        ).scale(SIZE);

        shader.setUniform("uModel", model);
        shader.setUniform("uColor", r, g, b, a);
        mesh.render();
    }

    public void cleanup() {
        if (shader != null) shader.cleanup();
        if (mesh   != null) mesh.cleanup();
    }
}
