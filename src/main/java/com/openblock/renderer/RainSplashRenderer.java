package com.openblock.renderer;

import com.openblock.world.BlockType;
import com.openblock.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.stb.STBImage.*;

/**
 * Rain-on-water splash droplets, the way Minecraft actually does it: the
 * splash_0..3 textures are tiny (8x8 with a couple of blue pixels), so a
 * single flat sprite is invisible — instead LOTS of small droplets pop up
 * off the water surface with gravity, and the massed motion is what reads
 * as "rain hitting the water". Everything lives in one fixed pool and one
 * batched mesh, so the cost is a few hundred quads and a single draw call.
 */
public class RainSplashRenderer {
    private static final int   MAX_PARTICLES = 650;
    private static final int   FRAMES        = 4;
    private static final int   TILE          = 16;   // strip tile resolution
    private static final float RADIUS        = 14f;  // spawn ring around the eye
    private static final float SPAWN_RATE    = 650f; // attempts/s at full intensity
    private static final int   MAX_ATTEMPTS  = 60;   // spawn attempts per frame cap
    private static final float DROP_GRAVITY  = -9f;  // droplets hop up then fall back

    private final ShaderProgram shader;
    private final Texture texture; // 4-frame horizontal strip
    private final Mesh mesh = new Mesh();

    // Particle pool (structure-of-arrays; swap-remove keeps it dense)
    private final float[] px = new float[MAX_PARTICLES];
    private final float[] py = new float[MAX_PARTICLES];
    private final float[] pz = new float[MAX_PARTICLES];
    private final float[] vy   = new float[MAX_PARTICLES];
    private final float[] age  = new float[MAX_PARTICLES];
    private final float[] life = new float[MAX_PARTICLES];
    private int count = 0;

    private float spawnAccum = 0f;
    private final Random rng = new Random();

    // Reused build buffers
    private final float[] verts = new float[MAX_PARTICLES * 4 * 6];
    private final int[]   index = new int[MAX_PARTICLES * 6];

    public RainSplashRenderer() {
        shader  = new ShaderProgram("/shaders/particle.vert", "/shaders/particle.frag");
        texture = buildStrip();
    }

    /**
     * Ages the pool and spawns new splashes on sky-exposed water columns near
     * the eye. Call only while it's actually raining at the player's altitude.
     */
    public void update(World world, Vector3f eye, float intensity, float delta) {
        // Age + move droplets; retire finished ones (swap-remove). Droplets
        // that fall back below the surface get depth-culled by the water,
        // which reads as sinking back in.
        for (int i = 0; i < count; ) {
            age[i] += delta;
            if (age[i] >= life[i]) {
                count--;
                px[i] = px[count]; py[i] = py[count]; pz[i] = pz[count];
                vy[i] = vy[count]; age[i] = age[count]; life[i] = life[count];
            } else {
                vy[i] += DROP_GRAVITY * delta;
                py[i] += vy[i] * delta;
                i++;
            }
        }

        spawnAccum += delta * SPAWN_RATE * intensity;
        int attempts = Math.min((int) spawnAccum, MAX_ATTEMPTS);
        spawnAccum -= attempts;
        for (int a = 0; a < attempts && count < MAX_PARTICLES; a++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            float r  = 1.5f + rng.nextFloat() * RADIUS;
            float wx = eye.x + (float) Math.cos(ang) * r;
            float wz = eye.z + (float) Math.sin(ang) * r;
            int ix = (int) Math.floor(wx), iz = (int) Math.floor(wz);
            int surf = world.getSurfaceY(ix, iz);
            if (surf <= 0) continue;
            BlockType top = world.getBlock(ix, surf - 1, iz);
            if (top != BlockType.WATER && top != BlockType.WATER_FLOWING) continue;
            px[count]   = wx;
            // Starts just ABOVE the water's top face (y = surf for still water) —
            // any lower and the water depth prepass culls the billboard entirely.
            py[count]   = surf + 0.04f;
            pz[count]   = wz;
            vy[count]   = 1.3f + rng.nextFloat() * 1.4f; // pop up, gravity pulls back
            age[count]  = 0f;
            life[count] = 0.35f + rng.nextFloat() * 0.25f;
            count++;
        }
    }

