package com.openblock.world;

import com.openblock.crafting.Smelting;
import com.openblock.player.Inventory;

/**
 * The state of one placed furnace — Minecraft's TileEntityFurnace logic on a
 * seconds clock. Created lazily the first time the player opens the block's
 * GUI and ticked by the world every frame, so smelting keeps running with the
 * screen closed.
 *
 * MC rules carried over:
 *  - fuel only ignites when there's a smeltable input AND the output has room
 *  - a lit furnace keeps burning its current fuel even if the input leaves
 *  - with the fire out, cook progress drains at double speed instead of resetting
 */
public class Furnace {
    public static final int SLOT_IN   = 0;
    public static final int SLOT_FUEL = 1;
    public static final int SLOT_OUT  = 2;

    public final int x, y, z;

    private final BlockType[] types  = new BlockType[3];
    private final int[]       counts = new int[3];

    /** Seconds left on the currently burning fuel. */
    private float burnLeft  = 0f;
    /** Full burn time of that fuel — the flame gauge's denominator. */
    private float burnTotal = 0f;
    /** Seconds of smelting done on the current input item. */
    private float cook = 0f;

    public Furnace(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public BlockType getType(int slot)  { return types[slot]; }
    public int       getCount(int slot) { return counts[slot]; }

    public void set(int slot, BlockType type, int count) {
        if (type == null || count <= 0) {
            types[slot] = null;
            counts[slot] = 0;
        } else {
            types[slot] = type;
            counts[slot] = Math.min(count, Inventory.MAX_STACK);
        }
    }

    public boolean isBurning() { return burnLeft > 0f; }

    /** Flame gauge fill 0..1 (how much of the current fuel remains). */
    public float burnFraction() {
        return burnTotal <= 0f ? 0f : Math.min(1f, burnLeft / burnTotal);
    }

    /** Arrow fill 0..1 (progress on the item being smelted). */
    public float cookFraction() {
        return Math.min(1f, cook / Smelting.COOK_TIME);
    }

    /** Could one more smelt of the current input land in the output slot? */
    private boolean canSmelt() {
        BlockType result = Smelting.resultFor(types[SLOT_IN]);
        if (result == null) return false;
        return types[SLOT_OUT] == null
            || (types[SLOT_OUT] == result && counts[SLOT_OUT] < Inventory.MAX_STACK);
    }

    /** Advances the furnace. Call every frame; cheap when idle. */
    public void tick(float delta) {
        if (burnLeft > 0f) burnLeft = Math.max(0f, burnLeft - delta);

        boolean smeltable = canSmelt();

        // Fire out + work to do + fuel present → consume one fuel item
        if (burnLeft <= 0f && smeltable && Smelting.isFuel(types[SLOT_FUEL])) {
            burnTotal = burnLeft = Smelting.burnTime(types[SLOT_FUEL]);
            set(SLOT_FUEL, types[SLOT_FUEL], counts[SLOT_FUEL] - 1);
        }

        if (burnLeft > 0f && smeltable) {
            cook += delta;
            if (cook >= Smelting.COOK_TIME) {
                cook = 0f;
                BlockType result = Smelting.resultFor(types[SLOT_IN]);
                set(SLOT_OUT, result, counts[SLOT_OUT] + 1);
                set(SLOT_IN, types[SLOT_IN], counts[SLOT_IN] - 1);
            }
        } else if (cook > 0f) {
            cook = Math.max(0f, cook - 2f * delta); // MC: unlit progress drains 2x
        }
    }
}
