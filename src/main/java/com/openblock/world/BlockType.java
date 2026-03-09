package com.openblock.world;

public enum BlockType {
    AIR     (false),
    GRASS   (true),
    DIRT    (true),
    STONE   (true),
    SAND    (true),
    BEDROCK (true);

    public final boolean solid;

    BlockType(boolean solid) {
        this.solid = solid;
    }

    private static final BlockType[] VALUES = values();

    public static BlockType fromOrdinal(int ordinal) {
        return VALUES[ordinal];
    }
}
