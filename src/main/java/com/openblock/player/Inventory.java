package com.openblock.player;

import com.openblock.world.BlockType;

/**
 * The player's full inventory, Minecraft-layout: 36 main slots (0-8 are the
 * hotbar, 9-35 the storage grid rows top-to-bottom), 4 armor slots, and a
 * 2x2 crafting grid (no recipes yet — the grid just holds items). Each slot
 * holds one block type stacked up to 64. Pickup fills existing part-stacks
 * hotbar-first, then the first empty slot, hotbar-first.
 */
public class Inventory {
    public static final int HOTBAR_SIZE = 9;
    public static final int MAIN_SIZE   = 36;  // hotbar + 27 storage
    public static final int ARMOR_SIZE  = 4;
    /** 9 craft cells: the inventory's 2x2 uses 0-3, a crafting table all 9. */
    public static final int CRAFT_SIZE  = 9;
    public static final int MAX_STACK   = 64;

    /** Length of the pickup "pop" scale animation (MC: popTime = 5 ticks). */
    public static final float POP_TIME = 0.30f;

    private final BlockType[] types  = new BlockType[MAIN_SIZE];
    private final int[]       counts = new int[MAIN_SIZE];
    private final BlockType[] armorTypes  = new BlockType[ARMOR_SIZE];
    private final int[]       armorCounts = new int[ARMOR_SIZE];
    private final BlockType[] craftTypes  = new BlockType[CRAFT_SIZE];
    private final int[]       craftCounts = new int[CRAFT_SIZE];
    /** Per-slot countdown for the pickup pop — the icon scales up and shrinks back. */
    private final float[]     popTimers = new float[MAIN_SIZE];
    private boolean popping = false;
    private int selected = 0;
    /** Bumped on every mutation so the hotbar/screen know when to rebuild icons. */
    private int revision = 0;

    /** Ticks the pickup-pop animation timers. */
    public void update(float delta) {
        popping = false;
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (popTimers[i] > 0f) {
                popTimers[i] -= delta;
                if (popTimers[i] <= 0f) {
                    popTimers[i] = 0f;
                    revision++; // one final rebuild lands the icon back at 1x
                } else {
                    popping = true;
                }
            }
        }
    }

    /** True while any slot's pickup pop is still animating. */
    public boolean anyPopping() { return popping; }

    /** Icon scale for a slot: jumps to ~1.3x on pickup, eases back to 1x. */
    public float popScale(int slot) {
        return 1f + 0.30f * Math.max(0f, popTimers[slot] / POP_TIME);
    }

    public int getSelected() { return selected; }

    public void setSelected(int slot) {
        if (slot >= 0 && slot < HOTBAR_SIZE && slot != selected) {
            selected = slot;
            revision++;
        }
    }

    public BlockType getType(int slot)  { return counts[slot] > 0 ? types[slot] : null; }
    public int       getCount(int slot) { return counts[slot]; }
    public int       getRevision()      { return revision; }

    public BlockType getArmorType(int i)  { return armorCounts[i] > 0 ? armorTypes[i] : null; }
    public int       getArmorCount(int i) { return armorCounts[i]; }
    public BlockType getCraftType(int i)  { return craftCounts[i] > 0 ? craftTypes[i] : null; }
    public int       getCraftCount(int i) { return craftCounts[i]; }

    /** Overwrites a main slot (inventory-screen drag & drop). Count 0 clears it. */
    public void set(int slot, BlockType type, int count) {
        types[slot]  = count > 0 ? type : null;
        counts[slot] = Math.max(0, count);
        revision++;
    }

    public void setArmor(int i, BlockType type, int count) {
        armorTypes[i]  = count > 0 ? type : null;
        armorCounts[i] = Math.max(0, count);
        revision++;
    }

    public void setCraft(int i, BlockType type, int count) {
        craftTypes[i]  = count > 0 ? type : null;
        craftCounts[i] = Math.max(0, count);
        revision++;
    }

    /** Adds one item. Returns false when every compatible slot is full (item stays on the ground). */
    public boolean add(BlockType type) {
        // Top up an existing stack first (hotbar before storage, like Minecraft)
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (counts[i] > 0 && types[i] == type && counts[i] < MAX_STACK) {
                counts[i]++;
                popTimers[i] = POP_TIME;
                revision++;
                return true;
            }
        }
        // Then the first empty slot
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (counts[i] == 0) {
                types[i]  = type;
                counts[i] = 1;
                popTimers[i] = POP_TIME;
                revision++;
                return true;
            }
        }
        return false;
    }

    /** Removes one item from the slot (block placed). No-op on an empty slot. */
    public void consume(int slot) {
        if (counts[slot] > 0) {
            counts[slot]--;
            revision++;
        }
    }
}
