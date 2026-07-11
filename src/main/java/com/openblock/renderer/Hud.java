package com.openblock.renderer;

import com.openblock.player.Player;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import static org.lwjgl.opengl.GL11.*;

/**
 * Survival HUD: hearts row and air bubbles above the hotbar (Minecraft layout —
 * hearts bottom-left, bubbles bottom-right), plus a red damage flash overlay.
 *
 * Sprites are 9x9 pixel-art (heart container, full heart, half heart, bubble)
 * generated in code and drawn at 3x scale, matching the game's chunky look.
 * Hidden entirely in admin mode, like Minecraft's creative HUD.
 */
public class Hud {
    private static final int SPR   = 9;  // sprite size in pixels
    private static final int PITCH = 10; // sprite stride in the atlas (1px gap)
    private static final int SCALE = 3;  // on-screen pixel scale
    /** Hearts/bubbles advance 8 art-pixels per slot (1px overlap, like MC). */
    private static final int STEP  = 8 * SCALE;

    // Atlas sprite indices
    private static final int SPR_CONTAINER  = 0;
    private static final int SPR_HEART      = 1;
    private static final int SPR_HEART_HALF = 2;
    private static final int SPR_BUBBLE     = 3;
    private static final int SPRITE_COUNT   = 4;

    // 9x9 pixel maps. '.' transparent, 'O' outline, 'F' fill, 'H' highlight.
    private static final String[] HEART_MAP = {
        ".OOO.OOO.",
        "OFFFOFFFO",
        "OFHFFFFFO",
        "OFFFFFFFO",
        ".OFFFFFO.",
        "..OFFFO..",
        "...OFO...",
        "....O....",
        ".........",
    };
    private static final String[] BUBBLE_MAP = {
        "..OOOOO..",
        ".OFFFFFO.",
        "OFHFFFFFO",
        "OFHFFFFFO",
        "OFFFFFFFO",
        ".OFFFFFO.",
        "..OOOOO..",
        ".........",
        ".........",
    };

    private final ShaderProgram spriteShader; // ui.vert + text.frag (textured)
    private final ShaderProgram flashShader;  // ui.vert + flash.frag (solid colour)
    private final Texture atlas;
    private final Matrix4f projection = new Matrix4f();

    private Mesh mesh;      // hearts + bubbles
    private Mesh flashMesh; // fullscreen quad
    private int lastW = -1, lastH = -1, lastHealth = -1, lastBubbles = -1;
    private boolean lastAdmin = false;

    public Hud() {
        spriteShader = new ShaderProgram("/shaders/ui.vert", "/shaders/text.frag");
        flashShader  = new ShaderProgram("/shaders/ui.vert", "/shaders/flash.frag");
        atlas = buildAtlas();
    }

    // ---------- atlas ----------

    private static Texture buildAtlas() {
        int w = SPRITE_COUNT * PITCH, h = SPR;
        ByteBuffer buf = MemoryUtil.memCalloc(w * h * 4); // zeroed → transparent

        // Heart container: dark gray heart-shaped socket
        blit(buf, w, SPR_CONTAINER, HEART_MAP, 0xFF000000, 0xFF3F3F3F, 0xFF5A5A5A);
        // Full heart: bright red with light shine
        blit(buf, w, SPR_HEART, HEART_MAP, 0xFF000000, 0xFFE3313B, 0xFFFF8B8B);
        // Half heart: red left half only (container behind shows the rest)
        blitHalf(buf, w, SPR_HEART_HALF, HEART_MAP, 0xFF000000, 0xFFE3313B, 0xFFFF8B8B);
        // Air bubble: light blue with white shine
        blit(buf, w, SPR_BUBBLE, BUBBLE_MAP, 0xFF29477F, 0xFF6E9BE8, 0xFFE8F2FF);

        buf.flip();
        Texture t = new Texture(buf, w, h);
        MemoryUtil.memFree(buf);
        return t;
    }

    private static void blit(ByteBuffer buf, int atlasW, int sprite, String[] map,
                             int outline, int fill, int highlight) {
        blitCols(buf, atlasW, sprite, map, outline, fill, highlight, SPR);
    }

    /** Like blit but only the left {@code cols} columns (right stays transparent). */
    private static void blitHalf(ByteBuffer buf, int atlasW, int sprite, String[] map,
                                 int outline, int fill, int highlight) {
        blitCols(buf, atlasW, sprite, map, outline, fill, highlight, 5);
    }

    private static void blitCols(ByteBuffer buf, int atlasW, int sprite, String[] map,
                                 int outline, int fill, int highlight, int cols) {
        int x0 = sprite * PITCH;
        for (int y = 0; y < SPR; y++) {
            String row = map[y];
            for (int x = 0; x < cols; x++) {
                int argb = switch (row.charAt(x)) {
                    case 'O' -> outline;
                    case 'F' -> fill;
                    case 'H' -> highlight;
                    default  -> 0;
                };
                if (argb == 0) continue;
                // Texture rows are bottom-up in GL; flip so map row 0 is the top
                int idx = ((SPR - 1 - y) * atlasW + x0 + x) * 4;
                buf.put(idx,     (byte) ((argb >> 16) & 0xFF));
                buf.put(idx + 1, (byte) ((argb >>  8) & 0xFF));
                buf.put(idx + 2, (byte) ( argb        & 0xFF));
                buf.put(idx + 3, (byte) ((argb >> 24) & 0xFF));
            }
        }
    }

