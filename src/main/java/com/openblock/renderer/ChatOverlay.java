package com.openblock.renderer;

import com.openblock.input.InputHandler;
import com.openblock.player.Player;
import com.openblock.weather.Weather;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Chat + command bar. T opens it, '/' opens it with a leading slash. Enter
 * submits, Escape closes. Recent messages show above the input line and expire
 * after a few seconds when the chat is closed (Minecraft behaviour).
 *
 * Commands:
 *   /help    — list commands
 *   /admin   — toggle admin mode (invincible + instant breaking)
 *   /weather — clear|rain
 */
public class ChatOverlay {
    private static final int   FONT_SIZE    = 12;
    private static final int   SCALE        = 2;
    private static final int   MAX_MESSAGES = 20;   // kept in memory
    private static final int   SHOW_OPEN    = 10;   // shown while chat is open
    private static final float MESSAGE_TTL  = 6f;   // seconds visible when closed
    /** Box height fits the 2x-scaled text (~36px) with padding; text is centred in it. */
    private static final int   BOX_H        = 40;
    private static final int   LINE_H       = 42;   // vertical spacing between boxes
    private static final int   MARGIN_X     = 8;

    private static class Message {
        final Texture tex;
        final int w, h;
        float age = 0f;
        Message(Texture tex, int w, int h) { this.tex = tex; this.w = w; this.h = h; }
    }

    private final ShaderProgram textShader; // ui.vert + text.frag
    private final ShaderProgram bgShader;   // ui.vert + flash.frag (solid quads)
    private final Matrix4f projection = new Matrix4f();
    private final Deque<Message> messages = new ArrayDeque<>();
    private final Mesh quadMesh = new Mesh(); // reused for every textured/solid quad

    private final StringBuilder buffer = new StringBuilder();
    private boolean open = false;
    /** Blocks the game's ESC-to-quit for a moment after ESC closed the chat. */
    private float escCooldown = 0f;
    private float cursorBlink = 0f;

    private Texture inputTex;
    private int inputTexW, inputTexH;
    private String renderedInput = null;

    public ChatOverlay() {
        textShader = new ShaderProgram("/shaders/ui.vert", "/shaders/text.frag");
        bgShader   = new ShaderProgram("/shaders/ui.vert", "/shaders/flash.frag");
    }

    public boolean isOpen() { return open; }

    /** True briefly after ESC closed the chat, so Game doesn't also quit on it. */
    public boolean escRecentlyUsed() { return escCooldown > 0f; }

    public void update(InputHandler input, float delta, Player player, Weather weather,
                       DayNightCycle dayNight) {
        if (escCooldown > 0f) escCooldown -= delta;
        cursorBlink += delta;
        for (Message m : messages) m.age += delta;

        if (!open) {
            if (input.isKeyJustPressed(GLFW_KEY_T)) {
                openChat(input, "");
            } else if (input.isKeyJustPressed(GLFW_KEY_SLASH)) {
                openChat(input, "/");
            }
            return;
        }

        // --- chat is open: capture typing ---
        buffer.append(input.consumeChars());
        if (buffer.length() > 96) buffer.setLength(96);

        if (input.isKeyJustPressed(GLFW_KEY_BACKSPACE) && buffer.length() > 0) {
            buffer.setLength(buffer.length() - 1);
        }
        if (input.isKeyJustPressed(GLFW_KEY_ESCAPE)) {
            close(input);
            escCooldown = 0.4f;
            return;
        }
        if (input.isKeyJustPressed(GLFW_KEY_ENTER) || input.isKeyJustPressed(GLFW_KEY_KP_ENTER)) {
            String text = buffer.toString().trim();
            close(input);
            if (!text.isEmpty()) submit(text, player, weather, dayNight);
        }
    }

    private void openChat(InputHandler input, String prefill) {
        open = true;
        buffer.setLength(0);
        buffer.append(prefill);
        cursorBlink = 0f;
        input.setTextMode(true); // also drops the 't'/'/' char that opened us
    }

    private void close(InputHandler input) {
        open = false;
        buffer.setLength(0);
        input.setTextMode(false);
    }

    /** Closes the chat if open (used when the death screen takes over). */
    public void forceClose(InputHandler input) {
        if (open) close(input);
    }

    private void submit(String text, Player player, Weather weather, DayNightCycle dayNight) {
        if (!text.startsWith("/")) {
            addMessage("<Player> " + text);
            return;
        }
        String lower = text.toLowerCase();
        if (lower.equals("/help")) {
            addMessage("Commands:");
            addMessage("  /help - show this list");
            addMessage("  /admin - toggle invincibility + instant breaking");
            addMessage("  /weather clear|rain - change the weather");
            addMessage("  /time day|night - jump to midday or midnight");
        } else if (lower.equals("/time") || lower.startsWith("/time ")) {
            String arg = lower.equals("/time") ? "" : lower.substring("/time ".length()).trim();
            switch (arg) {
                case "day"   -> { dayNight.setTime(0.5f); addMessage("Time set to midday"); }
                case "night" -> { dayNight.setTime(0.0f); addMessage("Time set to midnight"); }
                default      -> addMessage("Usage: /time day|night");
            }
        } else if (lower.equals("/admin")) {
            boolean on = player.toggleAdmin();
            addMessage("Admin mode " + (on ? "enabled" : "disabled"));
        } else if (lower.equals("/weather") || lower.startsWith("/weather ")) {
            String arg = lower.equals("/weather") ? "" : lower.substring("/weather ".length()).trim();
            switch (arg) {
                case "clear" -> { weather.setRaining(false); addMessage("Weather set to clear"); }
                case "rain"  -> { weather.setRaining(true);  addMessage("Weather set to rain"); }
                case "snow"  -> {
                    weather.setRaining(true);
                    addMessage("Weather set to rain - it falls as snow above y="
                        + (int) Weather.SNOW_LINE);
                }
                default -> addMessage("Usage: /weather clear|rain");
            }
        } else {
            addMessage("Unknown command: " + text + " (try /help)");
        }
    }

