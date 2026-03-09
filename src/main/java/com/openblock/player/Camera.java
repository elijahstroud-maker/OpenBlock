package com.openblock.player;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Camera {
    private final Vector3f position = new Vector3f(0, 80, 0);
    private float yaw   = -90.0f; // look along -Z initially
    private float pitch =   0.0f;

    private final Vector3f front = new Vector3f(0, 0, -1);
    private final Vector3f right = new Vector3f(1, 0,  0);
    private final Vector3f up    = new Vector3f(0, 1,  0);
    private static final Vector3f WORLD_UP = new Vector3f(0, 1, 0);

    private final Matrix4f viewMatrix = new Matrix4f();
    private final Vector3f tmpTarget  = new Vector3f();

    public Camera() {
        updateVectors();
    }

    public void rotate(float dyaw, float dpitch) {
        yaw   += dyaw;
        pitch += dpitch;
        pitch  = Math.max(-89.0f, Math.min(89.0f, pitch));
        updateVectors();
    }

    private void updateVectors() {
        double yawRad   = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        front.x = (float)(Math.cos(yawRad) * Math.cos(pitchRad));
        front.y = (float)(Math.sin(pitchRad));
        front.z = (float)(Math.sin(yawRad) * Math.cos(pitchRad));
        front.normalize();

        front.cross(WORLD_UP, right).normalize();
        right.cross(front, up).normalize();
    }

    public Matrix4f getViewMatrix() {
        position.add(front, tmpTarget);
        return viewMatrix.identity().lookAt(position, tmpTarget, up);
    }

    public Vector3f getPosition() { return position; }
    public Vector3f getFront()    { return front; }
    public Vector3f getRight()    { return right; }
    public Vector3f getUp()       { return up; }
    public float getYaw()         { return yaw; }
    public float getPitch()       { return pitch; }
}
