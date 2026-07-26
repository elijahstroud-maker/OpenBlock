package com.openblock.renderer;

import com.openblock.world.BlockType;
import com.openblock.world.World;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;

/**
 * Minecraft-style block-crack particles: each mining hit chips a few tiny
 * bits off the struck face — the block being chiseled away. Every chip is a
 * small billboard textured with a random quarter-tile patch of the block's
 * own texture (MC's TerrainParticle trick), thrown out with a hop, pulled
 * down by gravity, and colliding with the world so chips scatter and settle
 * on the ground before fading.
 *
 * One pooled batch, one draw call — same pattern as RainSplashRenderer.
 */
public class BlockParticleRenderer {
    private static final int   MAX     = 1024;
    private static final float GRAVITY = -13f;

    private final ShaderProgram shader;
    private final TextureAtlas atlas;
    private final Mesh mesh = new Mesh();
    private final Random rng = new Random();

    // Particle pool (structure-of-arrays; swap-remove keeps it dense)
    private final float[] px = new float[MAX], py = new float[MAX], pz = new float[MAX];
    private final float[] vx = new float[MAX], vy = new float[MAX], vz = new float[MAX];
    private final float[] age  = new float[MAX], life = new float[MAX];
    private final float[] size = new float[MAX];
    private final float[] u0 = new float[MAX], v0 = new float[MAX]; // sub-tile patch origin
    private final float[] du = new float[MAX], dv = new float[MAX]; // patch extent (v may be negative)
    private int count = 0;

    // Reused build buffers
    private final float[] verts = new float[MAX * 4 * 6];
    private final int[]   index = new int[MAX * 6];

    public BlockParticleRenderer(TextureAtlas atlas) {
        this.atlas  = atlas;
        this.shader = new ShaderProgram("/shaders/particle.vert", "/shaders/particle.frag");
    }

    /** Drains queued events from the world and steps all live chips. */
    public void update(World world, float delta) {
        List<World.BlockParticles> events = world.getParticleEvents();
        if (!events.isEmpty()) {
            for (World.BlockParticles e : events) {
                if (e.burst()) spawnBurst(e); else spawnChips(e);
            }
            events.clear();
        }

        for (int i = 0; i < count; ) {
            age[i] += delta;
            if (age[i] >= life[i]) {
                count--;
                copy(count, i);
                continue;
            }
            // Resting on a surface? Skip gravity entirely — re-applying it and
            // re-snapping every frame makes landed chips shiver at the seam.
            boolean grounded = vy[i] <= 0f
                && solid(world, px[i], py[i] - size[i] - 0.02f, pz[i]);
            if (grounded) {
                vy[i] = 0f;
                float f = Math.max(0f, 1f - delta * 14f); // settle the slide fast
                vx[i] *= f; vz[i] *= f;
            } else {
                vy[i] += GRAVITY * delta;
                // slight air drag, like MC's per-tick 0.98 velocity decay
                float drag = Math.max(0f, 1f - delta * 1.2f);
                vx[i] *= drag; vz[i] *= drag;
            }

            // Axis-by-axis movement with cheap centre-point collision
            float nx = px[i] + vx[i] * delta;
            if (solid(world, nx, py[i], pz[i])) vx[i] = 0f; else px[i] = nx;
            float nz = pz[i] + vz[i] * delta;
            if (solid(world, px[i], py[i], nz)) vz[i] = 0f; else pz[i] = nz;
            float ny = py[i] + vy[i] * delta;
            if (solid(world, px[i], ny, pz[i])) {
                if (vy[i] < 0f) { // landed: sit on the surface, kill the slide
                    py[i] = (float) Math.floor(ny) + 1f + size[i] + 0.01f;
                    vx[i] *= 0.5f; vz[i] *= 0.5f;
                }
                vy[i] = 0f;
            } else {
                py[i] = ny;
            }
            i++;
        }
    }

