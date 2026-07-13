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
    STICK         (false, false, true); // crafting ITEM — lives in inventories, never placed

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
