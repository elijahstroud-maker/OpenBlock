package com.openblock.player;

import com.openblock.audio.SoundManager;
import com.openblock.input.InputHandler;
import com.openblock.world.BlockType;
import com.openblock.world.Chunk;
import com.openblock.world.ItemDrop;
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
    /** Eyes below the water surface (drives drowning and the mining slowdown). */
    private boolean headUnderwater = false;
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

    // ---- Survival: health, air, fall damage ----
    public static final int   MAX_HEALTH = 20;   // 10 hearts, 2 HP each
    public static final float MAX_AIR    = 15f;  // seconds of breath (10 bubbles)

    private int   health = MAX_HEALTH;
    private float air    = MAX_AIR;
    /** Blocks fallen since last leaving ground / rising / touching water. */
    private float fallDistance  = 0f;
    /** Brief invulnerability window after taking a hit (Minecraft-style). */
    private float hurtCooldown  = 0f;
    private float sinceDamage   = 999f;
    private float regenTimer    = 0f;
    private float drownTimer    = 0f;
    /** 0..1 red screen flash intensity, decays after damage. Read by the HUD. */
    private float damageFlash   = 0f;
    /** Admin mode (/admin): invincible + instant block breaking. */
    private boolean adminMode   = false;
    /** Dead: frozen until the death screen respawns (or exits) the game. */
    private boolean dead        = false;
    private final Vector3f respawnPoint = new Vector3f();
    /** Set on death, popped by Game to show in chat. */
    private String deathMessage = null;

    /** What hurt the player — picks the Minecraft-style death message. */
    public enum DamageCause { FALL, DROWN, CACTUS, SUFFOCATE, GENERIC }
    private DamageCause lastCause = DamageCause.GENERIC;

    // ---- Timed block breaking ----
    private int[] breakTarget   = null;
    private float breakProgress = 0f;
    private float hitSoundTimer = 0f;

    // ---- Hotbar inventory + block placing ----
    private final Inventory inventory = new Inventory();
    /** Hold-to-place repeat interval (MC places every 4 game ticks). */
    private static final float PLACE_INTERVAL = 0.25f;
    private float placeCooldown = 0f;

    public Player(World world, InputHandler input, SoundManager sounds) {
        this.world  = world;
        this.input  = input;
        this.sounds = sounds;
        this.camera = new Camera();
        this.position = camera.getPosition(); // shared reference — we'll adjust below
    }

    public void update(float delta) {
        if (dead) return; // frozen on the death screen

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

        // ---- Survival tick: air/drowning, damage timers, slow regen ----
        boolean headUnder = inWater && !headAboveWater;
        headUnderwater = headUnder; // remembered for the mining-speed penalty
        if (headUnder) {
            air -= delta;
            if (air <= 0f) {
                air = 0f;
                drownTimer += delta;
                if (drownTimer >= 1f) { // 1 heart per second once breath runs out
                    drownTimer -= 1f;
                    damage(2, true, DamageCause.DROWN);
                }
            }
        } else {
            air = Math.min(MAX_AIR, air + delta * 4f); // breath recovers fast at the surface
            drownTimer = 0f;
        }
        if (hurtCooldown > 0f) hurtCooldown -= delta;
        sinceDamage += delta;
        damageFlash = Math.max(0f, damageFlash - delta * 2.2f);

        // Cactus contact damage: any touch — walking into, falling onto, or
        // standing on one — deals half a heart. The 0.5s hurt-immunity window
        // gives Minecraft's exact cadence: half a heart per half second.
        if (touchingCactus()) damage(1, true, DamageCause.CACTUS);
        // Suffocation: head buried in an opaque block (sand dropped on you).
        // MC's rule and rate — half a heart per half second, via the same
        // immunity window. Leaves are solid but see-through and don't count.
        BlockType headBlock = world.getBlock(pwx,
            (int) Math.floor(camera.getPosition().y), pwz);
        if (headBlock.solid && headBlock.opaque) {
            damage(1, true, DamageCause.SUFFOCATE);
        }
        // Slow natural regen (no hunger system yet): half a heart every 4s,
        // starting 8s after the last hit.
        if (health > 0 && health < MAX_HEALTH && sinceDamage > 8f) {
            regenTimer += delta;
            if (regenTimer >= 4f) { regenTimer -= 4f; health++; }
        } else {
            regenTimer = 0f;
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
        if (!collidesAtNew(newX, foot, camera.getPosition().z, foot)) {
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
        if (!collidesAtNew(camera.getPosition().x, foot, newZ, foot)) {
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

        // Fall-distance tracking: accumulates while falling through air; water
        // and upward motion both reset it (water landings are always safe).
        if (inWater || velocity.y > 0) fallDistance = 0f;
        else if (velocity.y < 0)       fallDistance += -velocity.y * delta;

        // Y
        float newEyeY = camera.getPosition().y + velocity.y * delta;
        float newFoot = newEyeY - EYE_HEIGHT;
        if (!collidesAtNew(camera.getPosition().x, newFoot, camera.getPosition().z,
                           camera.getPosition().y - EYE_HEIGHT)) {
            camera.getPosition().y = newEyeY;
            onGround = false;
        } else {
            if (velocity.y < 0) {
                onGround = true;
                // Snap to block top
                float snappedFoot = (float) Math.floor(newFoot + 1.0f);
                camera.getPosition().y = snappedFoot + EYE_HEIGHT;
                handleLanding();
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

        collectNearbyDrops();
        inventory.update(delta); // pickup-pop icon animation timers

        // Q tosses one item from the selected slot. Always consume the key edge
        // so a press while typing in chat doesn't fire later; ignore it there.
        boolean qPressed = input.isKeyJustPressed(GLFW_KEY_Q);
        if (qPressed && !input.isTextMode()) dropSelectedItem();
    }

    /** Tosses a single item from the selected slot out in front, Minecraft-style. */
    private void dropSelectedItem() {
        int slot = inventory.getSelected();
        BlockType type = inventory.getType(slot);
        if (type == null) return;
        inventory.consume(slot);
        Vector3f eye = camera.getPosition();
        Vector3f dir = camera.getFront();
        world.spawnThrownDrop(type,
            eye.x + dir.x * 0.4f,
            eye.y - 0.3f + dir.y * 0.4f, // launched from just below eye level
            eye.z + dir.z * 0.4f,
            dir.x * 5.5f + (float) (Math.random() - 0.5) * 0.6f,
            dir.y * 5.5f + 1.2f,
            dir.z * 5.5f + (float) (Math.random() - 0.5) * 0.6f);
    }

    /**
     * Picks up dropped items touching the player, Minecraft-style: the pickup
     * box is the player AABB grown by 1 block sideways and half a block
     * vertically. Taken stacks aren't deleted on the spot — the entity flies
     * to the player like a magnet (MC's pickup animation) and the World
     * removes it when it arrives. A full hotbar leaves the item on the ground.
     */
    private void collectNearbyDrops() {
        float eyeX = camera.getPosition().x, eyeZ = camera.getPosition().z;
        float foot = camera.getPosition().y - EYE_HEIGHT;
        float chestY = foot + HEIGHT * 0.45f; // where flying drops home in on
        for (ItemDrop d : world.getDrops()) {
            if (d.collecting) {
                d.collectTarget.set(eyeX, chestY, eyeZ); // track a moving player
                continue;
            }
            if (d.pickupDelay > 0f) continue;
            if (Math.abs(d.pos.x - eyeX) > HALF_W + 1.25f) continue;
            if (Math.abs(d.pos.z - eyeZ) > HALF_W + 1.25f) continue;
            if (d.pos.y < foot - 0.75f || d.pos.y > foot + HEIGHT + 0.75f) continue;
            boolean got = false;
            while (d.count > 0 && inventory.add(d.type)) {
                d.count--;
                got = true;
            }
            if (got) sounds.playPop();
            if (d.count <= 0) {
                d.collecting = true;
                d.collectTarget.set(eyeX, chestY, eyeZ);
            }
        }
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

    /**
     * Movement collision with Minecraft's overlap rule: solid cells the
     * player's CURRENT AABB already intersects don't block (collision cannot
     * resolve an existing overlap — treating them as walls would entomb a
     * player buried by falling sand). So a buried player falls to the real
     * floor, can jump, and can climb out of a one-block burial, while deeper
     * burials still trap them because they fall back before getting clear.
     * The current AABB is (position.x, curFoot, position.z).
     */
    private boolean collidesAtNew(float eyeX, float foot, float eyeZ, float curFoot) {
        float cEyeX = camera.getPosition().x, cEyeZ = camera.getPosition().z;
        int cMinX = (int) Math.floor(cEyeX - HALF_W);
        int cMaxX = (int) Math.floor(cEyeX + HALF_W);
        int cMinY = (int) Math.floor(curFoot);
        int cMaxY = (int) Math.floor(curFoot + HEIGHT - 0.001f);
        int cMinZ = (int) Math.floor(cEyeZ - HALF_W);
        int cMaxZ = (int) Math.floor(cEyeZ + HALF_W);

        int minX = (int) Math.floor(eyeX - HALF_W);
        int maxX = (int) Math.floor(eyeX + HALF_W);
        int minY = (int) Math.floor(foot);
        int maxY = (int) Math.floor(foot + HEIGHT - 0.001f);
        int minZ = (int) Math.floor(eyeZ - HALF_W);
        int maxZ = (int) Math.floor(eyeZ + HALF_W);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (!world.getBlock(bx, by, bz).solid) continue;
                    boolean alreadyInside = bx >= cMinX && bx <= cMaxX
                                         && by >= cMinY && by <= cMaxY
                                         && bz >= cMinZ && bz <= cMaxZ
                                         && world.getBlock(bx, by, bz).solid;
                    if (!alreadyInside) return true;
                }
            }
        }
        return false;
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

    // ---------- survival: damage / death ----------

    /** Fall-damage check, called the instant the player lands on solid ground. */
    private void handleLanding() {
        // Any water at the landing spot absorbs the fall completely (MC rule).
        // The per-tick inWater reset can miss a fast fall into shallow water —
        // at terminal velocity the feet cross the whole water layer in one
        // tick — so check the landing cell (and the one above, for 2-deep
        // pools) directly.
        int fx = (int) Math.floor(camera.getPosition().x);
        int fz = (int) Math.floor(camera.getPosition().z);
        int fy = (int) Math.floor(camera.getPosition().y - EYE_HEIGHT + 0.01f);
        boolean waterLanding = isWater(world.getBlock(fx, fy, fz))
                            || isWater(world.getBlock(fx, fy + 1, fz));
        if (fallDistance > 3.5f && !waterLanding) {
            int dmg = Math.round(fallDistance - 3f); // MC rule: 1 HP per block beyond 3
            if (damage(dmg, false, DamageCause.FALL)) {
                sounds.playFall(dmg >= 8); // oof + impact sound; heavy for ~4+ heart falls
            }
        }
        fallDistance = 0f;
    }

    /**
     * Applies damage (survival only). Returns true if the hit actually landed —
     * admin mode, the post-hit invulnerability window, and death all block it.
     */
    public boolean damage(int amount, boolean playHurtSound) {
        return damage(amount, playHurtSound, DamageCause.GENERIC);
    }

    public boolean damage(int amount, boolean playHurtSound, DamageCause cause) {
        if (adminMode || amount <= 0 || hurtCooldown > 0f || health <= 0) return false;
        lastCause = cause;
        health -= amount;
        hurtCooldown = 0.5f;
        sinceDamage  = 0f;
        damageFlash  = 0.65f;
        if (playHurtSound) sounds.playHurt();
        if (health <= 0) {
            health = 0;
            die();
        }
        return true;
    }

    /**
     * Marks the player dead — the death screen takes over from here and calls
     * {@link #respawn()} (or exits). No automatic teleport.
     */
    private void die() {
        dead = true;
        deathMessage = switch (lastCause) {
            case FALL      -> "Player hit the ground too hard";
            case DROWN     -> "Player drowned";
            case CACTUS    -> "Player was pricked to death";
            case SUFFOCATE -> "Player suffocated in a wall";
            case GENERIC   -> "Player died";
        };
        velocity.set(0, 0, 0);
        breakTarget   = null;
        breakProgress = 0f;
    }

    public boolean isDead() { return dead; }

    /** Respawn at the spawn point with full stats (death screen's Respawn button). */
    public void respawn() {
        dead = false;
        camera.getPosition().set(respawnPoint);
        velocity.set(0, 0, 0);
        health = MAX_HEALTH;
        air    = MAX_AIR;
        fallDistance = 0f;
        hurtCooldown = 0f;
        sinceDamage  = 999f;
        damageFlash  = 0f;
    }

    /** True while the player AABB (slightly expanded) touches any cactus block. */
    private boolean touchingCactus() {
        float eyeX  = camera.getPosition().x;
        float eyeZ  = camera.getPosition().z;
        float footY = camera.getPosition().y - EYE_HEIGHT;
        float pad   = 0.05f;
        int minX = (int) Math.floor(eyeX - HALF_W - pad);
        int maxX = (int) Math.floor(eyeX + HALF_W + pad);
        int minY = (int) Math.floor(footY - pad);
        int maxY = (int) Math.floor(footY + HEIGHT + pad - 0.001f);
        int minZ = (int) Math.floor(eyeZ - HALF_W - pad);
        int maxZ = (int) Math.floor(eyeZ + HALF_W + pad);
        for (int bx = minX; bx <= maxX; bx++)
            for (int by = minY; by <= maxY; by++)
                for (int bz = minZ; bz <= maxZ; bz++)
                    if (world.getBlock(bx, by, bz) == BlockType.CACTUS) return true;
        return false;
    }

    public void setRespawn(float x, float y, float z) {
        respawnPoint.set(x, y, z);
    }

    /** Toggles admin mode (invincible + instant breaking). Returns the new state. */
    public boolean toggleAdmin() {
        adminMode = !adminMode;
        if (adminMode) {
            health = MAX_HEALTH;
            air    = MAX_AIR;
        }
        return adminMode;
    }

    public boolean isAdminMode()   { return adminMode; }
    public int     getHealth()     { return health; }
    public float   getAir()        { return air; }
    public float   getDamageFlash(){ return damageFlash; }

    /** Death message set on death; returns it once and clears (Game feeds it to chat). */
    public String popDeathMessage() {
        String m = deathMessage;
        deathMessage = null;
        return m;
    }

    // ---------- timed block breaking ----------

    /**
     * Seconds to break a block bare-handed — real Minecraft values:
     * time = hardness x 1.5 (harvestable by hand) or x 5 (needs a tool).
     * When tools arrive they should act as speed multipliers on these bases.
     */
    private static float breakTime(BlockType b) {
        return switch (b) {
            case LEAVES               -> 0.30f; // hardness 0.2
            case CACTUS               -> 0.60f; // hardness 0.4
            case DIRT, SAND           -> 0.75f; // hardness 0.5
            case GRASS, SNOW_GRASS,
                 GRAVEL               -> 0.90f; // hardness 0.6
            case LOG                  -> 3.00f; // hardness 2.0, hand-harvestable
            case STONE                -> 7.50f; // hardness 1.5 x5 — needs a pickaxe
            case COBBLESTONE          -> 10.0f; // hardness 2.0 x5 — needs a pickaxe
            default                   -> 1.00f;
        };
    }

    /**
     * Hold-to-mine. Called every tick with whether the mouse button is held and
     * whether it was pressed this tick. Progress resets when the aim moves to a
     * different block. Admin mode breaks instantly, but only one block per
     * click (like pre-survival breaking) — not a continuous sweep.
     */
    public void updateBreaking(float delta, boolean holding, boolean justClicked) {
        if (adminMode) {
            breakTarget = null;
            breakProgress = 0f;
            if (justClicked) {
                int[] t = getTargetBlock();
                if (t != null) {
                    breakTarget = t;
                    breakNow(world.getBlock(t[0], t[1], t[2]));
                }
            }
            return;
        }

        if (!holding) {
            breakTarget = null;
            breakProgress = 0f;
            return;
        }
        int[] t = getTargetBlock();
        if (t == null) {
            breakTarget = null;
            breakProgress = 0f;
            return;
        }
        BlockType block = world.getBlock(t[0], t[1], t[2]);
        if (block == BlockType.BEDROCK) {
            // Unbreakable in survival, but punchable forever: hit sounds and
            // chips keep coming while progress stays pinned at zero.
            breakTarget = null;
            breakProgress = 0f;
            hitSoundTimer -= delta;
            if (hitSoundTimer <= 0f) {
                sounds.playHit(block);
                spawnHitChips(t, block);
                hitSoundTimer = 0.25f;
            }
            return;
        }

        if (breakTarget == null
                || t[0] != breakTarget[0] || t[1] != breakTarget[1] || t[2] != breakTarget[2]) {
            breakTarget = t;
            breakProgress = 0f;
            hitSoundTimer = 0f;
        }

        // Minecraft mining penalties: 5x slower with your head underwater (no
        // Aqua Affinity), 5x slower with your feet off the ground — so treading
        // water while fully submerged stacks to 25x, but standing on the bottom
        // of a pool is only 5x.
        float penalty = 1f;
        if (headUnderwater) penalty *= 5f;
        if (!onGround)      penalty *= 5f;
        breakProgress += delta / (breakTime(block) * penalty);
        hitSoundTimer -= delta;
        if (hitSoundTimer <= 0f) {
            sounds.playHit(block);
            spawnHitChips(t, block);
            hitSoundTimer = 0.25f;
        }
        if (breakProgress >= 1f) breakNow(block);
    }

    /**
     * A few crack chips popping off the face being struck (MC's mining
     * particles). The struck face is the one adjacent to the cell the view
     * ray was in just before the block — same cell placing would target.
     * Leaves chip nothing: bits of leaf flying off just looks wrong.
     */
    private void spawnHitChips(int[] t, BlockType block) {
        if (block == BlockType.LEAVES) return;
        int fx = 0, fy = 1, fz = 0; // fallback: chips off the top
        int[] cell = getPlacementCell();
        if (cell != null) {
            int dx = cell[0] - t[0], dy = cell[1] - t[1], dz = cell[2] - t[2];
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
                fx = dx; fy = dy; fz = dz;
            }
        }
        world.addBlockParticles(block, t[0], t[1], t[2], fx, fy, fz);
    }

    private void breakNow(BlockType block) {
        sounds.playBreak(block);
        world.setBlock(breakTarget[0], breakTarget[1], breakTarget[2], BlockType.AIR);
        if (!adminMode) {
            BlockType drop = dropFor(block);
            if (drop != null) {
                world.spawnDrop(drop, breakTarget[0], breakTarget[1], breakTarget[2]);
            }
        }
        breakTarget   = null;
        breakProgress = 0f;
    }

    /**
     * What a broken block drops (survival only; admin drops nothing).
     * Minecraft rules: grass drops dirt, stone drops cobblestone (hand-mineable
     * for now — no tools yet), leaves drop nothing (no saplings yet).
     */
    private static BlockType dropFor(BlockType block) {
        return switch (block) {
            case GRASS, SNOW_GRASS -> BlockType.DIRT;
            case STONE             -> BlockType.COBBLESTONE;
            case LEAVES            -> null;
            default                -> block;
        };
    }

    /** Block currently being mined (null when not mining). */
    public int[] getBreakTarget()   { return breakTarget; }
    /** Mining progress 0..1 for the crack overlay. */
    public float getBreakProgress() { return breakProgress; }

    // ---------- block placing ----------

    /**
     * Right-click block placing from the selected hotbar slot. Places against
     * the targeted block face; holding the button repeats every 0.25s (MC's
     * 4-tick repeat). Admin mode doesn't consume the block.
     */
    public void updatePlacing(float delta, boolean holding, boolean justClicked) {
        placeCooldown -= delta;
        if (!holding) {
            placeCooldown = 0f; // next click places instantly
            return;
        }
        if (!justClicked && placeCooldown > 0f) return;

        BlockType type = inventory.getType(inventory.getSelected());
        if (type == null) return;

        int[] cell = getPlacementCell();
        if (cell == null) return;
        BlockType existing = world.getBlock(cell[0], cell[1], cell[2]);
        if (existing.solid) return;               // only air/water are replaceable
        if (cellIntersectsPlayer(cell[0], cell[1], cell[2])) return;

        world.setBlock(cell[0], cell[1], cell[2], type);
        if (!adminMode) inventory.consume(inventory.getSelected());
        sounds.playBreak(type); // MC's place sound is the block's dig sound
        placeCooldown = PLACE_INTERVAL;
    }

    /**
     * The open cell the ray was in just before entering the targeted block —
     * i.e. the cell against the face being looked at. Null when nothing is in
     * reach or the ray starts inside a block.
     */
    private int[] getPlacementCell() {
        Vector3f eye = camera.getPosition();
        Vector3f dir = camera.getFront();
        int px = (int) Math.floor(eye.x);
        int py = (int) Math.floor(eye.y);
        int pz = (int) Math.floor(eye.z);
        int steps = (int) (REACH / RAY_STEP);
        for (int i = 1; i <= steps; i++) {
            float t = i * RAY_STEP;
            int bx = (int) Math.floor(eye.x + dir.x * t);
            int by = (int) Math.floor(eye.y + dir.y * t);
            int bz = (int) Math.floor(eye.z + dir.z * t);
            if (bx == px && by == py && bz == pz) continue;
            // (placing into the player's own cells is rejected by cellIntersectsPlayer)
            if (world.getBlock(bx, by, bz).solid) return new int[]{px, py, pz};
            px = bx; py = by; pz = bz;
        }
        return null;
    }

    /** Would a block in this cell overlap the player's AABB? */
    private boolean cellIntersectsPlayer(int bx, int by, int bz) {
        float eyeX = camera.getPosition().x, eyeZ = camera.getPosition().z;
        float foot = camera.getPosition().y - EYE_HEIGHT;
        return bx + 1 > eyeX - HALF_W && bx < eyeX + HALF_W
            && by + 1 > foot          && by < foot + HEIGHT
            && bz + 1 > eyeZ - HALF_W && bz < eyeZ + HALF_W;
    }

    public Inventory getInventory() { return inventory; }

    public Camera getCamera() { return camera; }

    /** Player's foot height in world blocks (used for the rain→snow altitude switch). */
    public float getFootY() { return camera.getPosition().y - EYE_HEIGHT; }

    public int getChunkX() {
        return Math.floorDiv((int) Math.floor(camera.getPosition().x), Chunk.SIZE_X);
    }

    public int getChunkZ() {
        return Math.floorDiv((int) Math.floor(camera.getPosition().z), Chunk.SIZE_Z);
    }
}
