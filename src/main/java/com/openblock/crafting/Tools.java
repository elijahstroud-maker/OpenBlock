package com.openblock.crafting;

import com.openblock.world.BlockType;

/**
 * The tool registry: material stats and mining rules, Minecraft-exact.
 * A tool is a non-stackable BlockType item that wears down as it's used.
 */
public final class Tools {

    /** Mining speed multiplier, max uses, and pickaxe harvest tier — MC's table. */
    public enum Material {
        WOOD   (2f,  59,   0),
        GOLD   (12f, 32,   0), // blazing fast, but shares wood's harvest tier
        STONE  (4f,  131,  1),
        IRON   (6f,  250,  2),
        DIAMOND(8f,  1561, 3);

        public final float efficiency;
        public final int   durability;
        public final int   harvestLevel;

        Material(float efficiency, int durability, int harvestLevel) {
            this.efficiency   = efficiency;
            this.durability   = durability;
            this.harvestLevel = harvestLevel;
        }
    }

    public enum Type { PICKAXE, AXE, SWORD, HOE }

    public record Info(Material material, Type type) { }

    private Tools() { }

    /** Material + type for a tool BlockType, or null if it isn't one. */
    public static Info infoFor(BlockType t) {
        if (t == null) return null;
        return switch (t) {
            case WOODEN_PICKAXE  -> new Info(Material.WOOD, Type.PICKAXE);
            case WOODEN_AXE      -> new Info(Material.WOOD, Type.AXE);
            case WOODEN_SWORD    -> new Info(Material.WOOD, Type.SWORD);
            case WOODEN_HOE      -> new Info(Material.WOOD, Type.HOE);
            case STONE_PICKAXE   -> new Info(Material.STONE, Type.PICKAXE);
            case STONE_AXE       -> new Info(Material.STONE, Type.AXE);
            case STONE_SWORD     -> new Info(Material.STONE, Type.SWORD);
            case STONE_HOE       -> new Info(Material.STONE, Type.HOE);
            case IRON_PICKAXE    -> new Info(Material.IRON, Type.PICKAXE);
            case IRON_AXE        -> new Info(Material.IRON, Type.AXE);
            case IRON_SWORD      -> new Info(Material.IRON, Type.SWORD);
            case IRON_HOE        -> new Info(Material.IRON, Type.HOE);
            case GOLDEN_PICKAXE  -> new Info(Material.GOLD, Type.PICKAXE);
            case GOLDEN_AXE      -> new Info(Material.GOLD, Type.AXE);
            case GOLDEN_SWORD    -> new Info(Material.GOLD, Type.SWORD);
            case GOLDEN_HOE      -> new Info(Material.GOLD, Type.HOE);
            case DIAMOND_PICKAXE -> new Info(Material.DIAMOND, Type.PICKAXE);
            case DIAMOND_AXE     -> new Info(Material.DIAMOND, Type.AXE);
            case DIAMOND_SWORD   -> new Info(Material.DIAMOND, Type.SWORD);
            case DIAMOND_HOE     -> new Info(Material.DIAMOND, Type.HOE);
            default              -> null;
        };
    }

    public static boolean isTool(BlockType t) { return infoFor(t) != null; }

    public static int maxDurability(BlockType t) {
        Info i = infoFor(t);
        return i == null ? 0 : i.material().durability;
    }

    /** The tool type that's "effective" (fast) on this block, or null. */
    public static Type effectiveTypeFor(BlockType block) {
        return switch (block) {
            case STONE, COBBLESTONE, FURNACE, FURNACE_LIT,
                 COAL_ORE, IRON_ORE, COPPER_ORE, GOLD_ORE,
                 LAPIS_ORE, REDSTONE_ORE, DIAMOND_ORE, EMERALD_ORE -> Type.PICKAXE;
            case LOG, PLANKS, CRAFTING_TABLE -> Type.AXE;
            default -> null;
        };
    }

    /**
     * Pickaxe harvest level required for this block to actually drop something
     * (MC: mining stone-family blocks with no/too-weak pickaxe breaks the
     * block but yields nothing). -1 = not gated; always drops regardless of tool.
     */
    public static int requiredLevelFor(BlockType block) {
        return switch (block) {
            case STONE, COBBLESTONE, COAL_ORE, FURNACE, FURNACE_LIT -> 0; // wood+
            case IRON_ORE, LAPIS_ORE                                -> 1; // stone+
            case GOLD_ORE, REDSTONE_ORE, DIAMOND_ORE, EMERALD_ORE   -> 2; // iron+
            default                                                 -> -1;
        };
    }

    /** Speed multiplier mining {@code block} with {@code heldTool} (null = bare hand). */
    public static float speedMultiplier(BlockType block, BlockType heldTool) {
        Info info = infoFor(heldTool);
        if (info == null) return 1f;
        // MC: swords cut through leaves (and cobwebs, which we don't have) fast
        if (info.type() == Type.SWORD && block == BlockType.LEAVES) return 15f;
        Type effective = effectiveTypeFor(block);
        return (effective != null && effective == info.type()) ? info.material().efficiency : 1f;
    }

    /**
     * Durability a tool loses per block broken, Minecraft's rule: swords cost 2,
     * hoes cost 0 (they only wear from tilling, which we don't have), every
     * other tool costs 1. Non-tools return 0.
     */
    public static int breakDurabilityCost(BlockType tool) {
        Info info = infoFor(tool);
        if (info == null) return 0;
        return switch (info.type()) {
            case SWORD -> 2;
            case HOE   -> 0;
            default    -> 1;
        };
    }

    /** Would mining this block with this tool (or bare hand) actually drop anything? */
    public static boolean canHarvest(BlockType block, BlockType heldTool) {
        int required = requiredLevelFor(block);
        if (required < 0) return true; // ungated block: hand is always fine
        Info info = infoFor(heldTool);
        return info != null && info.type() == Type.PICKAXE && info.material().harvestLevel >= required;
    }
}
