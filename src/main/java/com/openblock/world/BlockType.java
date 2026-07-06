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
    GRAVEL        (true,  true);

    public final boolean solid;
    /** Opaque blocks hide adjacent faces; non-opaque (AIR, LEAVES) let faces show. */
    public final boolean opaque;

    BlockType(boolean solid, boolean opaque) {
        this.solid = solid;
        this.opaque = opaque;
    }

    private static final BlockType[] VALUES = values();

    public static BlockType fromOrdinal(int ordinal) {
        return VALUES[ordinal];
    }
}
