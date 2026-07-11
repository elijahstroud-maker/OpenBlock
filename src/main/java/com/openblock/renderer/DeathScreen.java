package com.openblock.renderer;

import com.openblock.input.InputHandler;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.opengl.GL11.*;

/**
 * Minecraft-style death screen: deep red overlay, big "You died!" title, and
 * two gray bevelled buttons — Respawn and Exit Game. The cursor is released
 * while this is showing (Game handles the cursor mode switch).
 */
public class DeathScreen {

    public enum Action { NONE, RESPAWN, EXIT }

    private static final int BTN_W = 400;
    private static final int BTN_H = 48;

    private final ShaderProgram textShader; // ui.vert + text.frag
    private final ShaderProgram bgShader;   // ui.vert + flash.frag
    private final Matrix4f projection = new Matrix4f();
    private final Mesh quadMesh = new Mesh();

    private final Texture titleTex;   private final int titleW, titleH;
    private final Texture respawnTex; private final int respawnW, respawnH;
    private final Texture exitTex;    private final int exitW, exitH;

    public DeathScreen() {
        textShader = new ShaderProgram("/shaders/ui.vert", "/shaders/text.frag");
        bgShader   = new ShaderProgram("/shaders/ui.vert", "/shaders/flash.frag");

        int[] wh = new int[2];
        titleTex   = makeText("You died!", 28, 3, wh); titleW = wh[0];   titleH = wh[1];
        respawnTex = makeText("Respawn",   13, 2, wh); respawnW = wh[0]; respawnH = wh[1];
        exitTex    = makeText("Exit Game", 13, 2, wh); exitW = wh[0];    exitH = wh[1];
    }

    /** Polls hover/click state; returns the chosen action for this frame. */
    public Action update(InputHandler input, int w, int h) {
        boolean clicked = input.isMouseButtonJustPressedRaw(GLFW_MOUSE_BUTTON_LEFT);
        if (!clicked) return Action.NONE;
        float mx = input.getMouseX(), my = input.getMouseY();
        if (inButton(mx, my, w, respawnY(h))) return Action.RESPAWN;
        if (inButton(mx, my, w, exitY(h)))    return Action.EXIT;
        return Action.NONE;
    }

    public void render(InputHandler input, int w, int h) {
        projection.identity().setOrtho(0, w, h, 0, -1, 1);
        float mx = input.getMouseX(), my = input.getMouseY();

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Deep red full-screen overlay
        drawSolid(0, 0, w, h, 0.35f, 0.0f, 0.0f, 0.55f);

        // Title, centered in the upper third
        drawTexture(titleTex, (w - titleW) / 2, h / 3 - titleH / 2, titleW, titleH);

        // Buttons
        drawButton(w, respawnY(h), respawnTex, respawnW, respawnH,
                   inButton(mx, my, w, respawnY(h)));
        drawButton(w, exitY(h), exitTex, exitW, exitH,
                   inButton(mx, my, w, exitY(h)));

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private static int respawnY(int h) { return h / 2 + 20; }
    private static int exitY(int h)    { return h / 2 + 20 + BTN_H + 16; }

    private static boolean inButton(float mx, float my, int w, int btnY) {
        int x0 = (w - BTN_W) / 2;
        return mx >= x0 && mx <= x0 + BTN_W && my >= btnY && my <= btnY + BTN_H;
    }

    /** MC-style button: dark border, gray body with bevel, lighter when hovered. */
    private void drawButton(int w, int y, Texture label, int lw, int lh, boolean hover) {
        int x0 = (w - BTN_W) / 2, x1 = x0 + BTN_W, y1 = y + BTN_H;
        float body = hover ? 0.58f : 0.42f;

        drawSolid(x0 - 2, y - 2, x1 + 2, y1 + 2, 0f, 0f, 0f, 0.9f);          // border
        drawSolid(x0, y, x1, y1, body, body, body, 1f);                      // body
        drawSolid(x0, y, x1, y + 3, body + 0.25f, body + 0.25f, body + 0.25f, 1f); // top bevel
        drawSolid(x0, y1 - 3, x1, y1, body - 0.20f, body - 0.20f, body - 0.20f, 1f); // bottom bevel

        drawTexture(label, (w - lw) / 2, y + (BTN_H - lh) / 2, lw, lh);
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

    /** Rasterises white text with shadow at the given font size and pixel scale. */
    private static Texture makeText(String text, int fontSize, int scale, int[] outWH) {
        Font font = new Font(Font.MONOSPACED, Font.BOLD, fontSize);
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        FontMetrics fm = tmp.getGraphics().getFontMetrics(font);
        int strW = Math.max(1, fm.stringWidth(text));
        int strH = fm.getHeight();

        BufferedImage img = new BufferedImage(strW + 2, strH + 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(new Color(0, 0, 0, 210));
        g.drawString(text, 1, fm.getAscent() + 1);
        g.setColor(Color.WHITE);
        g.drawString(text, 0, fm.getAscent());
        g.dispose();

        int w = img.getWidth(), h = img.getHeight();
        outWH[0] = w * scale;
        outWH[1] = h * scale;

        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        for (int y = 0; y < h; y++) {
            int srcY = h - 1 - y;
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
        if (titleTex   != null) titleTex.cleanup();
        if (respawnTex != null) respawnTex.cleanup();
        if (exitTex    != null) exitTex.cleanup();
        quadMesh.cleanup();
    }
}