    public void addMessage(String text) {
        BufferedImage img = renderText(text, Color.WHITE);
        Message m = new Message(toTexture(img), img.getWidth() * SCALE, img.getHeight() * SCALE);
        messages.addFirst(m); // newest first (drawn closest to the input line)
        while (messages.size() > MAX_MESSAGES) {
            messages.removeLast().tex.cleanup();
        }
    }

    // ---------- rendering ----------

    public void render(int w, int h) {
        int baseY = Hotbar.panelTop(h) - 44 - LINE_H; // above hearts row

        projection.identity().setOrtho(0, w, h, 0, -1, 1);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Message history (newest at bottom, stacked upward). Text is centred
        // vertically inside each box so descenders never poke past the edge.
        int shown = 0;
        int y = baseY;
        for (Message m : messages) {
            if (open) {
                if (shown >= SHOW_OPEN) break;
            } else if (m.age > MESSAGE_TTL) {
                break; // deque is age-ordered: everything after is older
            }
            y -= LINE_H;
            // Background hugs the text width (min 500 wide, like MC's chat pane)
            // so long lines — e.g. the /help admin entry — stay fully enclosed.
            int boxW = Math.max(500, m.w + 8);
            drawSolid(MARGIN_X - 4, y, MARGIN_X + boxW, y + BOX_H, 0f, 0f, 0f, 0.42f);
            drawTexture(m.tex, MARGIN_X, y + (BOX_H - m.h) / 2, m.w, m.h);
            shown++;
        }

        // Input line
        if (open) {
            String shownText = buffer + (((int) (cursorBlink * 2.5f) % 2 == 0) ? "_" : "");
            if (!shownText.equals(renderedInput)) {
                renderedInput = shownText;
                if (inputTex != null) inputTex.cleanup();
                BufferedImage img = renderText(shownText.isEmpty() ? " " : shownText, Color.WHITE);
                inputTex  = toTexture(img);
                inputTexW = img.getWidth() * SCALE;
                inputTexH = img.getHeight() * SCALE;
            }
            int iy = baseY + 4;
            drawSolid(MARGIN_X - 4, iy, w - MARGIN_X, iy + BOX_H, 0f, 0f, 0f, 0.55f);
            if (inputTex != null) {
                drawTexture(inputTex, MARGIN_X, iy + (BOX_H - inputTexH) / 2, inputTexW, inputTexH);
            }
        }

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void drawSolid(int x0, int y0, int x1, int y1, float r, float g, float b, float a) {
        bgShader.use();
        bgShader.setUniform("uProjection", projection);
        bgShader.setUniform("uColor", r, g, b, a);
        uploadQuad(x0, y0, x1, y1);
        quadMesh.render();
        bgShader.detach();
    }

    private void drawTexture(Texture tex, int x, int y, int w, int h) {
        textShader.use();
        textShader.setUniform("uProjection", projection);
        textShader.setUniform("uTexture", 0);
        tex.bind(0);
        uploadQuad(x, y, x + w, y + h);
        quadMesh.render();
        textShader.detach();
    }

    private void uploadQuad(float x0, float y0, float x1, float y1) {
        float[] verts = {
            x0, y0, 0,  0, 1, 1,
            x1, y0, 0,  1, 1, 1,
            x1, y1, 0,  1, 0, 1,
            x0, y1, 0,  0, 0, 1,
        };
        quadMesh.upload(verts, new int[]{0, 1, 2, 2, 3, 0});
    }

    // ---------- text rasterising (AWT, chunky 2x like VersionLabel) ----------

    private static BufferedImage renderText(String text, Color color) {
        Font font = new Font(Font.MONOSPACED, Font.BOLD, FONT_SIZE);
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        FontMetrics fm = tmp.getGraphics().getFontMetrics(font);
        int strW = Math.max(1, fm.stringWidth(text));
        int strH = fm.getHeight();

        BufferedImage img = new BufferedImage(strW + 2, strH + 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(new Color(0, 0, 0, 200));
        g.drawString(text, 1, fm.getAscent() + 1);
        g.setColor(color);
        g.drawString(text, 0, fm.getAscent());
        g.dispose();
        return img;
    }

    private static Texture toTexture(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        for (int y = 0; y < h; y++) {
            int srcY = h - 1 - y; // flip for GL
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, srcY);
                buf.put((byte) ((argb >> 16) & 0xFF));
                buf.put((byte) ((argb >>  8) & 0xFF));
                buf.put((byte) ( argb        & 0xFF));
                buf.put((byte) ((argb >> 24) & 0xFF));
            }
        }
        buf.flip();
        Texture t = new Texture(buf, w, h);
        MemoryUtil.memFree(buf);
        return t;
    }

    public void cleanup() {
        if (textShader != null) textShader.cleanup();
        if (bgShader   != null) bgShader.cleanup();
        if (inputTex   != null) inputTex.cleanup();
        for (Message m : messages) m.tex.cleanup();
        messages.clear();
        quadMesh.cleanup();
    }
}
