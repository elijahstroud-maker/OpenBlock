package com.openblock.world;

/**
 * A gravity-affected block (sand, gravel) mid-fall, Minecraft-style: the
 * world block is swapped for this entity, it accelerates straight down, and
 * lands as a real block again on the first solid surface below (replacing
 * water it lands in, like MC). If something solid occupies the landing cell
 * by the time it gets there, the block pops off as an item drop instead.
 */
public class FallingBlock {
    private static final float GRAVITY  = -18f;
    private static final float TERMINAL = -40f;

    public final BlockType type;
    public final int bx, bz;
    /** Y of the block's centre while falling. */
    public float centerY;
    private float vy = 0f;
    /** Landed (or fell out of the world) — remove the entity. */
    public boolean done = false;

    public FallingBlock(BlockType type, int bx, int by, int bz) {
        this.type = type;
        this.bx = bx;
        this.bz = bz;
        this.centerY = by + 0.5f;
    }

    public void update(World world, float delta) {
        vy = Math.max(vy + GRAVITY * delta, TERMINAL);
        float newY = centerY + vy * delta;

        if (newY < -2f) { // fell out of the world
            done = true;
            return;
        }

        // First solid cell below the block's bottom face?
        int support = (int) Math.floor(newY - 0.5f - 0.001f);
        if (support >= 0 && world.getBlock(bx, support, bz).solid) {
            int landCell = support + 1;
            if (world.getBlock(bx, landCell, bz).solid) {
                // Squeezed out (something got placed here mid-fall): drop as item
                world.spawnDrop(type, bx, landCell + 1, bz);
            } else {
                world.setBlock(bx, landCell, bz, type); // replaces water too
            }
            done = true;
        } else {
            centerY = newY;
        }
    }
}
