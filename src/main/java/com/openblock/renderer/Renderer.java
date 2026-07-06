package com.openblock.renderer;

import com.openblock.input.InputHandler;
import com.openblock.player.Camera;
import com.openblock.window.Window;
import com.openblock.world.BlockType;
import com.openblock.world.Chunk;
import com.openblock.world.World;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

public class Renderer {
    /** How far terrain actually exists: render distance in blocks. */
    private static final float VIEW_DIST = World.RENDER_DISTANCE * Chunk.SIZE_X;
    // OG-Minecraft fog band: starts at 75% of render distance, opaque just before
    // the loaded-world edge so unloaded void is never visible.
    private static final float FOG_START = VIEW_DIST * 0.75f;
    private static final float FOG_END   = VIEW_DIST * 0.97f;
    // Volumetric ground haze (see chunk.frag): density at/below base height,
    // exponential falloff above it. Base sits just under sea level so haze
    // pools in valleys and over water while peaks stay clear.
    private static final float HEIGHT_FOG_DENSITY = 0.0030f;
    private static final float HEIGHT_FOG_FALLOFF = 0.09f;
    private static final float HEIGHT_FOG_BASE    = 62.0f;
    // Void fog: below y=32 the fog closes in, fully claustrophobic near bedrock.
    private static final float VOID_FOG_TOP_Y  = 32.0f;
    private static final float VOID_FOG_RANGE  = 22.0f;
    private static final Vector3f UNDERWATER_FOG = new Vector3f(0.02f, 0.10f, 0.22f);
    private static final Vector3f CAVE_FOG       = new Vector3f(0.015f, 0.015f, 0.02f);

    private ShaderProgram shader;
    private TextureAtlas atlas;
    private CloudRenderer clouds;
    private SkyRenderer sky;
    private Hotbar hotbar;
    private VersionLabel versionLabel;
    private BlockOutlineRenderer outline;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f model      = new Matrix4f();
    private final Matrix4f projView   = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final List<Chunk> waterChunks = new ArrayList<>();
    private boolean underwater = false;
    private long lastRenderNanos = -1;

    public boolean isUnderwater() { return underwater; }

    public void init(Window window) {
        shader       = new ShaderProgram("/shaders/chunk.vert", "/shaders/chunk.frag");
        atlas        = new TextureAtlas();
        clouds       = new CloudRenderer();
        sky          = new SkyRenderer();
        hotbar       = new Hotbar();
        versionLabel = new VersionLabel();
        outline      = new BlockOutlineRenderer();
        updateProjection(window.width, window.height);
    }

    public void updateProjection(int width, int height) {
        projection.identity().perspective(
            (float) Math.toRadians(70.0f),
            (float) width / height,
            0.1f,
            400.0f
        );
    }

    public void updateClouds(float delta, float playerX, float playerZ) {
        clouds.update(delta, playerX, playerZ);
    }

    public void updateHotbar(InputHandler input) {
        hotbar.update(input);
    }

    public void render(World world, Camera camera, DayNightCycle dayNight, int screenW, int screenH, int[] targetBlock) {
        long now = System.nanoTime();
        if (lastRenderNanos > 0) atlas.update((now - lastRenderNanos) / 1_000_000_000f);
        lastRenderNanos = now;

        // Detect if camera eye is submerged
        org.joml.Vector3f eye = camera.getPosition();
        BlockType eyeBlock = world.getBlock(
            (int) Math.floor(eye.x), (int) Math.floor(eye.y), (int) Math.floor(eye.z));
        underwater = eyeBlock == BlockType.WATER || eyeBlock == BlockType.WATER_FLOWING;

        Matrix4f view = camera.getViewMatrix();
        frustum.set(projection.mul(view, projView));

        Vector3f fogColor;
        float fogStart, fogEnd, heightFogDensity;
        // Distance used for the beyond-fog chunk cull. Void fog shrinks fogEnd but
        // darkens the fog color away from the clear color, so culling against the
        // shrunk distance would punch clear-color holes — cull with the base value.
        float cullDist;
        if (underwater) {
            fogColor = UNDERWATER_FOG;
            fogStart = 0.0f;
            fogEnd   = 16.0f;
            cullDist = fogEnd;
            heightFogDensity = 0.0f;
        } else {
            fogColor = dayNight.getSkyColor();
            fogStart = FOG_START;
            fogEnd   = FOG_END;
            cullDist = fogEnd;
            heightFogDensity = HEIGHT_FOG_DENSITY;

            // OG-Minecraft void fog: the deeper below y=32 the camera goes, the
            // tighter and darker the fog, bottoming out claustrophobic at bedrock.
            float voidF = Math.min(1f, Math.max(0f, (VOID_FOG_TOP_Y - eye.y) / VOID_FOG_RANGE));
            if (voidF > 0f) {
                fogStart += (5f  - fogStart) * voidF;
                fogEnd   += (24f - fogEnd)   * voidF;
                fogColor = new Vector3f(fogColor).lerp(CAVE_FOG, voidF);
                heightFogDensity *= (1f - voidF); // void fog replaces ground haze
            }
        }
        float ambient = dayNight.getAmbient();

        // 1. Sky bodies — skip when underwater (nothing to see)
        if (!underwater) {
            sky.render(projection, view, camera.getPosition(), dayNight);
        }

        // 2. Terrain chunks
        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uFogColor", fogColor);
        shader.setUniform("uFogStart", fogStart);
        shader.setUniform("uFogEnd",   fogEnd);
        shader.setUniform("uAmbient", ambient);
        shader.setUniform("uCameraPos", eye);
        shader.setUniform("uHeightFogDensity", heightFogDensity);
        shader.setUniform("uHeightFogFalloff", HEIGHT_FOG_FALLOFF);
        shader.setUniform("uHeightFogBase",    HEIGHT_FOG_BASE);
        atlas.bind(0);

        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunk.getMesh() == null || chunk.getMesh().isEmpty()) continue;
            if (!chunkVisible(chunk, eye, cullDist)) continue;

