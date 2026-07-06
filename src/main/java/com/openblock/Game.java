package com.openblock;

import com.openblock.audio.AudioEngine;
import com.openblock.audio.MusicPlayer;
import com.openblock.audio.SoundManager;
import com.openblock.input.InputHandler;
import com.openblock.player.Player;
import com.openblock.renderer.DayNightCycle;
import com.openblock.renderer.LoadingScreen;
import com.openblock.renderer.Renderer;
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
    private boolean loading = true;

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

        world = new World();
        player = new Player(world, input, sounds);
        // Find safe grassy spawn near origin, like Minecraft
        float[] spawn = world.findSafeSpawn();
        player.getCamera().getPosition().set(spawn[0], spawn[1], spawn[2]);
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

            // Escape to quit
            if (glfwGetKey(window.handle, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
                glfwSetWindowShouldClose(window.handle, true);
            }
        }
    }

    private void update(float delta) {
        if (!loading) {
            player.update(delta);
            dayNight.update(delta);

            if (input.isMouseButtonJustPressed(GLFW_MOUSE_BUTTON_LEFT)) {
                player.tryBreakBlock();
            }
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
        }
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
                Vector3f sky = dayNight.getSkyColor();
                glClearColor(sky.x, sky.y, sky.z, 1.0f);
            }
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            renderer.render(world, player.getCamera(), dayNight, window.width, window.height,
                            player.getTargetBlock());
        }
    }

    private void cleanup() {
        loadingScreen.cleanup();
        renderer.cleanup();
        world.cleanup();
        if (music  != null) music.cleanup();
        if (sounds != null) sounds.cleanup();
        if (audio  != null) audio.cleanup();
        window.destroy();
    }
}
