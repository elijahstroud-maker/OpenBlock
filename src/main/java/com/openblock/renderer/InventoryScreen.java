package com.openblock.renderer;

import com.openblock.crafting.Recipes;
import com.openblock.input.InputHandler;
import com.openblock.player.Inventory;
import com.openblock.player.Player;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * The E-key inventory screen — Minecraft's survival inventory layout at 3x
 * scale (MC's GUI is 176x166 with 18px slot cells), drawn in the same dark
 * translucent panel style as the hotbar:
 *
 *   - 4 armor slots down the left edge (no armor items exist yet, so they
 *     only accept nothing — MC-accurate: armor slots reject non-armor)
 *   - player viewport next to them: a blocky player model that follows the
 *     mouse cursor like MC's
 *   - 2x2 crafting grid + result slot top-right (no recipes wired up yet)
 *   - 9x3 storage grid, hotbar row below it
 *
 * Click rules match MC: left click picks up / places / swaps a whole stack,
 * right click takes the larger half / places a single item, shift-click
 * quick-moves between hotbar and storage. Closing the screen returns the
 * crafting grid and any held stack to the inventory (overflow is tossed).
 *
 * Slot ids: 0-35 main (0-8 hotbar), 36-39 armor, 40-43 craft, 44 result.
 */
public class InventoryScreen {
    private static final float S = 3.5f;       // MC GUI pixel → screen pixel
    /** MC GUI pixels → screen pixels. 18*S is a whole number, so slot cells
     *  stay evenly spaced even though offsets round. */
    private static int sc(int mcPx) { return Math.round(mcPx * S); }
    private static final int GUI_W = sc(176);
    private static final int GUI_H = sc(166);
    private static final int CELL  = sc(18);   // one slot cell
    /** Icon half-width, proportional to the cell (same ratio as the hotbar). */
    private static final float ICON_S = CELL * 0.30f;

    // Panel style — same palette as the hotbar
    private static final float BORDER_B = 0.00f, BORDER_A = 0.85f;
    private static final float PANEL_B  = 0.10f, PANEL_A  = 0.82f; // more opaque than the strip: it's a full screen
    private static final float SLOT_B   = 0.16f, SLOT_A   = 0.60f;
    private static final float BEV_DK_B = 0.03f, BEV_DK_A = 0.80f;
    private static final float BEV_LT_B = 0.42f, BEV_LT_A = 0.65f;
    private static final float HOVER_B  = 1.00f, HOVER_A  = 0.30f; // MC's white slot highlight

    // Slot ids
    private static final int ARMOR_BASE = 36;
    private static final int CRAFT_BASE = 40;  // 9 ids reserved; 2x2 uses the first 4
    private static final int RESULT_ID  = 49;

    /** Which GUI this screen is showing: the survival inventory or a crafting table. */
    public enum Mode { PLAYER, TABLE }

    /** {id, cellX, cellY} in MC GUI units — MC's exact GUI positions per mode. */
    private static final int[][] PLAYER_DEFS;
    private static final int[][] TABLE_DEFS;
    static {
        ArrayList<int[]> defs = new ArrayList<>();
        for (int i = 0; i < 9; i++)                       // hotbar row
            defs.add(new int[]{i, 7 + 18 * i, 141});
        for (int r = 0; r < 3; r++)                       // storage grid
            for (int c = 0; c < 9; c++)
                defs.add(new int[]{9 + r * 9 + c, 7 + 18 * c, 83 + 18 * r});
        int shared = defs.size();

        for (int i = 0; i < 4; i++)                       // armor column
            defs.add(new int[]{ARMOR_BASE + i, 7, 7 + 18 * i});
        for (int r = 0; r < 2; r++)                       // 2x2 crafting grid
            for (int c = 0; c < 2; c++)
                defs.add(new int[]{CRAFT_BASE + r * 2 + c, 97 + 18 * c, 17 + 18 * r});
        defs.add(new int[]{RESULT_ID, 153, 27});          // craft result
        PLAYER_DEFS = defs.toArray(new int[0][]);

        defs.subList(shared, defs.size()).clear();        // back to main + hotbar
        for (int r = 0; r < 3; r++)                       // 3x3 table grid (MC: 30,17)
            for (int c = 0; c < 3; c++)
                defs.add(new int[]{CRAFT_BASE + r * 3 + c, 29 + 18 * c, 16 + 18 * r});
        defs.add(new int[]{RESULT_ID, 123, 34});          // table result (MC: 124,35)
        TABLE_DEFS = defs.toArray(new int[0][]);
    }

    private Mode mode = Mode.PLAYER;

    private int[][] defs() { return mode == Mode.PLAYER ? PLAYER_DEFS : TABLE_DEFS; }

    /** Grid width of the active crafting area (2 for inventory, 3 for table). */
    private int craftW() { return mode == Mode.PLAYER ? 2 : 3; }

    // Player viewport frame in MC GUI units
    private static final int VP_X0 = 25, VP_Y0 = 7, VP_X1 = 79, VP_Y1 = 79;

    private final ShaderProgram shader;      // ui.vert + ui.frag: solid panel quads
    private final ShaderProgram iconShader;  // ui.vert + text.frag: atlas icons + labels
    private final ShaderProgram modelShader; // item.vert + item.frag: 3D player preview
    private final Texture whiteTexture;
    private final Texture skinTexture;       // steve.png — real player skin
    /** MC's empty-armor-slot silhouettes: helmet, chestplate, leggings, boots. */
    private final Texture[] armorIcons = new Texture[4];
    private final Texture craftingLabel; private final int craftingLabelW, craftingLabelH;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f previewProj  = new Matrix4f();
    private final Matrix4f previewView  = new Matrix4f();
    private final Matrix4f previewModel = new Matrix4f();

    private final Mesh panelMesh  = new Mesh();
    private final Mesh iconMesh   = new Mesh();
    private final Mesh cursorMesh = new Mesh();
    private final Mesh labelMesh  = new Mesh();
    private final PlayerModel previewPlayer;       // full limb rig, idle pose
    private final PlayerModel.Pose previewPose = new PlayerModel.Pose();

    private Inventory inventory;
    private TextureAtlas atlas;
    private boolean open = false;

    // Stack held on the mouse cursor
    private BlockType cursorType  = null;
    private int       cursorCount = 0;

    // Left drag-split state: slots painted over while the button is held
    private boolean leftDragging = false;
    private final LinkedHashSet<Integer> dragSlots = new LinkedHashSet<>();
    // Right drag: one item sprinkled into each new slot entered
    private boolean rightDragging = false;
    private int lastRightDragSlot = -1;
    // Double-click gather detection
    private int  lastPickupSlot = -1;
    private long lastPickupTime = 0;

    /** A rasterised stack-count label and its pixel size (pre-2x-scale). */
    private record Label(Texture tex, int w, int h) { }
    private final Map<Integer, Label> countTex = new HashMap<>();
    /** Cached item-name tooltip labels. */
    private final Map<BlockType, Label> nameTex = new EnumMap<>(BlockType.class);

    public InventoryScreen() {
        shader       = new ShaderProgram("/shaders/ui.vert", "/shaders/ui.frag");
        iconShader   = new ShaderProgram("/shaders/ui.vert", "/shaders/text.frag");
        modelShader  = new ShaderProgram("/shaders/item.vert", "/shaders/item.frag");
        whiteTexture = solidTexture(0xFF, 0xFF, 0xFF);
        skinTexture  = new Texture(PlayerSkin.PATH);
        armorIcons[0] = new Texture("/textures/item/empty_armor_slot_helmet.png");
        armorIcons[1] = new Texture("/textures/item/empty_armor_slot_chestplate.png");
        armorIcons[2] = new Texture("/textures/item/empty_armor_slot_leggings.png");
        armorIcons[3] = new Texture("/textures/item/empty_armor_slot_boots.png");
        int[] wh = new int[2];
        craftingLabel = makeText("Crafting", 11, 2, wh);
        craftingLabelW = wh[0];
        craftingLabelH = wh[1];
        previewPlayer = new PlayerModel();
    }

    public void attach(Inventory inventory, TextureAtlas atlas) {
        this.inventory = inventory;
        this.atlas     = atlas;
    }

    public boolean isOpen() { return open; }

    public void setOpen(boolean open) { this.open = open; }

    /** Opens as the survival inventory or a crafting table's 3x3 GUI. */
    public void open(Mode mode) {
        this.mode = mode;
        this.open = true;
    }

    public Mode getMode() { return mode; }

    /**
     * Called when the screen closes: the crafting grid and any stack still on
     * the cursor go back into the inventory (MC returns both); whatever
     * doesn't fit is tossed out in front of the player.
     */
    public void onClose(Player player) {
        for (int i = 0; i < Inventory.CRAFT_SIZE; i++) {
            BlockType t = inventory.getCraftType(i);
            int c = inventory.getCraftCount(i);
            if (t == null || c == 0) continue;
            inventory.setCraft(i, null, 0);
            returnOrToss(player, t, c);
        }
        if (cursorType != null && cursorCount > 0) {
            returnOrToss(player, cursorType, cursorCount);
        }
        cursorType  = null;
        cursorCount = 0;
        leftDragging  = false;
        rightDragging = false;
        dragSlots.clear();
        lastPickupSlot = -1;
    }

    private void returnOrToss(Player player, BlockType type, int count) {
        while (count > 0 && inventory.add(type)) count--;
        if (count > 0) player.tossItem(type, count);
    }

    /** Handles slot clicks and all of MC's inventory shortcuts. Call each tick while open. */
    public void update(InputHandler input, Player player, int w, int h) {
        float mx = input.getMouseX(), my = input.getMouseY();
        int id = slotAt(mx, my, w, h);
        boolean shift = input.isKeyDownRaw(GLFW_KEY_LEFT_SHIFT)
                     || input.isKeyDownRaw(GLFW_KEY_RIGHT_SHIFT);
        boolean ctrl  = input.isKeyDownRaw(GLFW_KEY_LEFT_CONTROL)
                     || input.isKeyDownRaw(GLFW_KEY_RIGHT_CONTROL);
        boolean leftEdge  = input.isMouseButtonJustPressedRaw(GLFW_MOUSE_BUTTON_LEFT);
        boolean rightEdge = input.isMouseButtonJustPressedRaw(GLFW_MOUSE_BUTTON_RIGHT);
        boolean leftDown  = input.isMouseButtonDownRaw(GLFW_MOUSE_BUTTON_LEFT);
        boolean rightDown = input.isMouseButtonDownRaw(GLFW_MOUSE_BUTTON_RIGHT);

        // --- keyboard shortcuts on the hovered slot (MC) ---
        if (id >= 0 && id != RESULT_ID && !(id >= ARMOR_BASE && id < CRAFT_BASE)) {
            // 1-9 swaps the hovered slot with that hotbar slot
            for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
                if (input.isKeyJustPressed(GLFW_KEY_1 + i)) {
                    if (id != i) {
                        BlockType ht = inventory.getType(i);
                        int       hc = inventory.getCount(i);
                        inventory.set(i, typeAt(id), countAt(id));
                        setAt(id, ht, hc);
                    }
                    break;
                }
            }
            // Q drops one item off the hovered stack; Ctrl+Q the whole stack
            if (input.isKeyJustPressed(GLFW_KEY_Q) && typeAt(id) != null) {
                BlockType t = typeAt(id);
                int n = ctrl ? countAt(id) : 1;
                player.tossItem(t, n);
                setAt(id, t, countAt(id) - n);
            }
        }

        // --- left button ---
        if (leftEdge) {
            if (id >= 0) {
                if (shift) {
                    quickMove(id);
                } else if (cursorType != null && id == lastPickupSlot
                        && System.currentTimeMillis() - lastPickupTime < 350) {
                    collectAll(); // double click gathers every matching stack
                    lastPickupSlot = -1;
                } else if (cursorType != null && acceptsPlacement(id) && canDropInto(id)) {
                    // Could become a drag-split: placement waits for release
                    leftDragging = true;
                    dragSlots.clear();
                    dragSlots.add(id);
                } else {
                    boolean hadCursor = cursorType != null;
                    leftClick(id);
                    if (!hadCursor && cursorType != null) { // just picked a stack up
                        lastPickupSlot = id;
                        lastPickupTime = System.currentTimeMillis();
                    }
                }
            } else if (cursorType != null && outsidePanel(mx, my, w, h)) {
                // Click into the open air: the whole stack is thrown out (MC)
                player.tossItem(cursorType, cursorCount);
                cursorType  = null;
                cursorCount = 0;
            }
        }

        // Left drag-split: paint slots while held, split evenly on release
        if (leftDragging) {
            if (leftDown && cursorType != null) {
                if (id >= 0 && acceptsPlacement(id) && canDropInto(id)) dragSlots.add(id);
            } else {
                if (cursorType != null) {
                    if (dragSlots.size() == 1) leftClick(dragSlots.iterator().next());
                    else distribute();
                }
                leftDragging = false;
                dragSlots.clear();
            }
        }

        // --- right button ---
        if (rightEdge) {
            if (id >= 0) {
                rightClick(id);
                if (cursorType != null) { // keep sprinkling one per new slot while held
                    rightDragging = true;
                    lastRightDragSlot = id;
                }
            } else if (cursorType != null && outsidePanel(mx, my, w, h)) {
                player.tossItem(cursorType, 1); // right click into air throws one item
                if (--cursorCount == 0) cursorType = null;
            }
        }
        if (rightDragging) {
            if (rightDown && cursorType != null) {
                if (id >= 0 && id != lastRightDragSlot) {
                    rightClick(id);
                    lastRightDragSlot = id;
                }
            } else {
                rightDragging = false;
                lastRightDragSlot = -1;
            }
        }
    }

    private boolean outsidePanel(float mx, float my, int w, int h) {
        int px = panelX(w), py = panelY(h);
        return mx < px || mx >= px + GUI_W || my < py || my >= py + GUI_H;
    }

    /** Can the cursor stack (at least partly) drop into this slot? */
    private boolean canDropInto(int id) {
        BlockType t = typeAt(id);
        return t == null || (t == cursorType && countAt(id) < Inventory.MAX_STACK);
    }

    /** Double-click gather: pulls every matching stack onto the cursor (MC). */
    private void collectAll() {
        for (int id = 0; id < RESULT_ID && cursorCount < Inventory.MAX_STACK; id++) {
            if (id >= ARMOR_BASE && id < CRAFT_BASE) continue; // armor can't hold blocks
            if (typeAt(id) != cursorType) continue;
            int take = Math.min(countAt(id), Inventory.MAX_STACK - cursorCount);
            setAt(id, cursorType, countAt(id) - take);
            cursorCount += take;
        }
    }

    /** Even split of the held stack across every dragged-over slot (MC left drag). */
    private void distribute() {
        int share = Math.max(1, cursorCount / dragSlots.size());
        for (int id : dragSlots) {
            if (cursorCount <= 0) break;
            BlockType t = typeAt(id);
            if (t != null && t != cursorType) continue;
            int put = Math.min(Math.min(share, Inventory.MAX_STACK - countAt(id)), cursorCount);
            if (put <= 0) continue;
            setAt(id, cursorType, countAt(id) + put);
            cursorCount -= put;
        }
        if (cursorCount == 0) cursorType = null;
    }

    // ---------- slot access by id ----------

    private BlockType typeAt(int id) {
        if (id < Inventory.MAIN_SIZE) return inventory.getType(id);
        if (id < CRAFT_BASE)          return inventory.getArmorType(id - ARMOR_BASE);
        if (id < RESULT_ID)           return inventory.getCraftType(id - CRAFT_BASE);
        Recipes.Result r = currentResult();
        return r == null ? null : r.type();
    }

    private int countAt(int id) {
        if (id < Inventory.MAIN_SIZE) return inventory.getCount(id);
        if (id < CRAFT_BASE)          return inventory.getArmorCount(id - ARMOR_BASE);
        if (id < RESULT_ID)           return inventory.getCraftCount(id - CRAFT_BASE);
        Recipes.Result r = currentResult();
        return r == null ? 0 : r.count();
    }

    /** What the active craft grid currently makes (null = no recipe match). */
    private Recipes.Result currentResult() {
        int w = craftW();
        BlockType[] grid = new BlockType[w * w];
        for (int i = 0; i < grid.length; i++) grid[i] = inventory.getCraftType(i);
        return Recipes.match(grid, w);
    }

    /** One craft happened: each occupied grid cell gives up one ingredient. */
    private void consumeCraftIngredients() {
        for (int i = 0; i < craftW() * craftW(); i++) {
            BlockType t = inventory.getCraftType(i);
            if (t != null) inventory.setCraft(i, t, inventory.getCraftCount(i) - 1);
        }
    }

    private void setAt(int id, BlockType type, int count) {
        if (id < Inventory.MAIN_SIZE)  inventory.set(id, type, count);
        else if (id < CRAFT_BASE)      inventory.setArmor(id - ARMOR_BASE, type, count);
        else if (id < RESULT_ID)       inventory.setCraft(id - CRAFT_BASE, type, count);
        // result slot is read-only
    }

    /** Blocks can't go in armor slots (MC rejects non-armor) or the result slot. */
    private boolean acceptsPlacement(int id) {
        return id < Inventory.MAIN_SIZE || (id >= CRAFT_BASE && id < RESULT_ID);
    }

    // ---------- click rules (Minecraft's) ----------

    private void leftClick(int id) {
        if (id == RESULT_ID) {
            takeCraftResult();
            return;
        }
        BlockType slotType  = typeAt(id);
        int       slotCount = countAt(id);

        if (cursorType == null) {
            if (slotType == null) return;
            cursorType  = slotType;          // pick up the whole stack
            cursorCount = slotCount;
            setAt(id, null, 0);
        } else if (!acceptsPlacement(id)) {
            return; // armor/result slot refuses the held stack
        } else if (slotType == null) {
            setAt(id, cursorType, cursorCount);   // place everything
            cursorType  = null;
            cursorCount = 0;
        } else if (slotType == cursorType) {
            int moved = Math.min(cursorCount, Inventory.MAX_STACK - slotCount);
            setAt(id, slotType, slotCount + moved);
            cursorCount -= moved;
            if (cursorCount == 0) cursorType = null;
        } else {
            setAt(id, cursorType, cursorCount);   // swap stacks
            cursorType  = slotType;
            cursorCount = slotCount;
        }
    }

    private void rightClick(int id) {
        if (id == RESULT_ID) {
            takeCraftResult(); // MC: right click on the result crafts once too
            return;
        }
        BlockType slotType  = typeAt(id);
        int       slotCount = countAt(id);

        if (cursorType == null) {
            if (slotType == null) return;
            int take = (slotCount + 1) / 2;       // MC takes the larger half
            cursorType  = slotType;
            cursorCount = take;
            setAt(id, slotType, slotCount - take);
        } else if (!acceptsPlacement(id)) {
            return;
        } else if (slotType == null) {
            setAt(id, cursorType, 1);             // place a single item
            if (--cursorCount == 0) cursorType = null;
        } else if (slotType == cursorType && slotCount < Inventory.MAX_STACK) {
            setAt(id, slotType, slotCount + 1);
            if (--cursorCount == 0) cursorType = null;
        }
    }

    /** Picks up one craft's worth of output onto the cursor (MC result-slot click). */
    private void takeCraftResult() {
        Recipes.Result r = currentResult();
        if (r == null) return;
        if (cursorType == null) {
            cursorType  = r.type();
            cursorCount = r.count();
        } else if (cursorType == r.type() && cursorCount + r.count() <= Inventory.MAX_STACK) {
            cursorCount += r.count();
        } else {
            return;
        }
        consumeCraftIngredients();
    }

    /** True when the whole stack could merge into the main inventory. */
    private boolean canFit(BlockType type, int count) {
        int space = 0;
        for (int i = 0; i < Inventory.MAIN_SIZE && space < count; i++) {
            BlockType t = inventory.getType(i);
            if (t == null) space += Inventory.MAX_STACK;
            else if (t == type) space += Inventory.MAX_STACK - inventory.getCount(i);
        }
        return space >= count;
    }

    /** Shift-click quick-move: hotbar ↔ storage; armor/craft → storage, then hotbar. */
    private void quickMove(int id) {
        if (id == RESULT_ID) {
            // Craft-all: keep crafting into the inventory until the recipe
            // breaks or nothing more fits (MC's shift-click on the result)
            for (int guard = 0; guard < 64; guard++) {
                Recipes.Result r = currentResult();
                if (r == null || !canFit(r.type(), r.count())) break;
                int left = mergeIntoRange(r.type(), r.count(), 9, 35);
                if (left > 0) mergeIntoRange(r.type(), left, 0, 8);
                consumeCraftIngredients();
            }
            return;
        }
        BlockType type  = typeAt(id);
        int       count = countAt(id);
        if (type == null || count == 0) return;

        int lo, hi;
        if (id < Inventory.HOTBAR_SIZE)       { lo = 9; hi = 35; }
        else if (id < Inventory.MAIN_SIZE)    { lo = 0; hi = 8;  }
        else                                  { lo = 9; hi = 35; }

        count = mergeIntoRange(type, count, lo, hi);
        if (count > 0 && id >= Inventory.MAIN_SIZE) {
            // armor/craft overflow falls through to the other main region
            count = mergeIntoRange(type, count, 0, 8);
        }
        setAt(id, type, count);
    }

    /** Merges into main slots [lo..hi]: tops up stacks first, then fills empties. */
    private int mergeIntoRange(BlockType type, int count, int lo, int hi) {
        for (int i = lo; i <= hi && count > 0; i++) {
            if (inventory.getType(i) == type && inventory.getCount(i) < Inventory.MAX_STACK) {
                int moved = Math.min(count, Inventory.MAX_STACK - inventory.getCount(i));
                inventory.set(i, type, inventory.getCount(i) + moved);
                count -= moved;
            }
        }
        for (int i = lo; i <= hi && count > 0; i++) {
            if (inventory.getType(i) == null) {
                int moved = Math.min(count, Inventory.MAX_STACK);
                inventory.set(i, type, moved);
                count -= moved;
            }
        }
        return count;
    }

    // ---------- layout ----------

    private int panelX(int w) { return (w - GUI_W) / 2; }
    /** Sits a touch above true center — reads better with the hotbar below. */
    private int panelY(int h) { return (h - GUI_H) * 2 / 5; }

    /** Slot id under the mouse, or -1. */
    private int slotAt(float mx, float my, int w, int h) {
        int px = panelX(w), py = panelY(h);
        for (int[] d : defs()) {
            int x = px + sc(d[1]), y = py + sc(d[2]);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) return d[0];
        }
        return -1;
    }

    // ---------- rendering ----------

    public void render(InputHandler input, int w, int h) {
        if (!open || inventory == null) return;
        projection.identity().setOrtho(0, w, h, 0, -1, 1);
        float mx = input.getMouseX(), my = input.getMouseY();
        int hovered = slotAt(mx, my, w, h);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        buildPanelMesh(w, h, hovered);
        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uTexture", 0);
        whiteTexture.bind(0);
        panelMesh.render();
        shader.detach();

        // Item icons + stack counts
        ArrayList<Float> verts = new ArrayList<>();
        ArrayList<Integer> idxs = new ArrayList<>();
        ArrayList<int[]> labels = new ArrayList<>(); // {count, x, y}
        int vi = 0;
        int px = panelX(w), py = panelY(h);
        for (int[] d : defs()) {
            BlockType type = typeAt(d[0]);
            if (type == null) continue;
            float cx = px + sc(d[1]) + CELL / 2f;
            float cy = py + sc(d[2]) + CELL / 2f;
            float pop = d[0] < Inventory.MAIN_SIZE ? inventory.popScale(d[0]) : 1f;
            vi += appendBlockIcon(verts, idxs, vi, type, cx, cy, ICON_S * pop);
            int count = countAt(d[0]);
            if (count > 1) {
                labels.add(new int[]{count, px + sc(d[1]) + CELL - 3, py + sc(d[2]) + CELL - 2});
            }
        }
        drawIconMesh(iconMesh, verts, idxs);
        for (int[] l : labels) {
            Label lab = countTexFor(l[0]);
            drawLabel(lab, l[1] - lab.w() * 2, l[2] - lab.h() * 2);
        }

        if (mode == Mode.PLAYER) {
            // "Crafting" label centered over the 2x2 grid
            int gridX0 = px + sc(97), gridX1 = px + sc(115 + 18);
            drawTexture(craftingLabel,
                (gridX0 + gridX1 - craftingLabelW) / 2,
                py + sc(17) - craftingLabelH - sc(2),
                craftingLabelW, craftingLabelH);

            // Empty armor slots show their MC placeholder silhouettes
            for (int i = 0; i < Inventory.ARMOR_SIZE; i++) {
                if (inventory.getArmorType(i) != null) continue;
                int ax = px + sc(7) + 3, ay = py + sc(7 + 18 * i) + 3;
                drawTexture(armorIcons[i], ax, ay, CELL - 6, CELL - 6);
            }

            // Player preview in its own little 3D viewport
            renderPlayerPreview(w, h, mx, my);
        } else {
            // Crafting table GUI: label top-left, MC style
            drawTexture(craftingLabel, px + sc(28), py + sc(5),
                        craftingLabelW, craftingLabelH);
        }

        // Held stack rides the cursor, drawn over everything
        if (cursorType != null) {
            verts.clear(); idxs.clear();
            appendBlockIcon(verts, idxs, 0, cursorType, mx, my, ICON_S);
            drawIconMesh(cursorMesh, verts, idxs);
            if (cursorCount > 1) {
                Label lab = countTexFor(cursorCount);
                drawLabel(lab, (int) mx + CELL / 2 - 3 - lab.w() * 2,
                               (int) my + CELL / 2 - 2 - lab.h() * 2);
            }
        } else if (hovered >= 0 && typeAt(hovered) != null) {
            // Item-name tooltip, MC-style (only with an empty cursor, like MC)
            renderTooltip(typeAt(hovered), (int) mx, (int) my, w);
        }

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void buildPanelMesh(int w, int h, int hovered) {
        ArrayList<Float> verts = new ArrayList<>();
        ArrayList<Integer> idxs = new ArrayList<>();
        int vi = 0;
        int px = panelX(w), py = panelY(h);

        // Dim the world behind the screen (MC's dark overlay)
        vi += rect(verts, idxs, vi, 0, 0, w, h, 0f, 0.45f);

        // Panel: black border + translucent dark body
        vi += rect(verts, idxs, vi, px - 2, py - 2, px + GUI_W + 2, py + GUI_H + 2,
                   BORDER_B, BORDER_A);
        vi += rect(verts, idxs, vi, px, py, px + GUI_W, py + GUI_H, PANEL_B, PANEL_A);

        // Player viewport frame: inset dark box (survival inventory only)
        if (mode == Mode.PLAYER) {
            int x0 = px + sc(VP_X0), y0 = py + sc(VP_Y0);
            int x1 = px + sc(VP_X1), y1 = py + sc(VP_Y1);
            int b = 2;
            vi += rect(verts, idxs, vi, x0, y0, x1, y1, 0.05f, 0.85f);
            vi += rect(verts, idxs, vi, x0, y0, x1, y0 + b, BEV_DK_B, BEV_DK_A);
            vi += rect(verts, idxs, vi, x0, y0, x0 + b, y1, BEV_DK_B, BEV_DK_A);
            vi += rect(verts, idxs, vi, x0, y1 - b, x1, y1, BEV_LT_B, BEV_LT_A);
            vi += rect(verts, idxs, vi, x1 - b, y0, x1, y1, BEV_LT_B, BEV_LT_A);
        }

        // Slot cells: inset bevel like the hotbar
        for (int[] d : defs()) {
            int x0 = px + sc(d[1]), y0 = py + sc(d[2]);
            int x1 = x0 + CELL, y1 = y0 + CELL;
            int b = 2;
            vi += rect(verts, idxs, vi, x0, y0, x1, y1, SLOT_B, SLOT_A);
            vi += rect(verts, idxs, vi, x0, y0, x1, y0 + b, BEV_DK_B, BEV_DK_A);
            vi += rect(verts, idxs, vi, x0, y0, x0 + b, y1, BEV_DK_B, BEV_DK_A);
            vi += rect(verts, idxs, vi, x0, y1 - b, x1, y1, BEV_LT_B, BEV_LT_A);
            vi += rect(verts, idxs, vi, x1 - b, y0, x1, y1, BEV_LT_B, BEV_LT_A);
        }

        // Hover highlight (over the cell body, under the icon pass — MC draws
        // it over the icon, but translucent white washes our icons out less this way)
        if (hovered >= 0) {
            for (int[] d : defs()) {
                if (d[0] != hovered) continue;
                int x0 = px + sc(d[1]) + 2, y0 = py + sc(d[2]) + 2;
                vi += rect(verts, idxs, vi, x0, y0, x0 + CELL - 4, y0 + CELL - 4,
                           HOVER_B, HOVER_A);
            }
        }

        // Crafting arrow: shaft + stepped head pointing at the result slot
        {
            int cxa, cya;
            if (mode == Mode.PLAYER) { cxa = px + sc(136); cya = py + sc(33); }
            else                     { cxa = px + sc(90);  cya = py + sc(33); }
            vi += rect(verts, idxs, vi, cxa, cya, cxa + sc(9), cya + sc(5), 0.65f, 0.9f);
            vi += rect(verts, idxs, vi, cxa + sc(9),  cya - sc(3), cxa + sc(11), cya + sc(8), 0.65f, 0.9f);
            vi += rect(verts, idxs, vi, cxa + sc(11), cya - sc(1), cxa + sc(13), cya + sc(6), 0.65f, 0.9f);
            vi += rect(verts, idxs, vi, cxa + sc(13), cya + sc(1), cxa + sc(14), cya + sc(4), 0.65f, 0.9f);
        }

        float[] va = new float[verts.size()];
        for (int i = 0; i < va.length; i++) va[i] = verts.get(i);
        int[] ia = new int[idxs.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = idxs.get(i);
        panelMesh.upload(va, ia);
    }

    /**
     * The blocky player in the viewport frame, rotating to look at the mouse
     * cursor like Minecraft's. Rendered as real 3D into a scissored sub-
     * viewport with its own perspective projection.
     */
    private void renderPlayerPreview(int w, int h, float mx, float my) {
        int px = panelX(w), py = panelY(h);
        int x0 = px + sc(VP_X0) + 2, y0 = py + sc(VP_Y0) + 2;
        int x1 = px + sc(VP_X1) - 2, y1 = py + sc(VP_Y1) - 2;
        int vw = x1 - x0, vh = y1 - y0;
        if (vw <= 0 || vh <= 0) return;
        int glY = h - y1; // GL viewport origin is bottom-left

        glEnable(GL_SCISSOR_TEST);
        glScissor(x0, glY, vw, vh);
        glViewport(x0, glY, vw, vh);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);

        // MC's GuiInventory.drawEntityOnScreen angles: the head turns twice as
        // far as the body, and only the head pitches — so the player mostly
        // LOOKS at the cursor instead of the whole body spinning.
        float headX = (x0 + x1) / 2f;
        float headY = y0 + vh * 0.22f; // roughly where the head sits on screen
        float dx = (mx - headX) / S;   // back to MC GUI pixels
        float dy = (my - headY) / S;
        float bodyYaw   = (float) Math.atan(dx / 40f) * 20f; // degrees
        float headYaw   = (float) Math.atan(dx / 40f) * 40f;
        float headPitch = (float) -Math.atan(dy / 40f) * 20f;

        previewProj.identity().perspective((float) Math.toRadians(28.0), (float) vw / vh, 1f, 200f);
        previewView.identity().lookAt(0f, 18f, 73.5f, 0f, 16f, 0f, 0f, 1f, 0f); // ~5% smaller than 70
        previewModel.identity().rotateY((float) Math.toRadians(bodyYaw));

        // Idle pose: MC's gentle arm sway; the head does the cursor tracking
        float age = (System.currentTimeMillis() % 3_600_000L) * 0.02f; // ticks
        float swayZ = (float) Math.cos(age * 0.09f) * 0.05f + 0.05f;
        float swayX = (float) Math.sin(age * 0.067f) * 0.05f;
        previewPose.rArmZ = -swayZ;
        previewPose.lArmZ = swayZ;
        previewPose.rArmX = swayX;
        previewPose.lArmX = -swayX;
        previewPose.headRotY = (float) Math.toRadians(headYaw);
        previewPose.headRotX = (float) Math.toRadians(-headPitch);

        modelShader.use();
        modelShader.setUniform("uProjection", previewProj);
        modelShader.setUniform("uView", previewView);
        modelShader.setUniform("uTexture", 0);
        modelShader.setUniform("uAmbient", 1.0f);
        skinTexture.bind(0);
        previewPlayer.render(modelShader, previewModel, previewPose);
        modelShader.detach();

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_SCISSOR_TEST);
        glViewport(0, 0, w, h);
    }

    // ---------- meshes / textures ----------

    /**
     * Half-texel UV inset: icon quads land on fractional pixels, and sampling
     * a tile's exact edge can bleed one texel into the NEIGHBORING atlas tile
     * (a thin translucent line when the neighbor is water). Pull every edge
     * half a texel inward.
     */
    private static float[] insetUV(float[] uv) {
        float du = (uv[2] - uv[0]) / TextureAtlas.TILE_SIZE * 0.5f;
        float dv = (uv[1] - uv[3]) / TextureAtlas.TILE_SIZE * 0.5f;
        return new float[]{uv[0] + du, uv[1] - dv, uv[2] - du, uv[3] + dv};
    }

    /** Dimetric mini block icon centered at (cx, cy) — same look as the hotbar.
     *  Items (sticks) draw as flat sprites instead, like MC item icons. */
    private int appendBlockIcon(ArrayList<Float> verts, ArrayList<Integer> idxs, int vi,
                                BlockType type, float cx, float cy, float s) {
        if (type.item) {
            float[] uv = insetUV(atlas.getUV(type, TextureAtlas.Face.TOP));
            float half = s * 1.35f; // matches the cube icon's visual footprint
            return quad(verts, idxs, vi,
                cx - half, cy - half, uv[0], uv[1],
                cx + half, cy - half, uv[2], uv[1],
                cx + half, cy + half, uv[2], uv[3],
                cx - half, cy + half, uv[0], uv[3], 1.0f);
        }
        float hh = s * 0.5f;
        float fh = s * 1.22f;
        float topY = cy - (hh + fh / 2f);
        float nX = cx,     nY = topY;
        float eX = cx + s, eY = topY + hh;
        float sX = cx,     sY = topY + 2f * hh;
        float wX = cx - s, wY = topY + hh;

        float[] top   = insetUV(atlas.getUV(type, TextureAtlas.Face.TOP));
        float[] north = insetUV(atlas.getUV(type, TextureAtlas.Face.NORTH));
        float[] east  = insetUV(atlas.getUV(type, TextureAtlas.Face.EAST));

        int added = 0;
        added += quad(verts, idxs, vi + added,
            wX, wY, top[0], top[1],   nX, nY, top[2], top[1],
            eX, eY, top[2], top[3],   sX, sY, top[0], top[3], 1.0f);
        added += quad(verts, idxs, vi + added,
            wX, wY, north[0], north[1],       sX, sY, north[2], north[1],
            sX, sY + fh, north[2], north[3],  wX, wY + fh, north[0], north[3], 0.8f);
        added += quad(verts, idxs, vi + added,
            sX, sY, east[0], east[1],         eX, eY, east[2], east[1],
            eX, eY + fh, east[2], east[3],    sX, sY + fh, east[0], east[3], 0.6f);
        return added;
    }

    private void drawIconMesh(Mesh mesh, ArrayList<Float> verts, ArrayList<Integer> idxs) {
        if (verts.isEmpty()) return;
        float[] va = new float[verts.size()];
        for (int i = 0; i < va.length; i++) va[i] = verts.get(i);
        int[] ia = new int[idxs.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = idxs.get(i);
        mesh.upload(va, ia);

        iconShader.use();
        iconShader.setUniform("uProjection", projection);
        iconShader.setUniform("uTexture", 0);
        atlas.bind(0);
        mesh.render();
        iconShader.detach();
    }

    private static Texture solidTexture(int r, int g, int b) {
        ByteBuffer buf = MemoryUtil.memAlloc(4);
        buf.put((byte) r).put((byte) g).put((byte) b).put((byte) 0xFF);
        buf.flip();
        Texture t = new Texture(buf, 1, 1);
        MemoryUtil.memFree(buf);
        return t;
    }

    // ---------- text / labels (same approach as the hotbar) ----------

    /** Hover tooltip: item name on a dark rounded-off box next to the cursor. */
    private void renderTooltip(BlockType type, int mx, int my, int w) {
        Label lab = nameTex.computeIfAbsent(type, t -> {
            int[] wh = new int[2];
            return new Label(makeText(prettyName(t), 12, 1, wh), wh[0], wh[1]);
        });
        int tw = lab.w() * 2, th = lab.h() * 2;
        int x = mx + 18, y = my - th - 10;
        if (x + tw + 12 > w) x = w - tw - 12; // keep it on screen
        if (y < 4) y = my + 24;
        drawSolid(x - 8, y - 5, x + tw + 8, y + th + 5, 0.00f, 0.90f);
        drawSolid(x - 6, y - 3, x + tw + 6, y + th + 3, 0.08f, 0.95f);
        drawLabel(lab, x, y);
    }

    /** "SNOW_GRASS" → "Snow Grass" etc. */
    private static String prettyName(BlockType t) {
        String[] parts = t.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /** One immediate solid quad via ui.frag's [alpha, brightness] layout. */
    private void drawSolid(int x0, int y0, int x1, int y1, float brightness, float alpha) {
        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uTexture", 0);
        whiteTexture.bind(0);
        labelMesh.upload(new float[]{
            x0, y0, 0, alpha, 0, brightness,
            x1, y0, 0, alpha, 0, brightness,
            x1, y1, 0, alpha, 0, brightness,
            x0, y1, 0, alpha, 0, brightness,
        }, new int[]{0, 1, 2, 2, 3, 0});
        labelMesh.render();
        shader.detach();
    }

    private void drawLabel(Label label, int x, int y) {
        drawTexture(label.tex(), x, y, label.w() * 2, label.h() * 2);
    }

    private void drawTexture(Texture tex, int x, int y, int tw, int th) {
        iconShader.use();
        iconShader.setUniform("uProjection", projection);
        iconShader.setUniform("uTexture", 0);
        tex.bind(0);
        labelMesh.upload(new float[]{
            x,      y,      0, 0, 1, 1,
            x + tw, y,      0, 1, 1, 1,
            x + tw, y + th, 0, 1, 0, 1,
            x,      y + th, 0, 0, 0, 1,
        }, new int[]{0, 1, 2, 2, 3, 0});
        labelMesh.render();
        iconShader.detach();
    }

    private Label countTexFor(int count) {
        return countTex.computeIfAbsent(count, n -> {
            int[] wh = new int[2];
            Texture t = makeText(String.valueOf(n), 12, 1, wh);
            return new Label(t, wh[0], wh[1]);
        });
    }

    /** Rasterises white text with an MC-style offset shadow. */
    private static Texture makeText(String text, int fontSize, int scale, int[] outWH) {
        Font font = new Font(Font.MONOSPACED, Font.BOLD, fontSize);
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        FontMetrics fm = tmp.getGraphics().getFontMetrics(font);
        int tw = Math.max(1, fm.stringWidth(text)), th = fm.getHeight();
        BufferedImage img = new BufferedImage(tw + 2, th + 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(new Color(0, 0, 0, 220));
        g.drawString(text, 2, fm.getAscent() + 2);
        g.setColor(Color.WHITE);
        g.drawString(text, 0, fm.getAscent());
        g.dispose();

        int iw = img.getWidth(), ih = img.getHeight();
        outWH[0] = iw * scale;
        outWH[1] = ih * scale;
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
        return t;
    }

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

    /** Solid quad via ui.frag: [x, y, z, alpha(u), 0(v), brightness(light)]. */
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
        shader.cleanup();
        iconShader.cleanup();
        modelShader.cleanup();
        whiteTexture.cleanup();
        skinTexture.cleanup();
        for (Texture t : armorIcons) t.cleanup();
        craftingLabel.cleanup();
        panelMesh.cleanup();
        iconMesh.cleanup();
        cursorMesh.cleanup();
        labelMesh.cleanup();
        previewPlayer.cleanup();
        for (Label l : countTex.values()) l.tex().cleanup();
        countTex.clear();
        for (Label l : nameTex.values()) l.tex().cleanup();
        nameTex.clear();
    }
}
