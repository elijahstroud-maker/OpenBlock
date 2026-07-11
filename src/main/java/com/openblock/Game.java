package com.openblock;

import com.openblock.audio.AudioEngine;
import com.openblock.audio.MusicPlayer;
import com.openblock.audio.SoundManager;
import com.openblock.input.InputHandler;
import com.openblock.player.Player;
import com.openblock.renderer.ChatOverlay;
import com.openblock.renderer.DayNightCycle;
import com.openblock.renderer.DeathScreen;
import com.openblock.renderer.LoadingScreen;
import com.openblock.renderer.Renderer;
import com.openblock.weather.Weather;
import com.openblock.window.Window;
import com.openblock.world.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

public class Game {
    private static final double TICK_RATE = 1.0 / 60.0;
    /** Show loading screen until every chunk in the render-distance square is meshed. */
    private static final int MIN_CHUNKS_TO_START =
        (2 * World.RENDER_DISTANCE + 1) * (2 * World.RENDER_DISTANCE + 1);

    private Window window;
    private InputHandler input;
    private Renderer renderer;
    private LoadingScreen loadingScreen;
    private World world;
    private Player player;
    private DayNightCycle dayNight;
    private AudioEngine audio;
    private MusicPlayer music;
    private SoundManager sounds;
    private ChatOverlay chat;
    private DeathScreen deathScreen;
    private Weather weather;
    private boolean loading = true;
    /** Cursor is released (visible) while the death screen is up. */
    private boolean cursorReleased = false;

    public void run() {
        try {
            init();
            loop();
        } catch (Throwable t) {
            System.err.println("[OPENBLOCK CRASH] " + t);
            t.printStackTrace(System.err);
        } finally {
            cleanup();
        }
    }

    private void init() {
        window = new Window(1280, 720, "OpenBlock");
        input = new InputHandler(window.handle);
        renderer = new Renderer();
        renderer.init(window);
        loadingScreen = new LoadingScreen();
        dayNight = new DayNightCycle();

        audio = new AudioEngine();
        audio.init();

        sounds = new SoundManager();
        sounds.init();

        music = new MusicPlayer();
        music.init();

        chat = new ChatOverlay();
        deathScreen = new DeathScreen();
        weather = new Weather();

        world = new World();
        player = new Player(world, input, sounds);
        renderer.attachInventory(player.getInventory()); // hotbar shows the real slots
        // Find safe grassy spawn near origin, like Minecraft
        float[] spawn = world.findSafeSpawn();
        player.getCamera().getPosition().set(spawn[0], spawn[1], spawn[2]);
        player.setRespawn(spawn[0], spawn[1], spawn[2]);
    }

