package com.openblock.input;

import static org.lwjgl.glfw.GLFW.*;

public class InputHandler {
    private final long window;
    private final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    /** True for one read after the key transitions to pressed (or repeats — used for backspace). */
    private final boolean[] keyClicked = new boolean[GLFW_KEY_LAST + 1];
    /** Printable characters typed since last consume (for the chat box). */
    private final StringBuilder charQueue = new StringBuilder();
    /**
     * Text mode: chat is capturing the keyboard. Gameplay reads (isKeyDown,
     * mouse buttons, mouse deltas) return neutral values so the player stops
     * moving/looking/breaking while typing. Edge reads (isKeyJustPressed)
     * still work — chat itself needs Enter/Backspace/Escape.
     */
    private boolean textMode = false;
    /**
     * UI capture: a mouse-driven screen (inventory) owns the input. Same
     * gameplay freeze as text mode — movement keys, mouse buttons, and look
     * deltas all read neutral — but typed characters aren't collected. Edge
     * reads (isKeyJustPressed / isMouseButtonJustPressedRaw) still work; the
     * screen itself needs E/Escape and slot clicks.
     */
    private boolean uiCapture = false;

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
                if (action == GLFW_PRESS)   { keys[key] = true; keyClicked[key] = true; }
                if (action == GLFW_REPEAT)  keyClicked[key] = true; // key-repeat for backspace etc.
                if (action == GLFW_RELEASE) keys[key] = false;
            }
        });

        glfwSetCharCallback(window, (win, codepoint) -> {
            // Printable ASCII only — the AWT chat font covers this range
            if (codepoint >= 32 && codepoint < 127) {
                charQueue.append((char) codepoint);
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
        if (textMode || uiCapture) {
            // Chat/inventory is capturing input — don't let accumulated mouse
            // movement spin the camera when it's consumed by Player.
            mouseDX = 0;
            mouseDY = 0;
        }
    }

    /** Last cursor position (meaningful when the cursor is visible, e.g. death screen). */
    public float getMouseX() { return (float) lastMouseX; }
    public float getMouseY() { return (float) lastMouseY; }

    /** Key-held read that ignores text/UI capture (inventory shift-click). */
    public boolean isKeyDownRaw(int key) {
        if (key < 0 || key > GLFW_KEY_LAST) return false;
        return keys[key];
    }

    /** Mouse-held read that ignores text/UI capture (inventory drag-split). */
    public boolean isMouseButtonDownRaw(int button) {
        if (button < 0 || button >= mouseDown.length) return false;
        return mouseDown[button];
    }

    /** Mouse click read that ignores text mode (death screen buttons). */
    public boolean isMouseButtonJustPressedRaw(int button) {
        if (button < 0 || button >= mouseDown.length) return false;
        boolean v = mouseClicked[button];
        mouseClicked[button] = false;
        return v;
    }

    // ---------- text mode (chat) ----------

    public void setTextMode(boolean on) {
        textMode = on;
        // Drop any pending typed characters: prevents the 't' or '/' that OPENED
        // the chat from appearing in the input line, and stale chars on close.
        charQueue.setLength(0);
    }

    public boolean isTextMode() { return textMode; }

    // ---------- UI capture (inventory screen) ----------

    public void setUiCapture(boolean on) { uiCapture = on; }

    public boolean isUiCapture() { return uiCapture; }

    /** Drops all pending one-shot key presses (leaving a UI screen — keys
     *  pressed while it was up shouldn't fire into gameplay afterwards). */
    public void clearKeyEdges() {
        java.util.Arrays.fill(keyClicked, false);
    }

    /** Returns and clears all printable characters typed since the last call. */
    public String consumeChars() {
        String s = charQueue.toString();
        charQueue.setLength(0);
        return s;
    }

    /**
     * One-shot: true once per key press (and once per OS key-repeat, so holding
     * backspace keeps deleting). Works in text mode too — chat needs it.
     */
    public boolean isKeyJustPressed(int key) {
        if (key < 0 || key > GLFW_KEY_LAST) return false;
        boolean v = keyClicked[key];
        keyClicked[key] = false;
        return v;
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
        if (textMode || uiCapture) return false; // chat/inventory is capturing the keyboard
        if (key < 0 || key > GLFW_KEY_LAST) return false;
        return keys[key];
    }

    /** True if the button was just pressed this tick (one-shot, cleared after reading). */
    public boolean isMouseButtonJustPressed(int button) {
        if (textMode || uiCapture) return false;
        if (button < 0 || button >= mouseDown.length) return false;
        boolean v = mouseClicked[button];
        mouseClicked[button] = false;
        return v;
    }

    public boolean isMouseButtonDown(int button) {
        if (textMode || uiCapture) return false;
        if (button < 0 || button >= mouseDown.length) return false;
        return mouseDown[button];
    }
}
