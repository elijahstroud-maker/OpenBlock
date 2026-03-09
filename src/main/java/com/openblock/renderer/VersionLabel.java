package com.openblock.renderer;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * Draws the version string in yellow pixel font in the top-left corner,
 * rendered to a texture via Java AWT.
 */
public class VersionLabel {
    public static final String VERSION = "Pre-Alpha 0.2";

    private static final int   FONT_SIZE    = 11;
    private static final int   SCALE        = 2;   // nearest-neighbour 2x upscale → chunky look
    private static final Color TEXT_COLOR   = new Color(0xFF, 0xFF, 0x55); // bright yellow
    private static final Color SHADOW_COLOR = new Color(0x3F, 0x3F, 0x00); // dark shadow

    private final ShaderProgram shader;
    private final Texture       texture;
    private final int           texW, texH;

    private Mesh    mesh;
    private int     screenW = -1, screenH = -1;
    private final Matrix4f projection = new Matrix4f();

    public VersionLabel() {
        shader = new ShaderProgram("/shaders/ui.vert", "/shaders/text.frag");

        // Measure string size with AWT
        Font font = new Font(Font.MONOSPACED, Font.BOLD, FONT_SIZE);
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        FontMetrics fm = tmp.getGraphics().getFontMetrics(font);
        int strW = fm.stringWidth(VERSION);
        int strH = fm.getHeight();

        // Draw shadow + text onto a small ARGB image
        int imgW = strW + 2;  // +2 for shadow offset
        int imgH = strH + 2;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        // Shadow (offset 1,1)
        g.setColor(SHADOW_COLOR);
        g.drawString(VERSION, 1, fm.getAscent() + 1);
        // Text
        g.setColor(TEXT_COLOR);
        g.drawString(VERSION, 0, fm.getAscent());
        g.dispose();

        // Scale up 2x with nearest-neighbour
        int scaledW = imgW * SCALE;
        int scaledH = imgH * SCALE;
        BufferedImage scaled = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gs = scaled.createGraphics();
        gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        gs.drawImage(img, 0, 0, scaledW, scaledH, null);
        gs.dispose();

        texW = scaledW;
        texH = scaledH;

        // Upload to OpenGL texture
        ByteBuffer buf = MemoryUtil.memAlloc(texW * texH * 4);
        for (int y = 0; y < texH; y++) {
            // Flip vertically for OpenGL (origin bottom-left)
            int srcY = texH - 1 - y;
            for (int x = 0; x < texW; x++) {
                int argb = scaled.getRGB(x, srcY);
                buf.put((byte)((argb >> 16) & 0xFF)); // R
                buf.put((byte)((argb >>  8) & 0xFF)); // G
                buf.put((byte)( argb        & 0xFF)); // B
                buf.put((byte)((argb >> 24) & 0xFF)); // A
            }
        }
        buf.flip();
        texture = new Texture(buf, texW, texH);
        MemoryUtil.memFree(buf);
    }

    public void render(int screenW, int screenH) {
        if (screenW != this.screenW || screenH != this.screenH) {
            this.screenW = screenW;
            this.screenH = screenH;
            projection.identity().setOrtho(0, screenW, screenH, 0, -1, 1);
            rebuildMesh();
        }

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uTexture", 0);
        texture.bind(0);
        mesh.render();
        shader.detach();

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void rebuildMesh() {
        float x0 = 4, y0 = 4;
        float x1 = x0 + texW, y1 = y0 + texH;
        float light = 1.0f;
        // Layout: [x, y, z, u, v, light]
        float[] verts = {
            x0, y0, 0,  0, 1, light,
            x1, y0, 0,  1, 1, light,
            x1, y1, 0,  1, 0, light,
            x0, y1, 0,  0, 0, light,
        };
        int[] idxs = {0, 1, 2, 2, 3, 0};
        if (mesh != null) mesh.cleanup();
        mesh = new Mesh();
        mesh.upload(verts, idxs);
    }

    public void cleanup() {
        if (shader  != null) shader.cleanup();
        if (texture != null) texture.cleanup();
        if (mesh    != null) mesh.cleanup();
    }
}