            model.identity().translate(
                chunk.getChunkX() * Chunk.SIZE_X,
                0.0f,
                chunk.getChunkZ() * Chunk.SIZE_Z
            );
            shader.setUniform("uModel", model);
            chunk.getMesh().render();
        }

        // 2b. Water — transparent pass, rendered after all opaque terrain.
        // Cull disabled so water is double-sided: side faces stay visible from any
        // angle (and from inside the water), matching Minecraft.
        // Two-pass technique: first a depth-only prepass writes the nearest water
        // surface into the depth buffer, then the color pass replays the same
        // geometry with GL_EQUAL. Exactly one water layer blends per pixel, so
        // stacked faces can never double-blend into darker bands and draw order
        // stops mattering (no sorting needed).
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);

        waterChunks.clear();
        for (Chunk chunk : world.getLoadedChunks()) {
            Mesh wm = chunk.getWaterMesh();
            if (wm == null || wm.isEmpty()) continue;
            if (!chunkVisible(chunk, eye, cullDist)) continue;
            waterChunks.add(chunk);
        }

        glColorMask(false, false, false, false);
        drawWaterChunks();
        glColorMask(true, true, true, true);
        glDepthFunc(GL_EQUAL);
        drawWaterChunks();
        glDepthFunc(GL_LESS);

        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        shader.detach();

        // 3. Clouds (alpha-blended, rendered before outline so clouds can occlude it)
        clouds.render(projection, camera.getViewMatrix(), fogColor, ambient, fogStart, fogEnd);

        // 4. Block outline — drawn after clouds so cloud depth is in the buffer
        outline.render(projection, camera.getViewMatrix(), targetBlock);

        // 5. HUD — hotbar + version label
        hotbar.render(screenW, screenH);
        versionLabel.render(screenW, screenH);
    }

    /** Draws every collected water mesh (used by both the depth prepass and color pass). */
    private void drawWaterChunks() {
        for (Chunk chunk : waterChunks) {
            model.identity().translate(
                chunk.getChunkX() * Chunk.SIZE_X,
                0.0f,
                chunk.getChunkZ() * Chunk.SIZE_Z
            );
            shader.setUniform("uModel", model);
            chunk.getWaterMesh().render();
        }
    }

    /**
     * True if the chunk can contribute pixels: inside the view frustum and not
     * fully behind the fog wall (fully-fogged chunks resolve to the clear color
     * anyway, so skipping them is free).
     */
    private boolean chunkVisible(Chunk chunk, Vector3f eye, float cullDist) {
        float x0 = chunk.getWorldX(), z0 = chunk.getWorldZ();
        float x1 = x0 + Chunk.SIZE_X, z1 = z0 + Chunk.SIZE_Z;

        // Nearest XZ distance from the eye to the chunk footprint (0 if inside).
        // 3D distance is always >= this, so the check is conservative.
        float dx = Math.max(0f, Math.max(x0 - eye.x, eye.x - x1));
        float dz = Math.max(0f, Math.max(z0 - eye.z, eye.z - z1));
        if (dx * dx + dz * dz > cullDist * cullDist) return false;

        return frustum.testAab(x0, 0f, z0, x1, chunk.getMaxNonAirY() + 1f, z1);
    }

    public void cleanup() {
        if (shader       != null) shader.cleanup();
        if (atlas        != null) atlas.cleanup();
        if (clouds       != null) clouds.cleanup();
        if (sky          != null) sky.cleanup();
        if (hotbar       != null) hotbar.cleanup();
        if (versionLabel != null) versionLabel.cleanup();
        if (outline      != null) outline.cleanup();
    }
}
