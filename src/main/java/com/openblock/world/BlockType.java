package com.openblock.world;

public enum BlockType {
    AIR        (false, false),
    GRASS      (true,  true),
    DIRT       (true,  true),
    STONE      (true,  true),
    SAND       (true,  true),
    BEDROCK    (true,  true),
    SNOW_GRASS (true,  true),
    LOG        (true,  true),
    LEAVES     (true,  false),
    CACTUS     (true,  true),
    WATER         (false, false),  // not solid, not opaque — player passes through, faces visible
    WATER_FLOWING (false, false),  // same as WATER but rendered at 7/8 height (visual flow indicator)
    GRAVEL        (true,  true),
    COBBLESTONE   (true,  true),   // dropped by mined stone; appended so ordinals stay stable
    PLANKS        (true,  true),
    CRAFTING_TABLE(true,  true),
    STICK         (false, false, true), // crafting ITEM — lives in inventories, never placed
    // Ores (0.7). Iron/gold/copper drop themselves (smelting later);
    // the rest drop their item.
    COAL_ORE      (true,  true),
    IRON_ORE      (true,  true),
    COPPER_ORE    (true,  true),
    GOLD_ORE      (true,  true),
    LAPIS_ORE     (true,  true),
    REDSTONE_ORE  (true,  true),
    DIAMOND_ORE   (true,  true),
    EMERALD_ORE   (true,  true),
    // Ore drop items
    COAL          (false, false, true),
    LAPIS_LAZULI  (false, false, true),
    REDSTONE      (false, false, true),
    DIAMOND       (false, false, true),
    EMERALD       (false, false, true),
    // Furnace & smelting (0.7). Lit furnace is its own block, old-MC style,
    // so the glowing front is just a texture swap when the fire starts.
    FURNACE       (true,  true),
    FURNACE_LIT   (true,  true),
    GLASS         (true,  false), // solid but see-through: neighbors keep their faces
    IRON_INGOT    (false, false, true),
    GOLD_INGOT    (false, false, true),
    COPPER_INGOT  (false, false, true),
    CHARCOAL      (false, false, true), // smelted from LOG; also a coal-tier fuel
    // Tools (0.7). Non-stackable (Inventory caps these at 1 per slot) and
    // wear down with use — see crafting/Tools.java for material stats.
    WOODEN_PICKAXE(false, false, true), WOODEN_AXE(false, false, true),
    WOODEN_SWORD  (false, false, true), WOODEN_HOE(false, false, true),
    STONE_PICKAXE (false, false, true), STONE_AXE (false, false, true),
    STONE_SWORD   (false, false, true), STONE_HOE (false, false, true),
    IRON_PICKAXE  (false, false, true), IRON_AXE  (false, false, true),
    IRON_SWORD    (false, false, true), IRON_HOE  (false, false, true),
    GOLDEN_PICKAXE(false, false, true), GOLDEN_AXE(false, false, true),
    GOLDEN_SWORD  (false, false, true), GOLDEN_HOE(false, false, true),
    DIAMOND_PICKAXE(false, false, true), DIAMOND_AXE(false, false, true),
    DIAMOND_SWORD (false, false, true), DIAMOND_HOE(false, false, true);

    public final boolean solid;
    /** Opaque blocks hide adjacent faces; non-opaque (AIR, LEAVES) let faces show. */
    public final boolean opaque;
    /** Items render as flat sprites (icons, hand, drops) and can't be placed. */
    public final boolean item;

    BlockType(boolean solid, boolean opaque) {
        this(solid, opaque, false);
    }

    BlockType(boolean solid, boolean opaque, boolean item) {
        this.solid = solid;
        this.opaque = opaque;
        this.item = item;
    }

    private static final BlockType[] VALUES = values();

    public static BlockType fromOrdinal(int ordinal) {
        return VALUES[ordinal];
    }
}
