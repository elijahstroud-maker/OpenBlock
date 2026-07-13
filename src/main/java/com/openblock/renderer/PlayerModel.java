package com.openblock.renderer;

import org.joml.Matrix4f;

import java.util.ArrayList;

/**
 * The player rig: six independently posable parts (head+hat, body, arms,
 * legs) with Minecraft's exact ModelBiped pivot points, each mesh built in
 * pivot-local coordinates so rotations hinge like real joints. Model units
 * are skin pixels, y-up, feet at y=0, facing +Z.
 *
 * Pivots (converted from ModelBiped's y-down rotation points):
 *   head (0,24,0) · body (0,24,0 — hangs from the neck, MC's sneak-lean
 *   hinge) · arms (±5,22,0) · legs (±2,12,0).
 *
 * A {@link Pose} holds applied rotations in radians plus the sneak flag;
 * the caller (world renderer / inventory viewport) fills it from MC's
 * setRotationAngles formulas.
 */
class PlayerModel {
    static final float NECK_Y = 24f;
    static final float ARM_X  = 5f,   ARM_Y = 22f;
    /** MC's exact leg pivots: ±1.9, the legs overlap 0.1px at the center. */
    static final float LEG_X  = 1.9f, LEG_Y = 12f;
    /** MC renders the player at 0.9375 model scale → 1.875 blocks tall. */
    static final float RENDER_SCALE = 1.875f / 32f;
    /** MC's sneak render drop: 0.125 blocks, in skin-pixel model units. */
    static final float SNEAK_DROP_PX = 0.125f / RENDER_SCALE;

    /** Applied part rotations (radians) + sneak state for one frame. */
    static class Pose {
        float headRotY, headRotX;
        float bodyTwistY;              // punch torso twist (arms ride along)
        float bodyLeanX;               // sneak forward lean
        float rArmX, rArmY, rArmZ;
        float lArmX, lArmY, lArmZ;
        float rLegX, lLegX;
        boolean sneak;
    }

    private final Mesh head, body, rightArm, leftArm, rightLeg, leftLeg;
    private final Matrix4f group = new Matrix4f();
    private final Matrix4f part  = new Matrix4f();

    PlayerModel() {
        head     = buildHead();
        body     = box(-4, -12, -2, 4,  0, 2, 16, 16, 8, 12, 4);
        rightArm = box(-3, -10, -2, 1,  2, 2, 40, 16, 4, 12, 4);
        leftArm  = box(-1, -10, -2, 3,  2, 2, 32, 48, 4, 12, 4);
        rightLeg = box(-2, -12, -2, 2,  0, 2,  0, 16, 4, 12, 4);
        leftLeg  = box(-2, -12, -2, 2,  0, 2, 16, 48, 4, 12, 4);
    }

    /**
     * Draws all six parts. The shader must already be in use with
     * projection/view/ambient set and the skin texture bound; this only sets
     * uModel per part. `base` places the whole model (world position, facing
     * yaw, scale) — the sneak drop is applied in here, not by the caller.
     */
    void render(ShaderProgram shader, Matrix4f base, Pose p) {
        if (p.sneak) base = new Matrix4f(base).translate(0f, -SNEAK_DROP_PX, 0f);

        // Torso group: the punch twist swings the shoulders with the body
        group.set(base).rotateY(p.bodyTwistY);

        draw(shader, body, part.set(group)
            .translate(0f, NECK_Y, 0f).rotateX(p.bodyLeanX));
        // Head is independent of twist/lean; MC drops its pivot 1px sneaking
        draw(shader, head, part.set(base)
            .translate(0f, p.sneak ? NECK_Y - 1f : NECK_Y, 0f)
            .rotateY(p.headRotY).rotateX(p.headRotX));
        draw(shader, rightArm, part.set(group)
            .translate(-ARM_X, ARM_Y, 0f)
            .rotateZ(p.rArmZ).rotateY(p.rArmY).rotateX(p.rArmX));
        draw(shader, leftArm, part.set(group)
            .translate(ARM_X, ARM_Y, 0f)
            .rotateZ(p.lArmZ).rotateY(p.lArmY).rotateX(p.lArmX));
        // Sneak reseats the hips up and back (MC's rotationPoint shift)
        float legY = p.sneak ? 15f : LEG_Y;
        float legZ = p.sneak ? -4f : 0f;
        draw(shader, rightLeg, part.set(base)
            .translate(-LEG_X, legY, legZ).rotateX(p.rLegX));
        draw(shader, leftLeg, part.set(base)
            .translate(LEG_X, legY, legZ).rotateX(p.lLegX));
    }

    private void draw(ShaderProgram shader, Mesh mesh, Matrix4f model) {
        shader.setUniform("uModel", model);
        mesh.render();
    }

    /**
     * The right arm's full joint transform for this pose — anything written
     * into `dest` after it (translate/rotate) happens in hand-local space,
     * which is how the held block rides the swing.
     */
    Matrix4f rightArmMatrix(Matrix4f base, Pose p, Matrix4f dest) {
        dest.set(base);
        if (p.sneak) dest.translate(0f, -SNEAK_DROP_PX, 0f);
        return dest.rotateY(p.bodyTwistY)
                   .translate(-ARM_X, ARM_Y, 0f)
                   .rotateZ(p.rArmZ).rotateY(p.rArmY).rotateX(p.rArmX);
    }

    private static Mesh buildHead() {
        ArrayList<Float> verts = new ArrayList<>();
        ArrayList<Integer> idxs = new ArrayList<>();
        int[] vi = {0};
        PlayerSkin.appendBox(verts, idxs, vi, -4, 0, -4, 4, 8, 4, 0, 0, 8, 8, 8, false);
        PlayerSkin.appendBox(verts, idxs, vi, // hat overlay: MC inflates it 0.5px per side
            -4.5f, -0.5f, -4.5f, 4.5f, 8.5f, 4.5f, 32, 0, 8, 8, 8, false);
        return toMesh(verts, idxs);
    }

    private static Mesh box(float x0, float y0, float z0, float x1, float y1, float z1,
                            int tu, int tv, int w, int h, int d) {
        ArrayList<Float> verts = new ArrayList<>();
        ArrayList<Integer> idxs = new ArrayList<>();
        int[] vi = {0};
        PlayerSkin.appendBox(verts, idxs, vi, x0, y0, z0, x1, y1, z1, tu, tv, w, h, d, false);
        return toMesh(verts, idxs);
    }

    private static Mesh toMesh(ArrayList<Float> verts, ArrayList<Integer> idxs) {
        float[] va = new float[verts.size()];
        for (int i = 0; i < va.length; i++) va[i] = verts.get(i);
        int[] ia = new int[idxs.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = idxs.get(i);
        Mesh m = new Mesh();
        m.upload(va, ia);
        return m;
    }

    void cleanup() {
        head.cleanup();
        body.cleanup();
        rightArm.cleanup();
        leftArm.cleanup();
        rightLeg.cleanup();
        leftLeg.cleanup();
    }
}
