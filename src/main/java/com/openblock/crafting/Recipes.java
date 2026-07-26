package com.openblock.crafting;

import com.openblock.world.BlockType;

import java.util.ArrayList;
import java.util.List;

/**
 * The crafting recipe book. A grid (2x2 from the inventory or 3x3 from a
 * crafting table) is matched by normalizing the occupied cells to their
 * bounding box and comparing against each recipe's pattern — so a 1x2 stick
 * recipe matches anywhere in the grid, exactly like Minecraft's shaped
 * recipes. Recipes so far (all vanilla):
 *
 *   log            → 4 planks
 *   plank over plank → 4 sticks
 *   2x2 planks     → crafting table
 *   cobblestone ring → furnace (3x3 table only)
 *   material x pickaxe/axe/sword/hoe shapes → tools, one of 5 materials each
 *   (all tool shapes are 3-tall, so they need the crafting table, like MC)
 */
public final class Recipes {

    /** What a grid crafts into. */
    public record Result(BlockType type, int count) { }

    private record Recipe(BlockType[][] pattern, BlockType out, int count) { }

    private static final Recipe[] RECIPES = buildAll();

    private static Recipe[] buildAll() {
        List<Recipe> list = new ArrayList<>(List.of(
            new Recipe(new BlockType[][]{{BlockType.LOG}}, BlockType.PLANKS, 4),
            new Recipe(new BlockType[][]{{BlockType.PLANKS}, {BlockType.PLANKS}},
                       BlockType.STICK, 4),
            new Recipe(new BlockType[][]{{BlockType.PLANKS, BlockType.PLANKS},
                                         {BlockType.PLANKS, BlockType.PLANKS}},
                       BlockType.CRAFTING_TABLE, 1),
            new Recipe(new BlockType[][]{
                    {BlockType.COBBLESTONE, BlockType.COBBLESTONE, BlockType.COBBLESTONE},
                    {BlockType.COBBLESTONE, null,                  BlockType.COBBLESTONE},
                    {BlockType.COBBLESTONE, BlockType.COBBLESTONE, BlockType.COBBLESTONE}},
                       BlockType.FURNACE, 1)
        ));

        // Tools: 5 materials x 4 shapes, MC's exact patterns (all 3 rows tall,
        // so — like real Minecraft — none of these fit the inventory's 2x2
        // and a crafting table is required.
        Object[][] materials = {
            {BlockType.PLANKS,      BlockType.WOODEN_PICKAXE, BlockType.WOODEN_AXE, BlockType.WOODEN_SWORD, BlockType.WOODEN_HOE},
            {BlockType.COBBLESTONE, BlockType.STONE_PICKAXE,  BlockType.STONE_AXE,  BlockType.STONE_SWORD,  BlockType.STONE_HOE},
            {BlockType.IRON_INGOT,  BlockType.IRON_PICKAXE,   BlockType.IRON_AXE,   BlockType.IRON_SWORD,   BlockType.IRON_HOE},
            {BlockType.GOLD_INGOT,  BlockType.GOLDEN_PICKAXE, BlockType.GOLDEN_AXE, BlockType.GOLDEN_SWORD, BlockType.GOLDEN_HOE},
            {BlockType.DIAMOND,     BlockType.DIAMOND_PICKAXE,BlockType.DIAMOND_AXE,BlockType.DIAMOND_SWORD,BlockType.DIAMOND_HOE},
        };
        BlockType S = BlockType.STICK;
        for (Object[] row : materials) {
            BlockType mat = (BlockType) row[0];
            list.add(new Recipe(new BlockType[][]{
                    {mat, mat, mat},
                    {null, S,   null},
                    {null, S,   null}},
                    (BlockType) row[1], 1)); // pickaxe
            // Axe/hoe are left-right asymmetric — MC accepts either mirror, so
            // both orientations are registered.
            list.add(new Recipe(new BlockType[][]{
                    {mat,  mat},
                    {mat,  S},
                    {null, S}},
                    (BlockType) row[2], 1)); // axe (right-handed)
            list.add(new Recipe(new BlockType[][]{
                    {mat, mat},
                    {S,   mat},
                    {S,   null}},
                    (BlockType) row[2], 1)); // axe (mirrored)
            list.add(new Recipe(new BlockType[][]{
                    {mat},
                    {mat},
                    {S}},
                    (BlockType) row[3], 1)); // sword
            list.add(new Recipe(new BlockType[][]{
                    {mat,  mat},
                    {null, S},
                    {null, S}},
                    (BlockType) row[4], 1)); // hoe (right-handed)
            list.add(new Recipe(new BlockType[][]{
                    {mat,  mat},
                    {S,    null},
                    {S,    null}},
                    (BlockType) row[4], 1)); // hoe (mirrored)
        }
        return list.toArray(new Recipe[0]);
    }

    private Recipes() { }

    /**
     * Matches the grid (row-major, length w*w, null = empty) against the book.
     * Returns null when nothing matches.
     */
    public static Result match(BlockType[] grid, int w) {
        // Bounding box of the occupied cells
        int minR = w, maxR = -1, minC = w, maxC = -1;
        for (int r = 0; r < w; r++) {
            for (int c = 0; c < w; c++) {
                if (grid[r * w + c] != null) {
                    minR = Math.min(minR, r); maxR = Math.max(maxR, r);
                    minC = Math.min(minC, c); maxC = Math.max(maxC, c);
                }
            }
        }
        if (maxR < 0) return null; // empty grid

        int rows = maxR - minR + 1, cols = maxC - minC + 1;
        outer:
        for (Recipe recipe : RECIPES) {
            if (recipe.pattern.length != rows || recipe.pattern[0].length != cols) continue;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (grid[(minR + r) * w + (minC + c)] != recipe.pattern[r][c]) {
                        continue outer;
                    }
                }
            }
            return new Result(recipe.out, recipe.count);
        }
        return null;
    }
}
