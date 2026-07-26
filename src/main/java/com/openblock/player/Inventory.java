package com.openblock.player;

import com.openblock.crafting.Tools;
import com.openblock.world.BlockType;

/**
 * The player's full inventory, Minecraft-layout: 36 main slots (0-8 are the
 * hotbar, 9-35 the storage grid rows top-to-bottom), 4 armor slots, and a
 * 2x2 crafting grid (no recipes yet — the grid just holds items). Each slot
 * holds one block type stacked up to 64 — except tools, which never stack
 * (maxStackFor caps them at 1) and carry a per-slot durability instead.
 * Pickup fills existing part-stacks hotbar-first, then the first empty slot,
 * hotbar-first.
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

    /** Tools never stack (each slot is one physical item with its own wear). */
    public static int maxStackFor(BlockType type) {
        return Tools.isTool(type) ? 1 : MAX_STACK;
    }

    private final BlockType[] types  = new BlockType[MAIN_SIZE];
    private final int[]       counts = new int[MAIN_SIZE];
    /** Remaining uses for a tool in this slot; meaningless (left at 0) otherwise. */
    private final int[]       durability = new int[MAIN_SIZE];
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
    /** Remaining uses of the tool in this slot (0 for anything that isn't a tool). */
    public int       getDurability(int slot) { return counts[slot] > 0 ? durability[slot] : 0; }
    public int       getRevision()      { return revision; }

    public BlockType getArmorType(int i)  { return armorCounts[i] > 0 ? armorTypes[i] : null; }
    public int       getArmorCount(int i) { return armorCounts[i]; }
    public BlockType getCraftType(int i)  { return craftCounts[i] > 0 ? craftTypes[i] : null; }
    public int       getCraftCount(int i) { return craftCounts[i]; }

    /** Overwrites a main slot (inventory-screen drag & drop). Count 0 clears it. */
    public void set(int slot, BlockType type, int count) {
        set(slot, type, count, count > 0 ? Tools.maxDurability(type) : 0);
    }

    /** Same, but preserving an explicit durability (moving an existing worn tool). */
    public void set(int slot, BlockType type, int count, int durabilityValue) {
        types[slot]      = count > 0 ? type : null;
        counts[slot]     = Math.max(0, count);
        durability[slot] = count > 0 ? durabilityValue : 0;
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

    /** Adds one freshly-created item (full durability if it's a tool). */
    public boolean add(BlockType type) {
        return add(type, Tools.maxDurability(type));
    }

    /**
     * Adds one item carrying an explicit durability (picking a dropped tool
     * back up). Returns false when every compatible slot is full (item stays
     * on the ground). Tools (maxStack 1) always fall through to the
     * first-empty-slot search since an existing slot is never "topped up".
     */
    public boolean add(BlockType type, int durabilityValue) {
        int maxStack = maxStackFor(type);
        // Top up an existing stack first (hotbar before storage, like Minecraft)
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (counts[i] > 0 && types[i] == type && counts[i] < maxStack) {
                counts[i]++;
                popTimers[i] = POP_TIME;
                revision++;
                return true;
            }
        }
        // Then the first empty slot
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (counts[i] == 0) {
                types[i]      = type;
                counts[i]     = 1;
                durability[i] = durabilityValue;
                popTimers[i]  = POP_TIME;
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

    /**
     * Wears down the tool in this slot by {@code amount} uses; the tool breaks
     * (slot clears) once durability runs out, exactly like Minecraft.
     * No-op if the slot doesn't hold a tool or amount <= 0.
     */
    public void damageTool(int slot, int amount) {
        if (amount <= 0 || counts[slot] <= 0 || !Tools.isTool(types[slot])) return;
        durability[slot] -= amount;
        if (durability[slot] <= 0) {
            types[slot]  = null;
            counts[slot] = 0;
            durability[slot] = 0;
        }
        revision++;
    }
}
