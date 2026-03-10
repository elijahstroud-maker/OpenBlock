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

    private static final int   STAR_COUNT = 300;
    private static final float STAR_DIST  = 190.0f; // just inside sun/moon dist

    private final ShaderProgram shader;
    private final Mesh mesh;
    private final Mesh starMesh;
    private final Matrix4f model     = new Matrix4f();
    private final Matrix4f starModel = new Matrix4f();

    public SkyRenderer() {
        shader   = new ShaderProgram("/shaders/sky.vert", "/shaders/sky.frag");
        mesh     = buildQuad();
        starMesh = buildStarMesh();
    }

    /**
     * Builds a single mesh containing all star quads in camera-local space.
     * Each star is a tiny billboard at a random direction on the sphere.
     * aLight (vertex attribute) varies per star for brightness variation.
     */
    private static Mesh buildStarMesh() {
        float[] verts = new float[STAR_COUNT * 4 * 6]; // 4 verts × 6 floats
        int[]   idx   = new int  [STAR_COUNT * 6];

        long s = 0xC0FFEE5AFE1234L; // fixed seed → same stars every run
        int vp = 0;

        for (int i = 0; i < STAR_COUNT; i++) {
            // Random direction on sphere (uniform)
            s = s * 6364136223846793005L + 1442695040888963407L;
            float theta  = (float)((s >>> 33) * (2.0 * Math.PI / (double)(1L << 31)));
            s = s * 6364136223846793005L + 1442695040888963407L;
            float cosPhi = (float)((s >>> 33) / (double)(1L << 31)) * 2.0f - 1.0f;
            float sinPhi = (float)Math.sqrt(Math.max(0, 1.0 - cosPhi * cosPhi));

            float dx = sinPhi * (float)Math.cos(theta);
            float dy = cosPhi;
            float dz = sinPhi * (float)Math.sin(theta);

            // Billboard axes: right = cross(dir, worldUp), up = cross(right, dir)
            float wx = (Math.abs(dy) > 0.99f) ? 1 : 0;
            float wy = (Math.abs(dy) > 0.99f) ? 0 : 1;
            float rx = dy * 0 - dz * wy,  ry = dz * wx - dx * 0,  rz = dx * wy - dy * wx;
            float rLen = (float)Math.sqrt(rx*rx + ry*ry + rz*rz);
            rx /= rLen; ry /= rLen; rz /= rLen;
            float ux = ry * dz - rz * dy,  uy = rz * dx - rx * dz,  uz = rx * dy - ry * dx;

            // Vary star size (0.15 – 0.45) and brightness (0.5 – 1.0)
            s = s * 6364136223846793005L + 1442695040888963407L;
            float half  = 0.15f + (float)((s >>> 33) & 0xFFFFF) / (float)0xFFFFF * 0.30f;
            s = s * 6364136223846793005L + 1442695040888963407L;
            float light = 0.50f + (float)((s >>> 33) & 0xFFFFF) / (float)0xFFFFF * 0.50f;

            float cx = dx * STAR_DIST, cy = dy * STAR_DIST, cz = dz * STAR_DIST;

            int base = i * 4;
            // bottom-left, bottom-right, top-right, top-left
            putStarVert(verts, vp, cx - rx*half - ux*half, cy - ry*half - uy*half, cz - rz*half - uz*half, 0,1, light); vp+=6;
            putStarVert(verts, vp, cx + rx*half - ux*half, cy + ry*half - uy*half, cz + rz*half - uz*half, 1,1, light); vp+=6;
            putStarVert(verts, vp, cx + rx*half + ux*half, cy + ry*half + uy*half, cz + rz*half + uz*half, 1,0, light); vp+=6;
            putStarVert(verts, vp, cx - rx*half + ux*half, cy - ry*half + uy*half, cz - rz*half + uz*half, 0,0, light); vp+=6;

            int ip = i * 6;
            idx[ip]   = base;   idx[ip+1] = base+1; idx[ip+2] = base+2;
            idx[ip+3] = base+2; idx[ip+4] = base+3; idx[ip+5] = base;
        }

        Mesh m = new Mesh();
        m.upload(verts, idx);
        return m;
    }

    private static void putStarVert(float[] buf, int p,
                                    float x, float y, float z,
                                    float u, float v, float light) {
        buf[p]   = x; buf[p+1] = y; buf[p+2] = z;
        buf[p+3] = u; buf[p+4] = v; buf[p+5] = light;
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

        float moonAlpha = Math.max(0.0f, 1.0f - dayNight.getAmbient() * 1.5f);

        // Stars first — sun and moon paint over them, so no star appears in front
        if (moonAlpha > 0.01f) {
            float skyAngle = (float)((dayNight.getTime() - 0.25f) * Math.PI * 2.0);
            starModel.translation(camPos.x, camPos.y, camPos.z).rotateZ(skyAngle);
            shader.setUniform("uModel", starModel);
            shader.setUniform("uColor", 1.0f, 1.0f, 0.95f, moonAlpha);
            starMesh.render();
        }

        // Sun
        if (dayNight.isSunUp()) {
            renderBody(camPos, dayNight.getSunDirection(),
                       1.0f, 0.95f, 0.70f, 1.0f);
        }

        // Moon
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

        // Pin right to the sky rotation axis (Z) so the quad never spins as
        // the body arcs across the sky. Z is never parallel to dir (dir.z ≈ 0.2)
        // so there is no degenerate case and no sudden flip at the zenith.
        Vector3f right = new Vector3f(0, 0, 1);
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
        if (shader   != null) shader.cleanup();
        if (mesh     != null) mesh.cleanup();
        if (starMesh != null) starMesh.cleanup();
    }
}