    private void loop() {
        double lastTime = glfwGetTime();
        double accumulator = 0.0;

        while (!window.shouldClose()) {
            double now = glfwGetTime();
            double delta = now - lastTime;
            lastTime = now;
            // Cap delta to prevent spiral of death on lag spikes
            accumulator += Math.min(delta, 0.25);

            while (accumulator >= TICK_RATE) {
                input.poll();
                update((float) TICK_RATE);
                accumulator -= TICK_RATE;
            }

            render();
            window.swapBuffers();
            window.pollEvents();

            // Escape to quit — unless the chat is using it to close itself
            if (!chat.isOpen() && !chat.escRecentlyUsed()
                    && glfwGetKey(window.handle, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
                glfwSetWindowShouldClose(window.handle, true);
            }
        }
    }

    private void update(float delta) {
        if (!loading) {
            dayNight.update(delta); // the world keeps turning, dead or alive
            weather.update(delta);

            if (player.isDead()) {
                chat.forceClose(input);
                if (!cursorReleased) {
                    glfwSetInputMode(window.handle, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                    glfwSetCursorPos(window.handle, window.width / 2.0, window.height / 2.0);
                    cursorReleased = true;
                }
                switch (deathScreen.update(input, window.width, window.height)) {
                    case RESPAWN -> {
                        sounds.playClick();
                        player.respawn();
                        glfwSetInputMode(window.handle, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                        input.resetMouseDelta();
                        cursorReleased = false;
                    }
                    case EXIT -> {
                        sounds.playClick();
                        glfwSetWindowShouldClose(window.handle, true);
                    }
                    case NONE -> { }
                }
            } else {
                chat.update(input, delta, player, weather, dayNight);
                player.update(delta);

                // Hold-to-mine; admin mode breaks one block per click instead.
                // Returns neutral while chat is open (text mode).
                player.updateBreaking(delta,
                    input.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT),
                    input.isMouseButtonJustPressed(GLFW_MOUSE_BUTTON_LEFT));
                // Right-click places the selected hotbar block (repeats on hold)
                player.updatePlacing(delta,
                    input.isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT),
                    input.isMouseButtonJustPressed(GLFW_MOUSE_BUTTON_RIGHT));
            }

            String deathMsg = player.popDeathMessage();
            if (deathMsg != null) chat.addMessage(deathMsg);
        }
        world.update(player.getChunkX(), player.getChunkZ(), delta);

        if (loading && world.getLoadedMeshCount() >= MIN_CHUNKS_TO_START) {
            loading = false;
        }

        if (!loading) {
            renderer.updateClouds(delta,
                player.getCamera().getPosition().x,
                player.getCamera().getPosition().z);
            renderer.updateHotbar(input);
            music.update(delta, dayNight.isSunUp());
            // Rain patter: silent when clear or snowing (like MC), and muffled
            // by cover — fading out with depth in caves and mines. Just under
            // the water surface the rain still drums through, quieter and
            // lowpass-muffled, gone entirely a couple of blocks down.
            boolean snowing = player.getFootY() >= Weather.SNOW_LINE;
            float rainTarget = 0f;
            float duck = 1f; // fast-follow underwater volume cut
            if (!snowing) {
                rainTarget = weather.getIntensity() * rainExposure();
                if (renderer.isUnderwater()) {
                    Vector3f eyePos = player.getCamera().getPosition();
                    float depth = world.getSurfaceY((int) Math.floor(eyePos.x),
                                                    (int) Math.floor(eyePos.z)) - eyePos.y;
                    // ~60% volume right under the surface, easing to a 25%
                    // floor so the rain stays faintly audible even deeper —
                    // rainExposure's depth fade still silences it eventually
                    duck = Math.max(0.25f, 0.6f * (1f - Math.max(0f, depth) / 5f));
                }
            }
            sounds.updateRainAmbient(rainTarget, duck, delta, renderer.isUnderwater());
        }
    }

    /**
     * How exposed the player is to the sky: 1 in the open, fading to 0 deep
     * underground. Sampled over a 3x3 spread of columns so standing in a
     * doorway or under a thin roof still lets the rain drum through loudly
     * (up to ~4 blocks of cover costs nothing); by ~22 blocks below the
     * surface the patter is gone entirely.
     */
    private float rainExposure() {
        Vector3f eye = player.getCamera().getPosition();
        float sum = 0f;
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                int surf = world.getSurfaceY((int) Math.floor(eye.x) + dx,
                                             (int) Math.floor(eye.z) + dz);
                float depth = surf - eye.y;
                sum += Math.max(0f, Math.min(1f, 1f - (depth - 4f) / 18f));
            }
        }
        return sum / 9f;
    }

    private void render() {
        if (window.resized) {
            glViewport(0, 0, window.width, window.height);
            renderer.updateProjection(window.width, window.height);
            window.resized = false;
        }

        if (loading) {
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            loadingScreen.render(window.width, window.height);
        } else {
            if (renderer.isUnderwater()) {
                glClearColor(0.02f, 0.10f, 0.22f, 1.0f);
            } else {
                Vector3f sky = weather.skyWithRain(dayNight.getSkyColor(), dayNight.getAmbient());
                glClearColor(sky.x, sky.y, sky.z, 1.0f);
            }
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            renderer.render(world, player, dayNight, weather, window.width, window.height);
            chat.render(window.width, window.height);
            if (player.isDead()) deathScreen.render(input, window.width, window.height);
        }
    }

    private void cleanup() {
        loadingScreen.cleanup();
        renderer.cleanup();
        if (chat != null) chat.cleanup();
        if (deathScreen != null) deathScreen.cleanup();
        world.cleanup();
        if (music  != null) music.cleanup();
        if (sounds != null) sounds.cleanup();
        if (audio  != null) audio.cleanup();
        window.destroy();
    }
}
