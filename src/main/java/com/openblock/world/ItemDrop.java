package com.openblock.world;

import org.joml.Vector3f;

import java.util.Random;

/**
 * A dropped block item, Minecraft-style: a mini block that pops out of the
 * broken block with a little random velocity, falls with gravity, rests on
 * the ground (spinning and bobbing — that part is render-only), floats up
 * out of water, gets pushed up when a block is placed in its cell, and
 * despawns after five minutes.
 */
public class ItemDrop {
    /** Half-extent of the physics box (MC item entities are 0.25 wide). */
    public static final float HALF = 0.125f;
    private static final float GRAVITY      = -16.0f; // MC: 0.04 blocks/tick^2
    private static final float TERMINAL     = -20.0f;
    private static final float DESPAWN_AGE  = 300f;   // 5 minutes, like MC
    /** How fast a buried item rises out of a block placed on top of it. */
    private static final float ESCAPE_SPEED = 3.2f;
    private static final Random RNG = new Random();

    public final BlockType type;
    /** Centre of the item's physics box. */
    public final Vector3f pos = new Vector3f();
    public final Vector3f vel = new Vector3f();
    public int   count = 1;      // nearby same-type drops merge, up to 64
    public float age   = 0f;
    /** Per-item phase so a pile of drops doesn't spin/bob in lockstep. */
    public final float phase = RNG.nextFloat() * (float) (Math.PI * 2);
    /** Grace period before it can be picked up (MC: 10 ticks). */
    public float pickupDelay = 0.5f;
    public boolean onGround = false;
    /**
     * Magnet pickup: once the player takes the stack the entity becomes a pure
     * visual that swooshes to them (the items are already in the inventory).
     * The player refreshes {@link #collectTarget} each tick while it flies.
     */
    public boolean collecting = false;
    public final Vector3f collectTarget = new Vector3f();
    /** Reached the player — remove the entity. */
    public boolean collected = false;

    /** Pops out of a broken block at its centre with a small random kick. */
    public ItemDrop(BlockType type, int bx, int by, int bz) {
        this.type = type;
        pos.set(bx + 0.5f + (RNG.nextFloat() - 0.5f) * 0.4f,
                by + 0.5f,
                bz + 0.5f + (RNG.nextFloat() - 0.5f) * 0.4f);
        vel.set((RNG.nextFloat() - 0.5f) * 2.0f,
                2.8f + RNG.nextFloat() * 0.8f,
                (RNG.nextFloat() - 0.5f) * 2.0f);
    }

    /** A drop tossed by the player (Q): starts at a point with a throw velocity. */
    public ItemDrop(BlockType type, float x, float y, float z,
                    float vx, float vy, float vz) {
        this.type = type;
        pos.set(x, y, z);
        vel.set(vx, vy, vz);
        pickupDelay = 2.0f; // MC: 40 ticks before the thrower can re-collect it
    }

    /** True once the item should be removed (despawn timer ran out). */
    public boolean expired() { return age > DESPAWN_AGE; }

    public void update(World world, float delta) {
        age += delta;
        if (pickupDelay > 0f) pickupDelay -= delta;

        // Flying to the player after pickup: no physics, just a quick swoosh
        // that accelerates as it closes in (still spinning via age).
        if (collecting) {
            float dx = collectTarget.x - pos.x;
            float dy = collectTarget.y - pos.y;
            float dz = collectTarget.z - pos.z;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float step = Math.max(6f, dist * 14f) * delta;
            if (step >= dist || dist < 0.1f) {
                collected = true;
            } else {
                pos.add(dx / dist * step, dy / dist * step, dz / dist * step);
            }
            return;
        }

        // Buried (a block was placed onto us): rise smoothly out of it, MC-style,
        // ignoring collision until clear. Box test (not just the centre point) so
        // a drop hanging over a cell edge is still caught; shrunk a whisker so
        // resting exactly on a block top never counts as buried.
        if (solidAt(world, pos.x, pos.y, pos.z, HALF - 0.03f)) {
            pos.y += ESCAPE_SPEED * delta;
            vel.set(0f, 0f, 0f);
            onGround = false;
            return;
        }

        BlockType at = world.getBlock(blockX(), blockY(), blockZ());
        boolean inWater = at == BlockType.WATER || at == BlockType.WATER_FLOWING;

        if (inWater) {
            BlockType above = world.getBlock(blockX(), blockY() + 1, blockZ());
            boolean topLayer = above != BlockType.WATER && above != BlockType.WATER_FLOWING;
            if (topLayer) {
                // Near the surface: soft damped spring toward the float line —
                // eases in with a couple of gentle dips instead of the old
                // rocket-up / gravity-down oscillation. The render-side bob
                // supplies the idle bobbing once this settles. Downward speed
                // is allowed past the spring cap so a thrown drop plunges in
                // first, then rises back up and bobs.
                float target = blockY() + 1f - 0.10f;
                vel.y += (target - pos.y) * 14f * delta;
                vel.y *= Math.max(0f, 1f - delta * 6f);
                vel.y  = Math.max(-2.5f, Math.min(1.2f, vel.y));
            } else {
                // Deep underwater: steady drift up toward the surface
                vel.y = Math.min(vel.y + 8f * delta, 1.1f);
            }
            // Strong sideways drag — water kills the throw's glide fast,
            // otherwise drops skate across the surface like it's ice
            vel.x *= Math.max(0f, 1f - delta * 6.5f);
            vel.z *= Math.max(0f, 1f - delta * 6.5f);
            onGround = false;
        } else {
            vel.y = Math.max(vel.y + GRAVITY * delta, TERMINAL);
        }
        if (onGround) {
            float f = Math.max(0f, 1f - delta * 9f); // ground friction kills the pop-out slide
            vel.x *= f;
            vel.z *= f;
        }

        // Axis-by-axis movement with block collision
        float nx = pos.x + vel.x * delta;
        if (solidAt(world, nx, pos.y, pos.z)) vel.x = 0f; else pos.x = nx;

        float nz = pos.z + vel.z * delta;
        if (solidAt(world, pos.x, pos.y, nz)) vel.z = 0f; else pos.z = nz;

        float ny = pos.y + vel.y * delta;
        if (solidAt(world, pos.x, ny, pos.z)) {
            if (vel.y < 0f) {
                // Land: rest on top of the block below
                pos.y = (float) Math.floor(ny - HALF) + 1f + HALF;
                onGround = true;
            }
            vel.y = 0f;
        } else {
            pos.y = ny;
            if (vel.y != 0f) onGround = false;
        }
    }

    private static boolean solidAt(World world, float cx, float cy, float cz) {
        return solidAt(world, cx, cy, cz, HALF);
    }

    /** Does the item's box (half-extent {@code half}) at this centre touch any solid block? */
    private static boolean solidAt(World world, float cx, float cy, float cz, float half) {
        int minX = (int) Math.floor(cx - half), maxX = (int) Math.floor(cx + half);
        int minY = (int) Math.floor(cy - half), maxY = (int) Math.floor(cy + half);
        int minZ = (int) Math.floor(cz - half), maxZ = (int) Math.floor(cz + half);
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    if (world.getBlock(x, y, z).solid) return true;
        return false;
    }

    public int blockX() { return (int) Math.floor(pos.x); }
    public int blockY() { return (int) Math.floor(pos.y); }
    public int blockZ() { return (int) Math.floor(pos.z); }
}
