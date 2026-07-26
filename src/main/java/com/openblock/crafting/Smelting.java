package com.openblock.crafting;

import com.openblock.world.BlockType;

/**
 * The smelting book: what a furnace turns inputs into, and how long each fuel
 * burns. Values are Minecraft's exactly, converted from ticks to seconds
 * (20 ticks = 1s): one item smelts in 200 ticks = 10s, coal burns 1600 ticks
 * = 80s (8 items), wood things 300 ticks = 15s (1.5 items), a stick 100 ticks
 * = 5s (half an item).
 */
public final class Smelting {

    /** Seconds to smelt one item (MC: 200 ticks). */
    public static final float COOK_TIME = 10f;

    private Smelting() { }

    /** What this block/item smelts into, or null if the furnace rejects it. */
    public static BlockType resultFor(BlockType in) {
        if (in == null) return null;
        return switch (in) {
            case IRON_ORE    -> BlockType.IRON_INGOT;
            case GOLD_ORE    -> BlockType.GOLD_INGOT;
            case COPPER_ORE  -> BlockType.COPPER_INGOT;
            case SAND        -> BlockType.GLASS;
            case COBBLESTONE -> BlockType.STONE;
            case LOG         -> BlockType.CHARCOAL;
            default          -> null;
        };
    }

    /** How long this fuel burns in seconds; 0 = not a fuel. MC's exact table. */
    public static float burnTime(BlockType fuel) {
        if (fuel == null) return 0f;
        return switch (fuel) {
            case COAL, CHARCOAL              -> 80f; // 1600 ticks
            case LOG, PLANKS, CRAFTING_TABLE -> 15f; //  300 ticks
            case STICK                       -> 5f;  //  100 ticks
            default                          -> 0f;
        };
    }

    public static boolean isFuel(BlockType b) { return burnTime(b) > 0f; }
}
