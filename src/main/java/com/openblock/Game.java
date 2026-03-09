package com.openblock;

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
    /** Show loading screen until at least this many chunk meshes are ready. */
    private static final int MIN_CHUNKS_TO_START = 289; // full 17×17 render distance

    private Window window;
    private InputHandler input;
    private Renderer renderer;
    private LoadingScreen loadingScreen;
    private World world;
    private Player player;
    private DayNightCycle dayNight;
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

        world = new World();
        player = new Player(world, input);
        // Spawn player above terrain (terrain gen will put surface ~64-112 so start high)
        player.getCamera().getPosition().set(8.0f, 120.0f, 8.0f);
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
        }
        world.update(player.getChunkX(), player.getChunkZ());

        if (loading && world.getLoadedMeshCount() >= MIN_CHUNKS_TO_START) {
            loading = false;
        }

        if (!loading) {
            renderer.updateClouds(delta,
                player.getCamera().getPosition().x,
                player.getCamera().getPosition().z);
            renderer.updateHotbar(input);
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
            Vector3f sky = dayNight.getSkyColor();
            glClearColor(sky.x, sky.y, sky.z, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            renderer.render(world, player.getCamera(), dayNight, window.width, window.height);
        }
    }

    private void cleanup() {
        loadingScreen.cleanup();
        renderer.cleanup();
        world.cleanup();
        window.destroy();
    }
}