    // ---------- rendering ----------

    public void render(int w, int h, Player player) {
        // Damage flash first (behind the HUD sprites)
        float flash = player.getDamageFlash();
        if (flash > 0.01f) renderFlash(w, h, flash);

        if (player.isAdminMode()) {
            lastAdmin = true; // force a mesh rebuild when admin turns back off
            return;           // creative-style: no survival HUD
        }

        int health  = Math.max(0, player.getHealth());
        int bubbles = player.getAir() >= Player.MAX_AIR - 0.01f ? -1 // hidden when full
                    : (int) Math.ceil(player.getAir() / (Player.MAX_AIR / 10f));

        if (w != lastW || h != lastH || health != lastHealth
                || bubbles != lastBubbles || lastAdmin) {
            lastW = w; lastH = h; lastHealth = health; lastBubbles = bubbles;
            lastAdmin = false;
            projection.identity().setOrtho(0, w, h, 0, -1, 1);
            rebuildMesh(w, h, health, bubbles);
        }
        if (mesh == null || mesh.isEmpty()) return;

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        spriteShader.use();
        spriteShader.setUniform("uProjection", projection);
        spriteShader.setUniform("uTexture", 0);
        atlas.bind(0);
        mesh.render();
        spriteShader.detach();

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void renderFlash(int w, int h, float flash) {
        projection.identity().setOrtho(0, w, h, 0, -1, 1);
        if (flashMesh == null) {
            flashMesh = new Mesh();
            // Unit quad scaled by projection: use a large fixed quad instead
        }
        // Rebuild each time — trivial 4 verts, and screen size can change
        float[] verts = {
            0, 0, 0,  0, 0, 1,
            w, 0, 0,  1, 0, 1,
            w, h, 0,  1, 1, 1,
            0, h, 0,  0, 1, 1,
        };
        flashMesh.upload(verts, new int[]{0, 1, 2, 2, 3, 0});

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        flashShader.use();
        flashShader.setUniform("uProjection", projection);
        flashShader.setUniform("uColor", 0.75f, 0.05f, 0.05f, flash * 0.40f);
        flashMesh.render();
        flashShader.detach();

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void rebuildMesh(int w, int h, int health, int bubbles) {
        ArrayList<Float>   verts = new ArrayList<>();
        ArrayList<Integer> idxs  = new ArrayList<>();
        int[] vi = {0};

        int panelW  = Hotbar.panelWidth();
        int panelX  = (w - panelW) / 2;
        int rowY    = Hotbar.panelTop(h) - SPR * SCALE - 4;

        // Hearts: 10 containers left-aligned with the hotbar, hearts on top
        for (int i = 0; i < 10; i++) {
            int x = panelX + i * STEP;
            addSprite(verts, idxs, vi, x, rowY, SPR_CONTAINER);
            int hp = health - i * 2; // health represented by this heart slot
            if (hp >= 2)      addSprite(verts, idxs, vi, x, rowY, SPR_HEART);
            else if (hp == 1) addSprite(verts, idxs, vi, x, rowY, SPR_HEART_HALF);
        }

        // Air bubbles: right-aligned with the hotbar, fill right-to-left like MC
        if (bubbles >= 0) {
            for (int i = 0; i < bubbles; i++) {
                int x = panelX + panelW - SPR * SCALE - i * STEP;
                addSprite(verts, idxs, vi, x, rowY, SPR_BUBBLE);
            }
        }

        float[] va = new float[verts.size()];
        for (int i = 0; i < va.length; i++) va[i] = verts.get(i);
        int[] ia = new int[idxs.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = idxs.get(i);

        if (mesh == null) mesh = new Mesh();
        mesh.upload(va, ia);
    }

    private static void addSprite(ArrayList<Float> v, ArrayList<Integer> idx, int[] vi,
                                  int x, int y, int sprite) {
        float atlasW = SPRITE_COUNT * PITCH;
        float u0 = (sprite * PITCH) / atlasW;
        float u1 = (sprite * PITCH + SPR) / atlasW;
        float x1 = x + SPR * SCALE, y1 = y + SPR * SCALE;
        // v: sprite row 0 (top) is at texture v=1 after the blit flip
        vert(v, x,  y,  u0, 1); vert(v, x1, y,  u1, 1);
        vert(v, x1, y1, u1, 0); vert(v, x,  y1, u0, 0);
        int b = vi[0];
        idx.add(b); idx.add(b + 1); idx.add(b + 2);
        idx.add(b + 2); idx.add(b + 3); idx.add(b);
        vi[0] += 4;
    }

    private static void vert(ArrayList<Float> v, float x, float y, float u, float vv) {
        v.add(x); v.add(y); v.add(0f); v.add(u); v.add(vv); v.add(1f);
    }

    public void cleanup() {
        if (spriteShader != null) spriteShader.cleanup();
        if (flashShader  != null) flashShader.cleanup();
        if (atlas        != null) atlas.cleanup();
        if (mesh         != null) mesh.cleanup();
        if (flashMesh    != null) flashMesh.cleanup();
    }
}
