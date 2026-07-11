package com.openblock.renderer;

import com.openblock.input.InputHandler;
import com.openblock.player.Inventory;
import com.openblock.world.BlockType;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Renders a 9-slot hotbar showing the player's inventory: isometric mini
 * block icons drawn from the terrain atlas, with Minecraft-style stack
 * counts bottom-right. Selected slot changes via scroll wheel or keys 1-9.
 *
 * Shader trick (frame pass): the 6-float vertex layout [x,y,z, u,v, light]
 * is reused so that u = per-vertex alpha, v = unused, light = brightness.
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

    // Colours (brightness, alpha) — Minecraft-style translucent dark strip with
    // inset slot bevels (dark top/left, light bottom/right) + white selector.
    private static final float BORDER_B = 0.00f, BORDER_A = 0.85f; // outer black border
    private static final float PANEL_B  = 0.10f, PANEL_A  = 0.55f; // translucent body
    private static final float SLOT_B   = 0.16f, SLOT_A   = 0.50f; // slot cell fill
    private static final float BEV_DK_B = 0.03f, BEV_DK_A = 0.80f; // bevel shadow (top/left)
    private static final float BEV_LT_B = 0.42f, BEV_LT_A = 0.65f; // bevel light (bottom/right)
    private static final float SEL_B    = 1.00f, SEL_A    = 1.00f; // white selector frame
    private static final float SEL_DK_B = 0.05f, SEL_DK_A = 0.90f; // selector outer shadow

    /** Full width of the hotbar strip in pixels (used by the HUD for alignment). */
    public static int panelWidth() {
        return SLOTS * SLOT_SIZE + (SLOTS - 1) * SLOT_SEP + 2 * PANEL_PAD;
    }

    /** Y of the top edge of the hotbar strip. */
    public static int panelTop(int screenH) {
        return screenH - (SLOT_SIZE + 2 * PANEL_PAD) - BOTTOM_PAD;
    }

    private final ShaderProgram shader;
    private final ShaderProgram iconShader; // ui.vert + text.frag: atlas icons + count labels
    private final Texture whiteTexture;
    private final Matrix4f projection = new Matrix4f();

    private Mesh mesh;
    private Mesh iconMesh;
    private int screenW = -1, screenH = -1;
    private int selectedSlot = 0;
    private int builtSlot    = -1;
    private int builtRevision = -1;

    private Inventory inventory;   // attached by the Renderer once the player exists
    private TextureAtlas atlas;

    /** A rasterised stack-count label and its pixel size (pre-2x-scale). */
    private record Label(Texture tex, int w, int h) { }
    /** Cached stack-count labels, keyed by count (2..64). */
    private final Map<Integer, Label> countTex = new HashMap<>();
    /** Screen position of each slot's count label for the current layout. */
    private final int[][] countPos = new int[SLOTS][2];
    private final int[]   countVal = new int[SLOTS];
    private final Mesh labelMesh = new Mesh(); // scratch quad reused per label

    public Hotbar() {
        shader       = new ShaderProgram("/shaders/ui.vert", "/shaders/ui.frag");
        iconShader   = new ShaderProgram("/shaders/ui.vert", "/shaders/text.frag");
        whiteTexture = buildWhiteTexture();
    }

    /** Hooks the hotbar up to the player's inventory and the block atlas. */
    public void attach(Inventory inventory, TextureAtlas atlas) {
        this.inventory = inventory;
        this.atlas     = atlas;
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
        if (inventory != null) inventory.setSelected(selectedSlot);
    }

    public int getSelectedSlot() { return selectedSlot; }

    public void render(int w, int h) {
        int revision = inventory != null ? inventory.getRevision() : -1;
        // The pickup pop animates the icon scale, so rebuild every frame while
        // one is running (9 icons — negligible).
        boolean popping = inventory != null && inventory.anyPopping();
        if (w != screenW || h != screenH || selectedSlot != builtSlot
                || revision != builtRevision || popping) {
            screenW       = w;
            screenH       = h;
            builtSlot     = selectedSlot;
            builtRevision = revision;
            projection.identity().setOrtho(0, w, h, 0, -1, 1);
            rebuildMesh(w, h);
            rebuildIcons(w, h);
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

        // Item icons (atlas-textured isometric mini blocks) + stack counts
        if (iconMesh != null && !iconMesh.isEmpty()) {
            iconShader.use();
            iconShader.setUniform("uProjection", projection);
            iconShader.setUniform("uTexture", 0);
            atlas.bind(0);
            iconMesh.render();
            iconShader.detach();
        }
        for (int i = 0; i < SLOTS; i++) {
            if (countVal[i] > 1) {
                Label l = countTexFor(countVal[i]);
                drawLabel(l, countPos[i][0] - l.w() * 2, countPos[i][1] - l.h() * 2);
            }
        }

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    /** Draws one count label; the position is its top-left (already right-aligned). */
    private void drawLabel(Label label, int x, int y) {
        iconShader.use();
        iconShader.setUniform("uProjection", projection);
        iconShader.setUniform("uTexture", 0);
        label.tex().bind(0);
        float x1 = x + label.w() * 2, y1 = y + label.h() * 2;
        labelMesh.upload(new float[]{
            x,  y,  0, 0, 1, 1,
            x1, y,  0, 1, 1, 1,
            x1, y1, 0, 1, 0, 1,
            x,  y1, 0, 0, 0, 1,
        }, new int[]{0, 1, 2, 2, 3, 0});
        labelMesh.render();
        iconShader.detach();
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

        // --- Strip: black outer border + translucent dark body ---
        vi += rect(verts, idxs, vi, panelX - 2, panelY - 2,
                   panelX + panelW + 2, panelY + panelH + 2, BORDER_B, BORDER_A);
        vi += rect(verts, idxs, vi, panelX, panelY, panelX + panelW, panelY + panelH,
                   PANEL_B, PANEL_A);

        // --- Slot cells: inset bevel (dark top/left, light bottom/right) ---
        for (int i = 0; i < SLOTS; i++) {
            int x0 = slotsX + i * (SLOT_SIZE + SLOT_SEP);
            int y0 = slotsY;
            int x1 = x0 + SLOT_SIZE;
            int y1 = y0 + SLOT_SIZE;
            int b  = 2; // bevel thickness

            vi += rect(verts, idxs, vi, x0, y0, x1, y1, SLOT_B, SLOT_A);          // body
            vi += rect(verts, idxs, vi, x0, y0, x1, y0 + b, BEV_DK_B, BEV_DK_A);  // top shadow
            vi += rect(verts, idxs, vi, x0, y0, x0 + b, y1, BEV_DK_B, BEV_DK_A);  // left shadow
            vi += rect(verts, idxs, vi, x0, y1 - b, x1, y1, BEV_LT_B, BEV_LT_A);  // bottom light
            vi += rect(verts, idxs, vi, x1 - b, y0, x1, y1, BEV_LT_B, BEV_LT_A);  // right light
        }

        // --- Selector: chunky white frame extending past the slot (MC-style) ---
        {
            int i  = selectedSlot;
            int bx = slotsX + i * (SLOT_SIZE + SLOT_SEP);
            int by = slotsY;
            int x0 = bx - 4, y0 = by - 4;
            int x1 = bx + SLOT_SIZE + 4, y1 = by + SLOT_SIZE + 4;
            int t  = 4;
            // Dark shadow just outside the white frame so it pops on bright terrain
            vi += rect(verts, idxs, vi, x0-1, y0-1, x1+1, y0,   SEL_DK_B, SEL_DK_A);
            vi += rect(verts, idxs, vi, x0-1, y1,   x1+1, y1+1, SEL_DK_B, SEL_DK_A);
            vi += rect(verts, idxs, vi, x0-1, y0,   x0,   y1,   SEL_DK_B, SEL_DK_A);
            vi += rect(verts, idxs, vi, x1,   y0,   x1+1, y1,   SEL_DK_B, SEL_DK_A);
            // White frame
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
     * Rebuilds the icon mesh: an isometric mini block per occupied slot
     * (sheared atlas tile for the top, shaded left/right faces — the classic
     * Minecraft item look), and records where each stack count label goes.
     */
    private void rebuildIcons(int w, int h) {
        for (int i = 0; i < SLOTS; i++) countVal[i] = 0;
        if (inventory == null || atlas == null) {
            if (iconMesh != null) { iconMesh.cleanup(); iconMesh = null; }
            return;
        }

        int totalSlotsW = SLOTS * SLOT_SIZE + (SLOTS - 1) * SLOT_SEP;
        int panelW      = totalSlotsW + 2 * PANEL_PAD;
        int slotsX      = (w - panelW) / 2 + PANEL_PAD;
        int slotsY      = h - (SLOT_SIZE + 2 * PANEL_PAD) - BOTTOM_PAD + PANEL_PAD;

        ArrayList<Float>   verts = new ArrayList<>();
        ArrayList<Integer> idxs  = new ArrayList<>();
        int vi = 0;

        for (int i = 0; i < SLOTS; i++) {
            BlockType type = inventory.getType(i);
            if (type == null) continue;
            countVal[i] = inventory.getCount(i);

            int x0 = slotsX + i * (SLOT_SIZE + SLOT_SEP);
            float cx = x0 + SLOT_SIZE / 2f;
            float cy = slotsY + SLOT_SIZE / 2f;
            // Bottom-right anchor for the stack count (right-aligned at draw time)
            countPos[i][0] = x0 + SLOT_SIZE - 3;
            countPos[i][1] = slotsY + SLOT_SIZE - 2;

            // Isometric block: top diamond + two visible faces, in TRUE dimetric
            // proportions (30° pitch / 45° yaw — Minecraft's GUI projection):
            // the top diamond is 2:1 wide, and the vertical faces are
            // s * sqrt(1.5) ≈ 1.22 * s tall. Anything squatter reads as a
            // squashed tile instead of a cube.
            // scaled up briefly by the pickup pop (grows ~1.3x, eases back)
            float s  = 17f * inventory.popScale(i); // block half-width
            float hh = s * 0.5f;            // top-diamond half-height
            float fh = s * 1.22f;           // vertical face height
            float topY = cy - (hh + fh / 2f); // centre the icon in the slot
            float nX = cx,     nY = topY;             // top apex
            float eX = cx + s, eY = topY + hh;
            float sX = cx,     sY = topY + 2f * hh;   // bottom of the top face
            float wX = cx - s, wY = topY + hh;

            float[] top   = atlas.getUV(type, TextureAtlas.Face.TOP);
            float[] north = atlas.getUV(type, TextureAtlas.Face.NORTH);
            float[] east  = atlas.getUV(type, TextureAtlas.Face.EAST);

            // Top face (light 1.0): W→N→E→S sheared tile
            vi += quad(verts, idxs, vi,
                wX, wY, top[0], top[1],   nX, nY, top[2], top[1],
                eX, eY, top[2], top[3],   sX, sY, top[0], top[3], 1.0f);
            // Left face (light 0.8)
            vi += quad(verts, idxs, vi,
                wX, wY, north[0], north[1],       sX, sY, north[2], north[1],
                sX, sY + fh, north[2], north[3],  wX, wY + fh, north[0], north[3], 0.8f);
            // Right face (light 0.6)
            vi += quad(verts, idxs, vi,
                sX, sY, east[0], east[1],         eX, eY, east[2], east[1],
                eX, eY + fh, east[2], east[3],    sX, sY + fh, east[0], east[3], 0.6f);
        }

        if (iconMesh == null) iconMesh = new Mesh();
        float[] va = new float[verts.size()];
        for (int i = 0; i < va.length; i++) va[i] = verts.get(i);
        int[] ia = new int[idxs.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = idxs.get(i);
        iconMesh.upload(va, ia);
    }

    /** One textured quad with 4 explicit corners/UVs and a face light. */
    private static int quad(ArrayList<Float> v, ArrayList<Integer> idx, int base,
                            float x0, float y0, float u0, float v0,
                            float x1, float y1, float u1, float v1,
                            float x2, float y2, float u2, float v2,
                            float x3, float y3, float u3, float v3, float light) {
        float[][] p = {{x0,y0,u0,v0},{x1,y1,u1,v1},{x2,y2,u2,v2},{x3,y3,u3,v3}};
        for (float[] c : p) {
            v.add(c[0]); v.add(c[1]); v.add(0f); v.add(c[2]); v.add(c[3]); v.add(light);
        }
        idx.add(base); idx.add(base+1); idx.add(base+2);
        idx.add(base+2); idx.add(base+3); idx.add(base);
        return 4;
    }

    /** Rasterises (and caches) a white-with-shadow stack count, MC-style. */
    private Label countTexFor(int count) {
        return countTex.computeIfAbsent(count, n -> {
            String text = String.valueOf(n);
            Font font = new Font(Font.MONOSPACED, Font.BOLD, 12);
            BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            FontMetrics fm = tmp.getGraphics().getFontMetrics(font);
            int tw = Math.max(1, fm.stringWidth(text)), th = fm.getHeight();
            BufferedImage img = new BufferedImage(tw + 2, th + 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setFont(font);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setColor(new Color(0, 0, 0, 220));
            g.drawString(text, 2, fm.getAscent() + 2); // MC's offset shadow
            g.setColor(Color.WHITE);
            g.drawString(text, 0, fm.getAscent());
            g.dispose();

            int iw = img.getWidth(), ih = img.getHeight();
            ByteBuffer buf = MemoryUtil.memAlloc(iw * ih * 4);
            for (int y = 0; y < ih; y++) {
                int srcY = ih - 1 - y; // flip for GL
                for (int x = 0; x < iw; x++) {
                    int argb = img.getRGB(x, srcY);
                    buf.put((byte) ((argb >> 16) & 0xFF));
                    buf.put((byte) ((argb >>  8) & 0xFF));
                    buf.put((byte) ( argb        & 0xFF));
                    buf.put((byte) ((argb >> 24) & 0xFF));
                }
            }
            buf.flip();
            Texture t = new Texture(buf, iw, ih);
            MemoryUtil.memFree(buf);
            return new Label(t, iw, ih);
        });
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
        if (iconShader   != null) iconShader.cleanup();
        if (whiteTexture != null) whiteTexture.cleanup();
        if (mesh         != null) mesh.cleanup();
        if (iconMesh     != null) iconMesh.cleanup();
        for (Label l : countTex.values()) l.tex().cleanup();
        countTex.clear();
        labelMesh.cleanup();
    }
}
