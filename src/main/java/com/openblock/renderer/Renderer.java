package com.openblock.renderer;

import com.openblock.input.InputHandler;
import com.openblock.player.Camera;
import com.openblock.window.Window;
import com.openblock.world.Chunk;
import com.openblock.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Renderer {
    private ShaderProgram shader;
    private TextureAtlas atlas;
    private CloudRenderer clouds;
    private SkyRenderer sky;
    private Hotbar hotbar;
    private VersionLabel versionLabel;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f model      = new Matrix4f();

    public void init(Window window) {
        shader       = new ShaderProgram("/shaders/chunk.vert", "/shaders/chunk.frag");
        atlas        = new TextureAtlas();
        clouds       = new CloudRenderer();
        sky          = new SkyRenderer();
        hotbar       = new Hotbar();
        versionLabel = new VersionLabel();
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

    public void render(World world, Camera camera, DayNightCycle dayNight, int screenW, int screenH) {
        Vector3f fogColor = dayNight.getSkyColor();
        float ambient     = dayNight.getAmbient();

        // 1. Sky bodies (sun/moon) — no depth write, drawn first
        sky.render(projection, camera.getViewMatrix(),
                   camera.getPosition(), dayNight);

        // 2. Terrain chunks
        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", camera.getViewMatrix());
        shader.setUniform("uTexture", 0);
        shader.setUniform("uFogColor", fogColor);
        shader.setUniform("uAmbient", ambient);
        atlas.bind(0);

        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunk.getMesh() == null || chunk.getMesh().isEmpty()) continue;

            model.identity().translate(
                chunk.getChunkX() * Chunk.SIZE_X,
                0.0f,
                chunk.getChunkZ() * Chunk.SIZE_Z
            );
            shader.setUniform("uModel", model);
            chunk.getMesh().render();
        }

        shader.detach();

        // 3. Clouds (alpha-blended, on top of terrain)
        clouds.render(projection, camera.getViewMatrix(), fogColor, ambient);

        // 4. HUD — hotbar + version label
        hotbar.render(screenW, screenH);
        versionLabel.render(screenW, screenH);
    }

    public void cleanup() {
        if (shader  != null) shader.cleanup();
        if (atlas   != null) atlas.cleanup();
        if (clouds  != null) clouds.cleanup();
        if (sky     != null) sky.cleanup();
        if (hotbar        != null) hotbar.cleanup();
        if (versionLabel  != null) versionLabel.cleanup();
    }
}