    /**
     * Block destroyed: MC's burst — the block volume subdivided 4x4x4, one
     * chip per cell thrown outward from the block center, so the block
     * visibly shatters into the debris that then rains down and settles.
     */
    private void spawnBurst(World.BlockParticles e) {
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                for (int k = 0; k < 4; k++) {
                    if (count >= MAX) return;
                    float ox = (i + 0.5f) / 4f, oy = (j + 0.5f) / 4f, oz = (k + 0.5f) / 4f;
                    // Outward from center plus a bit of random scatter and an
                    // upward hop, scaled down toward MC's gentle pop — the
                    // chips mostly collapse into a heap, not a firework
                    spawn(e.type(),
                        e.x() + ox, e.y() + oy, e.z() + oz,
                        (ox - 0.5f) * 4f * (0.4f + rng.nextFloat() * 0.6f),
                        (oy - 0.5f) * 4f * (0.4f + rng.nextFloat() * 0.6f)
                            + 0.8f + rng.nextFloat() * 0.8f,
                        (oz - 0.5f) * 4f * (0.4f + rng.nextFloat() * 0.6f));
                }
            }
        }
    }

    /** Mining tick: a few chips popping off the struck face. */
    private void spawnChips(World.BlockParticles e) {
        int n = 2 + rng.nextInt(2);
        for (int i = 0; i < n && count < MAX; i++) {
            // Random point on the face, nudged just off the surface
            float ox = e.fx() == 0 ? 0.1f + rng.nextFloat() * 0.8f : (e.fx() > 0 ? 1.06f : -0.06f);
            float oy = e.fy() == 0 ? 0.1f + rng.nextFloat() * 0.8f : (e.fy() > 0 ? 1.06f : -0.06f);
            float oz = e.fz() == 0 ? 0.1f + rng.nextFloat() * 0.8f : (e.fz() > 0 ? 1.06f : -0.06f);
            spawn(e.type(),
                e.x() + ox, e.y() + oy, e.z() + oz,
                e.fx() * (0.8f + rng.nextFloat() * 0.6f) + (rng.nextFloat() - 0.5f) * 0.8f,
                e.fy() * (0.8f + rng.nextFloat() * 0.6f) + 1.0f + rng.nextFloat() * 0.5f,
                e.fz() * (0.8f + rng.nextFloat() * 0.6f) + (rng.nextFloat() - 0.5f) * 0.8f);
        }
    }

    private void spawn(BlockType type, float x, float y, float z,
                       float velX, float velY, float velZ) {
        // MC's particle-texture rule: a grass block chips plain dirt — the
        // green side texture as debris looks wrong (grass_block.json even
        // defines "particle": "block/dirt")
        BlockType texType = switch (type) {
            case GRASS, SNOW_GRASS -> BlockType.DIRT;
            default                -> type;
        };
        float[] uv = atlas.getUV(texType, TextureAtlas.Face.NORTH);
        float tw = uv[2] - uv[0], th = uv[3] - uv[1];
        px[count] = x;  py[count] = y;  pz[count] = z;
        vx[count] = velX; vy[count] = velY; vz[count] = velZ;
        age[count]  = 0f;
        life[count] = 0.35f + rng.nextFloat() * 0.75f; // MC: 4-40 ticks, mostly short
        size[count] = 0.028f + rng.nextFloat() * 0.024f; // tiny chiseled flecks
        // Random quarter-tile patch of the block texture (MC's uo/vo trick)
        u0[count] = uv[0] + tw * 0.75f * rng.nextFloat();
        v0[count] = uv[1] + th * 0.75f * rng.nextFloat();
        du[count] = tw * 0.25f;
        dv[count] = th * 0.25f;
        count++;
    }

    private void copy(int from, int to) {
        px[to] = px[from]; py[to] = py[from]; pz[to] = pz[from];
        vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from];
        age[to] = age[from]; life[to] = life[from]; size[to] = size[from];
        u0[to] = u0[from]; v0[to] = v0[from]; du[to] = du[from]; dv[to] = dv[from];
    }

    private static boolean solid(World world, float x, float y, float z) {
        return world.getBlock((int) Math.floor(x), (int) Math.floor(y),
                              (int) Math.floor(z)).solid;
    }

    public void render(Matrix4f projection, Matrix4f view, float ambient) {
        if (count == 0) return;

        // Camera basis for billboarding (view matrix rows)
        float rx = view.m00(), ry = view.m10(), rz = view.m20();
        float ux = view.m01(), uy = view.m11(), uz = view.m21();

        int vp = 0, ip = 0;
        for (int i = 0; i < count; i++) {
            float t = age[i] / life[i];
            float alpha = t < 0.8f ? 1f : 1f - (t - 0.8f) / 0.2f;
            float s = size[i];
            float a0 = u0[i], b0 = v0[i], a1 = a0 + du[i], b1 = b0 + dv[i];

            float cx = px[i], cy = py[i], cz = pz[i];
            int base = vp / 6;
            vp = putVert(verts, vp, cx - rx*s - ux*s, cy - ry*s - uy*s, cz - rz*s - uz*s, a0, b0, alpha);
            vp = putVert(verts, vp, cx + rx*s - ux*s, cy + ry*s - uy*s, cz + rz*s - uz*s, a1, b0, alpha);
            vp = putVert(verts, vp, cx + rx*s + ux*s, cy + ry*s + uy*s, cz + rz*s + uz*s, a1, b1, alpha);
            vp = putVert(verts, vp, cx - rx*s + ux*s, cy - ry*s + uy*s, cz - rz*s + uz*s, a0, b1, alpha);
            index[ip++] = base;     index[ip++] = base + 1; index[ip++] = base + 2;
            index[ip++] = base + 2; index[ip++] = base + 3; index[ip++] = base;
        }
        mesh.upload(java.util.Arrays.copyOf(verts, vp), java.util.Arrays.copyOf(index, ip));

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        // Depth writes stay ON: the water pass depth-tests against these, so
        // chips in front of water don't get the ocean blended over them
        glDisable(GL_CULL_FACE);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uTexture", 0);
        // EXACTLY the terrain formula (texture * faceLight * ambient) with a
        // 0.75 face light — no additive floor, so chips blend into the night
        // the same way the blocks around them do
        shader.setUniform("uBrightness", 0.75f * ambient);
        atlas.bind(0);
        mesh.render();
        shader.detach();

        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
    }

    private static int putVert(float[] v, int p,
                               float x, float y, float z, float u, float tv, float a) {
        v[p] = x; v[p+1] = y; v[p+2] = z; v[p+3] = u; v[p+4] = tv; v[p+5] = a;
        return p + 6;
    }

    public void cleanup() {
        if (shader != null) shader.cleanup();
        mesh.cleanup();
    }
}
