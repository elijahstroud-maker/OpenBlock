package com.openblock.player;

import com.openblock.world.BlockType;

/**
 * The player's 9-slot hotbar inventory (no full inventory screen yet).
 * Each slot holds one block type stacked up to 64, Minecraft-style. Pickup
 * fills existing part-stacks left-to-right first, then the first empty slot.
 */
public class Inventory {
    public static final int SLOTS     = 9;
    public static final int MAX_STACK = 64;

    /** Length of the pickup "pop" scale animation (MC: popTime = 5 ticks). */
    public static final float POP_TIME = 0.30f;

    private final BlockType[] types  = new BlockType[SLOTS];
    private final int[]       counts = new int[SLOTS];
    /** Per-slot countdown for the pickup pop — the icon scales up and shrinks back. */
    private final float[]     popTimers = new float[SLOTS];
    private boolean popping = false;
    private int selected = 0;
    /** Bumped on every mutation so the hotbar knows when to rebuild its icons. */
    private int revision = 0;

    /** Ticks the pickup-pop animation timers. */
    public void update(float delta) {
        popping = false;
        for (int i = 0; i < SLOTS; i++) {
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
        if (slot >= 0 && slot < SLOTS && slot != selected) {
            selected = slot;
            revision++;
        }
    }

    public BlockType getType(int slot)  { return counts[slot] > 0 ? types[slot] : null; }
    public int       getCount(int slot) { return counts[slot]; }
    public int       getRevision()      { return revision; }

    /** Adds one item. Returns false when every compatible slot is full (item stays on the ground). */
    public boolean add(BlockType type) {
        // Top up an existing stack first (leftmost, like Minecraft)
        for (int i = 0; i < SLOTS; i++) {
            if (counts[i] > 0 && types[i] == type && counts[i] < MAX_STACK) {
                counts[i]++;
                popTimers[i] = POP_TIME;
                revision++;
                return true;
            }
        }
        // Then the first empty slot
        for (int i = 0; i < SLOTS; i++) {
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