    public void render(Matrix4f projection, Matrix4f view, float ambient) {
        if (count == 0) return;

        // Camera basis for billboarding (view matrix rows)
        float rx = view.m00(), ry = view.m10(), rz = view.m20();
        float ux = view.m01(), uy = view.m11(), uz = view.m21();

        int vp = 0, ip = 0;
        for (int i = 0; i < count; i++) {
            float t = age[i] / life[i];
            int frame = Math.min(FRAMES - 1, (int) (t * FRAMES));
            float u0 = frame / (float) FRAMES, u1 = u0 + 1f / (float) FRAMES;
            float s = 0.14f;                  // small droplet, constant size
            // Mostly-solid for the hop, quick fade at the very end
            float alpha = t < 0.7f ? 0.85f : 0.85f * (1f - (t - 0.7f) / 0.3f);

            float cx = px[i], cy = py[i], cz = pz[i];
            int base = (vp / 6);
            vp = putVert(verts, vp, cx - rx*s - ux*s, cy - ry*s - uy*s, cz - rz*s - uz*s, u0, 0f, alpha);
            vp = putVert(verts, vp, cx + rx*s - ux*s, cy + ry*s - uy*s, cz + rz*s - uz*s, u1, 0f, alpha);
            vp = putVert(verts, vp, cx + rx*s + ux*s, cy + ry*s + uy*s, cz + rz*s + uz*s, u1, 1f, alpha);
            vp = putVert(verts, vp, cx - rx*s + ux*s, cy - ry*s + uy*s, cz - rz*s + uz*s, u0, 1f, alpha);
            index[ip++] = base;     index[ip++] = base + 1; index[ip++] = base + 2;
            index[ip++] = base + 2; index[ip++] = base + 3; index[ip++] = base;
        }
        mesh.upload(java.util.Arrays.copyOf(verts, vp), java.util.Arrays.copyOf(index, ip));

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uTexture", 0);
        // Tracks the world light closely so night splashes are dim specks,
        // not glowing sparks against dark water
        shader.setUniform("uBrightness", 0.22f + 0.78f * ambient);
        texture.bind(0);
        mesh.render();
        shader.detach();

        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    private static int putVert(float[] v, int p,
                               float x, float y, float z, float u, float tv, float a) {
        v[p] = x; v[p+1] = y; v[p+2] = z; v[p+3] = u; v[p+4] = tv; v[p+5] = a;
        return p + 6;
    }

    /** Builds a horizontal strip of the 4 splash frames (blank tiles if missing). */
    private static Texture buildStrip() {
        int w = FRAMES * TILE, h = TILE;
        ByteBuffer buf = MemoryUtil.memCalloc(w * h * 4); // transparent
        for (int f = 0; f < FRAMES; f++) {
            blitFrame(buf, w, f, "/textures/particle/splash_" + f + ".png");
        }
        buf.flip();
        Texture t = new Texture(buf, w, h);
        MemoryUtil.memFree(buf);
        return t;
    }

    private static void blitFrame(ByteBuffer buf, int atlasW, int frame, String path) {
        byte[] bytes;
        try (InputStream is = RainSplashRenderer.class.getResourceAsStream(path)) {
            if (is == null) return;
            bytes = is.readAllBytes();
        } catch (IOException e) {
            return;
        }
        ByteBuffer raw = MemoryUtil.memAlloc(bytes.length);
        raw.put(bytes).flip();
        int[] w = {0}, h = {0}, ch = {0};
        stbi_set_flip_vertically_on_load(true);
        ByteBuffer pixels = stbi_load_from_memory(raw, w, h, ch, 4);
        stbi_set_flip_vertically_on_load(false);
        MemoryUtil.memFree(raw);
        if (pixels == null) return;

        int srcW = w[0], srcH = h[0];
        int xStart = frame * TILE;
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
    }

    public void cleanup() {
        if (shader  != null) shader.cleanup();
        if (texture != null) texture.cleanup();
        mesh.cleanup();
    }
}
