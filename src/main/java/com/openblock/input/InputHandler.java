package com.openblock.input;

import static org.lwjgl.glfw.GLFW.*;

public class InputHandler {
    private final long window;
    private final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];

    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private boolean firstMouse = true;

    /** Accumulated mouse delta since last poll(). */
    public float mouseDX = 0;
    public float mouseDY = 0;

    /** Accumulated scroll delta (positive = scroll up). */
    public float scrollDY = 0;

    /** True while the button is held. Index = GLFW_MOUSE_BUTTON_*. */
    private final boolean[] mouseDown    = new boolean[8];
    /** True for exactly one tick after the button transitions to pressed. */
    private final boolean[] mouseClicked = new boolean[8];

    public InputHandler(long window) {
        this.window = window;

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key >= 0 && key <= GLFW_KEY_LAST) {
                if (action == GLFW_PRESS)   keys[key] = true;
                if (action == GLFW_RELEASE) keys[key] = false;
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button >= 0 && button < mouseDown.length) {
                if (action == GLFW_PRESS) {
                    if (!mouseDown[button]) mouseClicked[button] = true;
                    mouseDown[button] = true;
                } else if (action == GLFW_RELEASE) {
                    mouseDown[button] = false;
                }
            }
        });

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (firstMouse) {
                lastMouseX = xpos;
                lastMouseY = ypos;
                firstMouse = false;
            }
            mouseDX += (float)(xpos - lastMouseX);
            mouseDY += (float)(ypos - lastMouseY);
            lastMouseX = xpos;
            lastMouseY = ypos;
        });

        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            scrollDY += (float) yoffset;
        });

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
    }

    /** Call once per tick to snapshot and reset mouse delta. */
    public void poll() {
        // Mouse delta is accumulated in the callback; it's read by Player then reset externally.
        // Key state is maintained by callback.
    }

    /** Reset mouse delta after it has been consumed. */
    public void resetMouseDelta() {
        mouseDX = 0;
        mouseDY = 0;
    }

    /** Reset scroll delta after it has been consumed. */
    public void resetScrollDelta() {
        scrollDY = 0;
    }

    public boolean isKeyDown(int key) {
        if (key < 0 || key > GLFW_KEY_LAST) return false;
        return keys[key];
    }

    /** True if the button was just pressed this tick (one-shot, cleared after reading). */
    public boolean isMouseButtonJustPressed(int button) {
        if (button < 0 || button >= mouseDown.length) return false;
        boolean v = mouseClicked[button];
        mouseClicked[button] = false;
        return v;
    }

    public boolean isMouseButtonDown(int button) {
        if (button < 0 || button >= mouseDown.length) return false;
        return mouseDown[button];
    }
}
