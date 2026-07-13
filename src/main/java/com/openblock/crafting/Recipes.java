package com.openblock.crafting;

import com.openblock.world.BlockType;

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
 */
public final class Recipes {

    /** What a grid crafts into. */
    public record Result(BlockType type, int count) { }

    private record Recipe(BlockType[][] pattern, BlockType out, int count) { }

    private static final Recipe[] RECIPES = {
        new Recipe(new BlockType[][]{{BlockType.LOG}}, BlockType.PLANKS, 4),
        new Recipe(new BlockType[][]{{BlockType.PLANKS}, {BlockType.PLANKS}},
                   BlockType.STICK, 4),
        new Recipe(new BlockType[][]{{BlockType.PLANKS, BlockType.PLANKS},
                                     {BlockType.PLANKS, BlockType.PLANKS}},
                   BlockType.CRAFTING_TABLE, 1),
    };

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
