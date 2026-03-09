package com.openblock.renderer;

import com.openblock.input.InputHandler;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Renders a 9-slot hotbar.
 * Selected slot changes via scroll wheel or number keys 1-9.
 *
 * Shader trick: the 6-float vertex layout [x,y,z, u,v, light] is reused so
 * that u = per-vertex alpha, v = unused, light = brightness.
 * ui.frag outputs: vec4(brightness, brightness, brightness, alpha).
 */
public class Hotbar {
    public static final int SLOTS = 9;

    // Layout constants
    private static final int SLOT_SIZE   = 56;  // px per slot cell
    private static final int SLOT_PAD    = 4;   // inner content inset
    private static final int SLOT_SEP    = 2;   // gap between slots (shows panel bg)
    private static final int PANEL_PAD   = 6;   // panel edge → first slot
    private static final int BOTTOM_PAD  = 10;  // panel bottom from screen edge

    // Colours (brightness, alpha) for each layer
    private static final float PANEL_B = 0.08f, PANEL_A = 0.80f;
    private static final float OUTER_B = 0.05f, OUTER_A = 0.90f;
    private static final float INNER_B = 0.38f, INNER_A = 0.85f;
    private static final float SEL_B   = 1.00f, SEL_A   = 1.00f;

    private final ShaderProgram shader;
    private final Texture whiteTexture;
    private final Matrix4f projection = new Matrix4f();

    private Mesh mesh;
    private int screenW = -1, screenH = -1;
    private int selectedSlot = 0;
    private int builtSlot    = -1;

    public Hotbar() {
        shader       = new ShaderProgram("/shaders/ui.vert", "/shaders/ui.frag");
        whiteTexture = buildWhiteTexture();
    }

    private static Texture buildWhiteTexture() {
        ByteBuffer buf = MemoryUtil.memAlloc(4);
        buf.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
        buf.flip();
        Texture t = new Texture(buf, 1, 1);
        MemoryUtil.memFree(buf);
        return t;
    }

    /** Call each tick; handles scroll wheel and number keys 1–9. */
    public void update(InputHandler input) {
        // Scroll wheel
        if (input.scrollDY != 0) {
            int d = input.scrollDY > 0 ? -1 : 1;
            selectedSlot = Math.floorMod(selectedSlot + d, SLOTS);
            input.resetScrollDelta();
        }
        // Number keys 1–9
        for (int i = 0; i < SLOTS; i++) {
            if (input.isKeyDown(GLFW_KEY_1 + i)) {
                selectedSlot = i;
                break;
            }
        }
    }

    public int getSelectedSlot() { return selectedSlot; }

    public void render(int w, int h) {
        if (w != screenW || h != screenH || selectedSlot != builtSlot) {
            screenW   = w;
            screenH   = h;
            builtSlot = selectedSlot;
            projection.identity().setOrtho(0, w, h, 0, -1, 1);
            rebuildMesh(w, h);
        }

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uTexture", 0);
        whiteTexture.bind(0);
        mesh.render();
        shader.detach();

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void rebuildMesh(int w, int h) {
        int totalSlotsW = SLOTS * SLOT_SIZE + (SLOTS - 1) * SLOT_SEP;
        int panelW      = totalSlotsW + 2 * PANEL_PAD;
        int panelH      = SLOT_SIZE   + 2 * PANEL_PAD;
        int panelX      = (w - panelW) / 2;
        int panelY      = h - panelH - BOTTOM_PAD;
        int slotsX      = panelX + PANEL_PAD;
        int slotsY      = panelY + PANEL_PAD;

        ArrayList<Float>   verts = new ArrayList<>();
        ArrayList<Integer> idxs  = new ArrayList<>();
        int vi = 0;

        // --- Background panel ---
        vi += rect(verts, idxs, vi, panelX, panelY, panelX+panelW, panelY+panelH,
                   PANEL_B, PANEL_A);

        // --- Slot cells ---
        for (int i = 0; i < SLOTS; i++) {
            int x0 = slotsX + i * (SLOT_SIZE + SLOT_SEP);
            int y0 = slotsY;
            int x1 = x0 + SLOT_SIZE;
            int y1 = y0 + SLOT_SIZE;

            // Outer dark border
            vi += rect(verts, idxs, vi, x0, y0, x1, y1, OUTER_B, OUTER_A);
            // Inner lighter fill
            vi += rect(verts, idxs, vi,
                       x0 + SLOT_PAD, y0 + SLOT_PAD,
                       x1 - SLOT_PAD, y1 - SLOT_PAD,
                       INNER_B, INNER_A);
        }

        // --- Selected slot highlight (white border, 3px thick) ---
        {
            int i  = selectedSlot;
            int bx = slotsX + i * (SLOT_SIZE + SLOT_SEP);
            int by = slotsY;
            // Draw 1px outside the cell
            int x0 = bx - 1, y0 = by - 1;
            int x1 = bx + SLOT_SIZE + 1, y1 = by + SLOT_SIZE + 1;
            int t  = 3;
            vi += rect(verts, idxs, vi, x0,   y0,   x1,   y0+t, SEL_B, SEL_A); // top
            vi += rect(verts, idxs, vi, x0,   y1-t, x1,   y1,   SEL_B, SEL_A); // bottom
            vi += rect(verts, idxs, vi, x0,   y0+t, x0+t, y1-t, SEL_B, SEL_A); // left
            vi += rect(verts, idxs, vi, x1-t, y0+t, x1,   y1-t, SEL_B, SEL_A); // right
        }

        float[] va = new float[verts.size()];
        for (int i = 0; i < va.length; i++) va[i] = verts.get(i);
        int[] ia = new int[idxs.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = idxs.get(i);

        if (mesh != null) mesh.cleanup();
        mesh = new Mesh();
        mesh.upload(va, ia);
    }

    /**
     * One solid quad. Vertex layout: [x, y, z, alpha(u), 0(v), brightness(light)].
     * ui.frag: fragColor = vec4(brightness, brightness, brightness, u).
     */
    private static int rect(ArrayList<Float> v, ArrayList<Integer> idx,
                             int base, int x0, int y0, int x1, int y1,
                             float brightness, float alpha) {
        v.add((float)x0); v.add((float)y0); v.add(0f); v.add(alpha); v.add(0f); v.add(brightness);
        v.add((float)x1); v.add((float)y0); v.add(0f); v.add(alpha); v.add(0f); v.add(brightness);
        v.add((float)x1); v.add((float)y1); v.add(0f); v.add(alpha); v.add(0f); v.add(brightness);
        v.add((float)x0); v.add((float)y1); v.add(0f); v.add(alpha); v.add(0f); v.add(brightness);
        idx.add(base); idx.add(base+1); idx.add(base+2);
        idx.add(base+2); idx.add(base+3); idx.add(base);
        return 4;
    }

    public void cleanup() {
        if (shader       != null) shader.cleanup();
        if (whiteTexture != null) whiteTexture.cleanup();
        if (mesh         != null) mesh.cleanup();
    }
}
