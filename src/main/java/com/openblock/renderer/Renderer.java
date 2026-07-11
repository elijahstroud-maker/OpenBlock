package com.openblock.renderer;

import com.openblock.input.InputHandler;
import com.openblock.player.Camera;
import com.openblock.player.Player;
import com.openblock.weather.Weather;
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
    private BlockCrackRenderer crack;
    private Hud hud;
    private WeatherRenderer weatherFx;
    private ItemDropRenderer itemDrops;
    private RainSplashRenderer rainSplash;
    private BlockParticleRenderer blockParticles;
    private SuffocationOverlay suffocation;
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
        crack        = new BlockCrackRenderer();
        hud          = new Hud();
        weatherFx    = new WeatherRenderer();
        itemDrops    = new ItemDropRenderer(atlas);
        rainSplash   = new RainSplashRenderer();
        blockParticles = new BlockParticleRenderer(atlas);
        suffocation  = new SuffocationOverlay(atlas);
        updateProjection(window.width, window.height);
    }

    /** Hooks the hotbar up to the player's inventory (called once from Game). */
    public void attachInventory(com.openblock.player.Inventory inventory) {
        hotbar.attach(inventory, atlas);
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

    public void render(World world, Player player, DayNightCycle dayNight, Weather weather,
                       int screenW, int screenH) {
        Camera camera = player.getCamera();
        int[] targetBlock = player.getTargetBlock();
        long now = System.nanoTime();
        float frameDt = lastRenderNanos > 0 ? (now - lastRenderNanos) / 1_000_000_000f : 0f;
        if (frameDt > 0f) atlas.update(frameDt);
        lastRenderNanos = now;
        float rain = weather.getIntensity();

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
            // Storms grey the sky/fog and pull the fog wall in — the world
            // closes down around the player like Minecraft rain.
            fogColor = weather.skyWithRain(dayNight.getSkyColor(), dayNight.getAmbient());
            fogStart = FOG_START * (1f - 0.35f * rain);
            fogEnd   = FOG_END   * (1f - 0.20f * rain);
            cullDist = fogEnd;
            heightFogDensity = HEIGHT_FOG_DENSITY * (1f + 0.8f * rain);

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
        float ambient = weather.ambientWithRain(dayNight.getAmbient());

        // 1. Sky bodies — skip when underwater (nothing to see); storms fade them out
        if (!underwater) {
            sky.render(projection, view, camera.getPosition(), dayNight, 1f - rain);
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

        // 2a. Dropped items — opaque mini blocks, drawn before the water pass so
        // submerged drops still get the water surface blended over them
        itemDrops.render(projection, view, world.getDrops(), eye, ambient);
        // Falling sand/gravel entities — full-size cubes, same shader/meshes
        itemDrops.renderFalling(projection, view, world.getFallingBlocks(), ambient);

        // 2a'. Block-crack particles (break bursts + mining chips) — before the
        // water pass so chips from underwater mining aren't depth-culled
        if (frameDt > 0f) blockParticles.update(world, frameDt);
        blockParticles.render(projection, view, ambient);

        // 2b. Block outline + mining crack overlay — also before the water pass:
        // the water depth prepass would otherwise cull them on any block that
        // sits below the surface (mining underwater from a boat/shore showed
        // no cracks at all). Drawn here, the water blends naturally over them.
        outline.render(projection, view, targetBlock);
        crack.render(projection, view, player.getBreakTarget(), player.getBreakProgress());
        shader.use(); // restore the chunk shader for the water pass
        atlas.bind(0); // crack.render bound ITS texture to unit 0 — rebind the
                       // atlas or the whole water pass samples the crack strip

        // 2c. Water — transparent pass, rendered after all opaque terrain.
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

        // 3b. Rain/snow curtains around the player (skipped underwater — the
        // surface is the roof). Above the snow line the same storm falls as snow,
        // and snow doesn't splash, so the water-surface ripples pause too.
        if (!underwater && rain > 0.01f) {
            boolean snow = player.getFootY() >= Weather.SNOW_LINE;
            weatherFx.render(world, projection, view, eye, rain, snow, ambient, frameDt);
            if (!snow) {
                rainSplash.update(world, eye, rain, frameDt);
                rainSplash.render(projection, view, ambient);
            }
        }

        // 3c. Buried-head effect: the eye inside an opaque block means every
        // terrain face is culled and the player would x-ray through the world.
        // Draw the actual block cell as a dark cube around the camera instead —
        // true perspective when looking around. Drawn under the HUD so the
        // hearts stay visible while suffocating.
        if (eyeBlock.solid && eyeBlock.opaque) {
            suffocation.render(projection, view, eyeBlock,
                (int) Math.floor(eye.x), (int) Math.floor(eye.y), (int) Math.floor(eye.z));
            // Re-draw the mining cracks: while buried you're mining the very
            // block you're inside, and the dark cube just covered the earlier
            // crack pass. Culling is off in crack.render, so the crack cube's
            // inside faces show — progress stays visible from within.
            crack.render(projection, view, player.getBreakTarget(), player.getBreakProgress());
        }

        // 4. HUD — hotbar, hearts/bubbles/damage flash, version label
        hotbar.render(screenW, screenH);
        hud.render(screenW, screenH, player);
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
        if (crack        != null) crack.cleanup();
        if (hud          != null) hud.cleanup();
        if (weatherFx    != null) weatherFx.cleanup();
        if (itemDrops    != null) itemDrops.cleanup();
        if (rainSplash   != null) rainSplash.cleanup();
        if (blockParticles != null) blockParticles.cleanup();
        if (suffocation  != null) suffocation.cleanup();
    }
}
