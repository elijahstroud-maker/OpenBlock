package com.openblock.player;

import com.openblock.input.InputHandler;
import com.openblock.world.Chunk;
import com.openblock.world.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

public class Player {
    private static final float SPEED         = 5.0f;
    private static final float JUMP_VELOCITY = 8.0f;
    private static final float GRAVITY       = -25.0f;
    private static final float TERMINAL_VEL  = -50.0f;
    private static final float MOUSE_SENS    = 0.12f;

    // AABB half-extents
    private static final float HALF_W = 0.3f;
    private static final float HEIGHT = 1.8f;
    // Eye offset from foot position
    private static final float EYE_HEIGHT = 1.62f;

    private final Camera camera;
    private final World world;
    private final InputHandler input;

    private final Vector3f velocity = new Vector3f();
    /** Foot position (bottom of player AABB). */
    private final Vector3f position;
    private boolean onGround = false;

    private final Vector3f tmpMove  = new Vector3f();
    private final Vector3f tmpFwd   = new Vector3f();
    private final Vector3f tmpRight = new Vector3f();

    public Player(World world, InputHandler input) {
        this.world  = world;
        this.input  = input;
        this.camera = new Camera();
        this.position = camera.getPosition(); // shared reference — we'll adjust below
    }

    public void update(float delta) {
        // Mouse look
        camera.rotate(input.mouseDX * MOUSE_SENS, -input.mouseDY * MOUSE_SENS);
        input.resetMouseDelta();

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
        if (tmpMove.lengthSquared() > 0) tmpMove.normalize();
        tmpMove.mul(SPEED);
        velocity.x = tmpMove.x;
        velocity.z = tmpMove.z;

        // Jump
        if (input.isKeyDown(GLFW_KEY_SPACE) && onGround) {
            velocity.y = JUMP_VELOCITY;
            onGround = false;
        }

        // Gravity
        if (!onGround) {
            velocity.y += GRAVITY * delta;
            velocity.y = Math.max(velocity.y, TERMINAL_VEL);
        }

        // Separate-axis collision
        float foot = camera.getPosition().y - EYE_HEIGHT;

        // X
        float newX = camera.getPosition().x + velocity.x * delta;
        if (!collidesAt(newX, foot, camera.getPosition().z)) {
            camera.getPosition().x = newX;
        } else {
            velocity.x = 0;
        }

        // Z
        float newZ = camera.getPosition().z + velocity.z * delta;
        if (!collidesAt(camera.getPosition().x, foot, newZ)) {
            camera.getPosition().z = newZ;
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
    }

    /**
     * Returns true if the player AABB centered at (eyeX, foot, eyeZ)
     * (foot = bottom of player) intersects any solid block.
     */
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

    public Camera getCamera() { return camera; }

    public int getChunkX() {
        return Math.floorDiv((int) Math.floor(camera.getPosition().x), Chunk.SIZE_X);
    }

    public int getChunkZ() {
        return Math.floorDiv((int) Math.floor(camera.getPosition().z), Chunk.SIZE_Z);
    }
}
