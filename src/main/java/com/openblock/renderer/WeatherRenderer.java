package com.openblock.renderer;

import com.openblock.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.*;

/**
 * Rain and snow curtains, Minecraft style: for every column in a grid around
 * the player, two crossed vertical quads run from the terrain surface up past
 * eye level, textured with the scrolling rain.png / snow.png strips. Columns
 * whose surface is above the curtain top draw nothing — so caves, overhangs
 * and tree canopies shelter the player exactly where a roof exists.
 */
public class WeatherRenderer {
    private static final int   RADIUS    = 8;              // columns each side of the player
    private static final int   GRID      = RADIUS * 2 + 1;
    private static final float TOP_ABOVE = 12f;            // curtain top above eye level
    private static final float MAX_BELOW = 18f;            // deepest reach below eye level
    private static final float V_SCALE   = 0.25f;          // 64x256 texture spans 4 blocks of height
    private static final float FADE_DIST = 20f;            // edge columns fade out (frag shader)
    private static final float RAIN_FALL = 4.0f;           // scroll speed, texture-heights/s (~16 blocks/s)
    private static final float SNOW_FALL = 0.35f;          // snow drifts down slowly

    private final ShaderProgram shader;
    private final Texture rainTex;
    private final Texture snowTex;
    private final Mesh mesh = new Mesh();

    // Per-column surface heights, cached and refreshed as the player moves
    private final int[] surface = new int[GRID * GRID];
    private int   cachedPX = Integer.MIN_VALUE, cachedPZ = Integer.MIN_VALUE;
    private float refreshTimer = 0f;

    // Reused build buffers: 2 quads per column, 4 verts x 6 floats, 6 indices per quad
    private final float[] verts = new float[GRID * GRID * 2 * 4 * 6];
    private final int[]   index = new int[GRID * GRID * 2 * 6];

    private float scroll = 0f;

    public WeatherRenderer() {
        shader  = new ShaderProgram("/shaders/weather.vert", "/shaders/weather.frag");
        rainTex = new Texture("/textures/environment/rain.png");
        snowTex = new Texture("/textures/environment/snow.png");
    }

    public void render(World world, Matrix4f projection, Matrix4f view, Vector3f eye,
                       float intensity, boolean snow, float ambient, float delta) {
        if (intensity <= 0.01f) return;

        scroll += delta * (snow ? SNOW_FALL : RAIN_FALL);
        if (scroll > 4096f) scroll -= 4096f; // keep float precision over long sessions

        int px = (int) Math.floor(eye.x), pz = (int) Math.floor(eye.z);
        refreshTimer -= delta;
        if (px != cachedPX || pz != cachedPZ || refreshTimer <= 0f) {
            cachedPX = px;
            cachedPZ = pz;
            refreshTimer = 0.4f;
            for (int gz = 0; gz < GRID; gz++) {
                for (int gx = 0; gx < GRID; gx++) {
                    // True heightmap surface: a roof ANY height overhead shelters
                    // the column (a near-eye scan missed cave ceilings >12 up)
                    surface[gz * GRID + gx] =
                        world.getSurfaceY(px - RADIUS + gx, pz - RADIUS + gz);
                }
            }
        }

        // Build the visible curtains for this frame
        float top = eye.y + TOP_ABOVE;
        int vp = 0, ip = 0, quadCount = 0;
        for (int gz = 0; gz < GRID; gz++) {
            for (int gx = 0; gx < GRID; gx++) {
                float bot = surface[gz * GRID + gx];
                if (bot >= top) continue;                       // roofed over — sheltered
                if (bot < eye.y - MAX_BELOW) bot = eye.y - MAX_BELOW;

                int wx = px - RADIUS + gx, wz = pz - RADIUS + gz;
                float cx = wx + 0.5f, cz = wz + 0.5f;
                // Per-column random texture offset/phase so no two columns match
                float u0 = hash01(wx, wz, 1);
                float ph = hash01(wx, wz, 2) * 4f;
                // v decreases with height so increasing uScroll moves streaks DOWN
                float vTop = ph - top * V_SCALE, vBot = ph - bot * V_SCALE;

                // Quad A: spans X at the column's centre Z; Quad B: spans Z
                vp = putQuad(verts, vp, cx - 0.5f, cz, cx + 0.5f, cz, bot, top, u0, vBot, vTop);
                ip = putQuadIndices(index, ip, quadCount++ * 4);
                vp = putQuad(verts, vp, cx, cz - 0.5f, cx, cz + 0.5f, bot, top, u0, vBot, vTop);
                ip = putQuadIndices(index, ip, quadCount++ * 4);
            }
        }
        if (quadCount == 0) return;
        mesh.upload(Arrays.copyOf(verts, vp), Arrays.copyOf(index, ip));

        // Precipitation stays faintly visible at night (l floor), brightens with daylight
        float l = 0.25f + 0.75f * ambient;

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uScroll", scroll);
        shader.setUniform("uFadeDist", FADE_DIST);
        if (snow) shader.setUniform("uColor", 0.92f * l, 0.92f * l, 0.97f * l, 0.85f * intensity);
        else      shader.setUniform("uColor", 0.75f * l, 0.80f * l, 0.92f * l, 0.65f * intensity);
        (snow ? snowTex : rainTex).bind(0);
        mesh.render();
        shader.detach();

        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    /** Vertical quad from (x0,z0,bot) to (x1,z1,top); u spans one full texture repeat. */
    private static int putQuad(float[] v, int p,
                               float x0, float z0, float x1, float z1,
                               float bot, float top, float u0, float vBot, float vTop) {
        p = putVert(v, p, x0, bot, z0, u0,      vBot);
        p = putVert(v, p, x1, bot, z1, u0 + 1f, vBot);
        p = putVert(v, p, x1, top, z1, u0 + 1f, vTop);
        p = putVert(v, p, x0, top, z0, u0,      vTop);
        return p;
    }

    private static int putVert(float[] v, int p, float x, float y, float z, float u, float tv) {
        v[p] = x; v[p + 1] = y; v[p + 2] = z; v[p + 3] = u; v[p + 4] = tv; v[p + 5] = 1f;
        return p + 6;
    }

    private static int putQuadIndices(int[] idx, int p, int base) {
        idx[p] = base; idx[p + 1] = base + 1; idx[p + 2] = base + 2;
        idx[p + 3] = base + 2; idx[p + 4] = base + 3; idx[p + 5] = base;
        return p + 6;
    }

    /** Deterministic per-column hash in [0,1). */
    private static float hash01(int x, int z, int salt) {
        int h = x * 374761393 + z * 668265263 + salt * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0x7FFFFFFF) / (float) 0x80000000L;
    }

    public void cleanup() {
        if (shader  != null) shader.cleanup();
        if (rainTex != null) rainTex.cleanup();
        if (snowTex != null) snowTex.cleanup();
        mesh.cleanup();
    }
}
