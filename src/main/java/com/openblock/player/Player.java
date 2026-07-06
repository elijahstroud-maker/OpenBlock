package com.openblock.player;

import com.openblock.audio.SoundManager;
import com.openblock.input.InputHandler;
import com.openblock.world.BlockType;
import com.openblock.world.Chunk;
import com.openblock.world.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

public class Player {
    private static final float SPEED         = 4.3f;
    private static final float JUMP_VELOCITY = 8.0f;
    private static final float GRAVITY       = -25.0f;
    private static final float TERMINAL_VEL  = -50.0f;
    private static final float MOUSE_SENS    = 0.12f;

    // Water physics
    private static final float GRAVITY_WATER  = -4.0f;
    private static final float TERMINAL_WATER = -3.0f;
    private static final float SWIM_SPEED     =  2.5f;   // max upward speed while submerged
    private static final float SWIM_ACCEL     =  8.0f;   // acceleration toward SWIM_SPEED
    private static final float SINK_SPEED     = -4.0f;
    private static final float SPEED_WATER    = SPEED * 0.55f;
    // Swim view-bob: amplitude scales with horizontal speed (near-flat when still).
    private static final float WATER_BOB_AMOUNT = 0.055f; // max eye dip, blocks
    private static final float WATER_BOB_FREQ   = 2.4f;   // phase advance per block moved

    // AABB half-extents
    private static final float HALF_W = 0.3f;
    private static final float HEIGHT = 1.8f;
    // Eye offset from foot position
    private static final float EYE_HEIGHT = 1.62f;


    private final Camera camera;
    private final World world;
    private final InputHandler input;
    private final SoundManager sounds;

    private final Vector3f velocity = new Vector3f();
    /** Foot position (bottom of player AABB). */
    private final Vector3f position;
    private boolean onGround    = false;
    private boolean inWater     = false;
    private boolean wasInWater  = false;
    /** Tracks downward speed just before entering water, to choose splash weight. */
    private float   preEntryVelY  = 0f;
    /** Prevents splash spam when rapidly exiting and re-entering water. */
    private float   splashCooldown = 0f;
    private float bobTimer   = 0f;
    /** Swim view-bob phase + eased amplitude (render-only camera offset). */
    private float bobPhase = 0f;
    private float bobAmp   = 0f;
    private int lastStepX = Integer.MIN_VALUE;
    private int lastStepZ = Integer.MIN_VALUE;
    private float stepCooldown = 0f;

    private final Vector3f tmpMove  = new Vector3f();
    private final Vector3f tmpFwd   = new Vector3f();
    private final Vector3f tmpRight = new Vector3f();

    public Player(World world, InputHandler input, SoundManager sounds) {
        this.world  = world;
        this.input  = input;
        this.sounds = sounds;
        this.camera = new Camera();
        this.position = camera.getPosition(); // shared reference — we'll adjust below
    }

