package com.openblock.window;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class Window {
    public final long handle;
    public int width;
    public int height;
    public boolean resized = false;

    public Window(int width, int height, String title) {
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

        // Fullscreen on primary monitor at its native resolution
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode vidMode = glfwGetVideoMode(monitor);
        this.width  = vidMode != null ? vidMode.width()  : width;
        this.height = vidMode != null ? vidMode.height() : height;

        handle = glfwCreateWindow(this.width, this.height, title, monitor, NULL);
        if (handle == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        glfwSetFramebufferSizeCallback(handle, (win, w, h) -> {
            this.width = w;
            this.height = h;
            this.resized = true;
        });

        glfwMakeContextCurrent(handle);
        glfwSwapInterval(1); // vsync
        glfwShowWindow(handle);

        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glClearColor(0.53f, 0.81f, 0.98f, 1.0f); // sky blue
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    public void destroy() {
        glfwDestroyWindow(handle);
        glfwTerminate();
    }
}