    public void update(float delta) {
        // Mouse look
        camera.rotate(input.mouseDX * MOUSE_SENS, -input.mouseDY * MOUSE_SENS);
        input.resetMouseDelta();

        // Detect water: player is in water if the block at waist height is water
        float foot = camera.getPosition().y - EYE_HEIGHT;
        int pwx = (int) Math.floor(camera.getPosition().x);
        int pwz = (int) Math.floor(camera.getPosition().z);
        wasInWater = inWater;
        if (!wasInWater) preEntryVelY = velocity.y; // record speed just before possible entry
        BlockType waistBlock = world.getBlock(pwx, (int) Math.floor(foot + 0.6f), pwz);
        inWater = isWater(waistBlock);
        if (splashCooldown > 0) splashCooldown -= delta;

        // Splash on entering any water; flowing water plays at lower volume than source water.
        // Heavy splash requires a genuine high fall (> ~3 blocks); cooldown prevents spam on exit/re-entry.
        if (inWater && !wasInWater && splashCooldown <= 0) {
            sounds.playSplash(preEntryVelY < -12f, waistBlock == BlockType.WATER_FLOWING);
            splashCooldown = 1.2f;
        }

        // Ambient water sound: full volume in water, fades with 3D distance to nearest flowing water
        float waterGain;
        if (inWater) {
            waterGain = 1.0f;
        } else {
            float px = camera.getPosition().x, py = camera.getPosition().y, pz = camera.getPosition().z;
            int fy = (int) Math.floor(foot);
            float minDist2 = Float.MAX_VALUE;
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        if (world.getBlock(pwx + dx, fy + dy, pwz + dz) == BlockType.WATER_FLOWING) {
                            float wx = pwx + dx + 0.5f - px;
                            float wy = fy  + dy + 0.5f - py;
                            float wz = pwz + dz + 0.5f - pz;
                            float d2 = wx*wx + wy*wy + wz*wz;
                            if (d2 < minDist2) minDist2 = d2;
                        }
                    }
                }
            }
            float maxDist = 5.0f;
            waterGain = minDist2 < maxDist * maxDist
                ? (float) Math.sqrt(1.0f - minDist2 / (maxDist * maxDist))
                : 0.0f;
        }
        sounds.updateWaterAmbient(waterGain);
        // Head above water: eye block is not water — player can hop out
        boolean headAboveWater = inWater &&
            !isWater(world.getBlock(pwx, (int) Math.floor(camera.getPosition().y), pwz));

        // Local water surface (top plane of the highest water block in this column).
        // Used by the surface bob so it works at ANY elevation — ponds, rivers,
        // flooded caves — not just sea level.
        float waterSurfaceY = 0f;
        if (inWater) {
            int sy = (int) Math.floor(foot + 0.6f);
            int guard = 0;
            while (guard++ < 32 && isWater(world.getBlock(pwx, sy + 1, pwz))) sy++;
            waterSurfaceY = sy + 1;
        }

        // Horizontal movement (no Y component from camera direction)
        tmpFwd.set(camera.getFront().x, 0, camera.getFront().z);
        if (tmpFwd.lengthSquared() > 0.0001f) tmpFwd.normalize();
        tmpRight.set(camera.getRight().x, 0, camera.getRight().z);
        if (tmpRight.lengthSquared() > 0.0001f) tmpRight.normalize();

        tmpMove.set(0, 0, 0);
        if (input.isKeyDown(GLFW_KEY_W)) tmpMove.add(tmpFwd);
        if (input.isKeyDown(GLFW_KEY_S)) tmpMove.sub(tmpFwd);
        if (input.isKeyDown(GLFW_KEY_D)) tmpMove.add(tmpRight);
        if (input.isKeyDown(GLFW_KEY_A)) tmpMove.sub(tmpRight);
        boolean movingHorizontally = tmpMove.lengthSquared() > 0;
        if (movingHorizontally) tmpMove.normalize();
        tmpMove.mul(inWater ? SPEED_WATER : SPEED);
        velocity.x = tmpMove.x;
        velocity.z = tmpMove.z;

        // Jump (only on solid ground, not in water — wading uses the swim-out
        // assist instead, otherwise holding space hop-spams in the shallows)
        if (input.isKeyDown(GLFW_KEY_SPACE) && onGround && !inWater) {
            velocity.y = JUMP_VELOCITY;
            onGround = false;
        }

        // Vertical physics — water vs normal
        if (inWater) {
            if (input.isKeyDown(GLFW_KEY_SPACE)) {
                if (headAboveWater) {
                    if (velocity.y > 1.4f) {
                        // Still carrying swim momentum — let water gravity decelerate naturally.
                        // Threshold 1.4 → rises ~0.54 blocks above surface before bobbing starts.
                        bobTimer = 1.3f / 4.0f; // prime at sine peak so bob starts at max, no zero-velocity pause
                        velocity.y += GRAVITY_WATER * delta;
                    } else {
                        // Near apex — sine bob from current (elevated) position.
                        bobTimer += delta;
                        if (bobTimer > 1.3f) bobTimer -= 1.3f;
                        velocity.y = 0.8f * (float) Math.sin(bobTimer * (float) Math.PI * 2.0f / 1.3f);
                        // Never bob the eye below just-above-the-surface (works at any elevation)
                        if (velocity.y < 0 && camera.getPosition().y < waterSurfaceY + 0.35f) velocity.y = 0;
                    }
                } else {
                    bobTimer = 0;
                    velocity.y = Math.min(velocity.y + SWIM_ACCEL * delta, SWIM_SPEED);
                }
            } else if (input.isKeyDown(GLFW_KEY_LEFT_SHIFT)) {
                bobTimer = 0;
                velocity.y = SINK_SPEED;
            } else {
                bobTimer = 0;
                velocity.y += GRAVITY_WATER * delta;
                velocity.y = Math.max(velocity.y, TERMINAL_WATER);
            }
        } else if (!onGround) {
            velocity.y += GRAVITY * delta;
            velocity.y = Math.max(velocity.y, TERMINAL_VEL);
        }

        // Separate-axis collision
        foot = camera.getPosition().y - EYE_HEIGHT;

        // X
        float newX = camera.getPosition().x + velocity.x * delta;
        if (!collidesAt(newX, foot, camera.getPosition().z)) {
            camera.getPosition().x = newX;
        } else if (onGround && !collidesAt(newX, foot + 0.5f, camera.getPosition().z)) {
            // Ground step-up (0.5 block — stairs/slabs): instant snap, small enough to be invisible
            camera.getPosition().x = newX;
            camera.getPosition().y = (float) Math.floor(foot) + 1.0f + EYE_HEIGHT;
        } else if (headAboveWater && input.isKeyDown(GLFW_KEY_SPACE)) {
            // Swim-out assist (explicit: space + pushing into the bank). Finds the
            // lowest standable ledge up to 2.3 blocks above the feet and applies
            // exactly the velocity needed to land on it — beaches get a gentle
            // hop, 1-above-surface river banks a strong one.
            float target = climbTarget(newX, foot, camera.getPosition().z);
            if (!Float.isNaN(target)) {
                float v = (float) Math.sqrt(2f * -GRAVITY * (target - foot + 0.35f));
                if (velocity.y < v) velocity.y = v;
            } else {
                velocity.x = 0;
            }
        } else {
            velocity.x = 0;
        }

        // Z
        float newZ = camera.getPosition().z + velocity.z * delta;
        if (!collidesAt(camera.getPosition().x, foot, newZ)) {
            camera.getPosition().z = newZ;
        } else if (onGround && !collidesAt(camera.getPosition().x, foot + 0.5f, newZ)) {
            camera.getPosition().z = newZ;
            camera.getPosition().y = (float) Math.floor(foot) + 1.0f + EYE_HEIGHT;
        } else if (headAboveWater && input.isKeyDown(GLFW_KEY_SPACE)) {
            float target = climbTarget(camera.getPosition().x, foot, newZ);
            if (!Float.isNaN(target)) {
                float v = (float) Math.sqrt(2f * -GRAVITY * (target - foot + 0.35f));
                if (velocity.y < v) velocity.y = v;
            } else {
                velocity.z = 0;
            }
        } else {
            velocity.z = 0;
        }

        // Y
        float newEyeY = camera.getPosition().y + velocity.y * delta;
        float newFoot = newEyeY - EYE_HEIGHT;
        if (!collidesAt(camera.getPosition().x, newFoot, camera.getPosition().z)) {
            camera.getPosition().y = newEyeY;
            onGround = false;
        } else {
            if (velocity.y < 0) {
                onGround = true;
                // Snap to block top
                float snappedFoot = (float) Math.floor(newFoot + 1.0f);
                camera.getPosition().y = snappedFoot + EYE_HEIGHT;
            }
            velocity.y = 0;
        }

        // If we think we're on the ground, verify there's actually a block below us.
        // This catches the case of walking off a ledge with zero vertical velocity.
        if (onGround) {
            float groundFoot = camera.getPosition().y - EYE_HEIGHT;
            if (!collidesAt(camera.getPosition().x, groundFoot - 0.05f, camera.getPosition().z)) {
                onGround = false;
            }
        }

        // Step sounds — trigger on new block column, but no faster than every 0.35s
        if (stepCooldown > 0) stepCooldown -= delta;
        if (onGround) {
            int bx = (int) Math.floor(camera.getPosition().x);
            int bz = (int) Math.floor(camera.getPosition().z);
            if ((bx != lastStepX || bz != lastStepZ) && stepCooldown <= 0) {
                lastStepX = bx;
                lastStepZ = bz;
                stepCooldown = 0.35f;
                float stepFoot = camera.getPosition().y - EYE_HEIGHT;
                BlockType below = world.getBlock(bx, (int) Math.floor(stepFoot) - 1, bz);
                if (below.solid) sounds.playStep(below);
            }
        }

        // Swim view-bob: bob amplitude scales with horizontal speed, so drifting is
        // near-flat and active swimming gently bobs the camera. Eased so it fades in
        // and out instead of snapping. Render-only via Camera.setBobOffset.
        float horizSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float targetAmp  = inWater ? Math.min(horizSpeed / SPEED_WATER, 1f) * WATER_BOB_AMOUNT : 0f;
        bobAmp  += (targetAmp - bobAmp) * Math.min(1f, delta * 6f);
        bobPhase += horizSpeed * delta * WATER_BOB_FREQ;
        camera.setBobOffset((float) Math.sin(bobPhase) * bobAmp);
    }

    /**
     * Returns true if the player AABB centered at (eyeX, foot, eyeZ)
     * (foot = bottom of player) intersects any solid block.
     */
    private static boolean isWater(BlockType b) {
        return b == BlockType.WATER || b == BlockType.WATER_FLOWING;
    }

    /**
     * Lowest standable foot height on the blocked side for the swim-out assist:
     * tries floor(foot)+1..+3 and returns the first with body clearance that is
     * NOT still underwater, capped at a 2.3-block climb (covers banks 1 above
     * the water surface). Skipping submerged ledges matters on terraced shores —
     * boosting onto an underwater step just lands you back in the water and the
     * assist re-fires, which reads as stutter-jumping on the block below the
     * shore. NaN if no valid ledge.
     */
    private float climbTarget(float eyeX, float foot, float eyeZ) {
        float base = (float) Math.floor(foot);
        for (int k = 1; k <= 3; k++) {
            float target = base + k;
            if (target - foot > 2.3f) break;
            if (collidesAt(eyeX, target, eyeZ)) continue;
            if (isWater(world.getBlock((int) Math.floor(eyeX),
                                       (int) Math.floor(target + 0.05f),
                                       (int) Math.floor(eyeZ)))) continue;
            return target;
        }
        return Float.NaN;
    }

    private boolean collidesAt(float eyeX, float foot, float eyeZ) {
        int minX = (int) Math.floor(eyeX - HALF_W);
        int maxX = (int) Math.floor(eyeX + HALF_W);
        int minY = (int) Math.floor(foot);
        int maxY = (int) Math.floor(foot + HEIGHT - 0.001f);
        int minZ = (int) Math.floor(eyeZ - HALF_W);
        int maxZ = (int) Math.floor(eyeZ + HALF_W);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (world.getBlock(bx, by, bz).solid) return true;
                }
            }
        }
        return false;
    }

    private static final float REACH    = 5.0f;
    private static final float RAY_STEP = 0.05f;

    /**
     * Returns the world [x, y, z] of the first solid block within reach,
     * or null if nothing is targeted.
     */
    public int[] getTargetBlock() {
        Vector3f eye = camera.getPosition();
        Vector3f dir = camera.getFront();
        int steps = (int)(REACH / RAY_STEP);
        for (int i = 1; i <= steps; i++) {
            float t = i * RAY_STEP;
            int bx = (int) Math.floor(eye.x + dir.x * t);
            int by = (int) Math.floor(eye.y + dir.y * t);
            int bz = (int) Math.floor(eye.z + dir.z * t);
            if (world.getBlock(bx, by, bz).solid) return new int[]{bx, by, bz};
        }
        return null;
    }

    /** Breaks the targeted block (instant, except bedrock is indestructible). */
    public void tryBreakBlock() {
        int[] target = getTargetBlock();
        if (target == null) return;
        BlockType block = world.getBlock(target[0], target[1], target[2]);
        if (block != BlockType.BEDROCK) {
            sounds.playBreak(block);
            world.setBlock(target[0], target[1], target[2], BlockType.AIR);
        }
    }

    public Camera getCamera() { return camera; }

    public int getChunkX() {
        return Math.floorDiv((int) Math.floor(camera.getPosition().x), Chunk.SIZE_X);
    }

    public int getChunkZ() {
        return Math.floorDiv((int) Math.floor(camera.getPosition().z), Chunk.SIZE_Z);
    }
}
