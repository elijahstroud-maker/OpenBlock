package com.openblock.renderer;

import com.openblock.player.Player;
import com.openblock.world.BlockType;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * The first-person hand at the bottom-right of the screen, Minecraft-style:
 * the held block as a mini cube when the selected hotbar slot has one, a bare
 * blocky arm otherwise (flat skin color for now — a proper skin texture can
 * replace the 1x1 texel later without touching the geometry).
 *
 * Animations, all matching MC's feel:
 *  - swing arc on punching / mining / placing (driven by Player.swingProgress)
 *  - walk bob while moving on the ground
 *  - equip dip: switching hotbar slots drops the hand off-screen and raises
 *    it again holding the new item
 *
 * Drawn in camera space (identity view) after the world, with the depth
 * buffer cleared first so the hand never clips into nearby walls.
 */
public class HandRenderer {
    private static final float EQUIP_SPEED = 5.0f;  // full raise/drop in 0.2s
    /** Bob cycles per block walked (×π). MC's view bob runs on
     *  distanceWalkedModified, which advances at 0.6× real distance — a lazy
     *  ~1.3 Hz sway at full walking speed. Higher reads as scurrying. */
    private static final float BOB_FREQ    = 0.6f;

    private final ShaderProgram shader; // item.vert/frag: textured + uAmbient
    private final TextureAtlas atlas;
    private final Texture skinTexture;
    private final Map<BlockType, Mesh> blockMeshes = new EnumMap<>(BlockType.class);
    private final Mesh armMesh;
    private final Matrix4f identity = new Matrix4f();
    private final Matrix4f model    = new Matrix4f();

    /** What the hand is currently showing (null = bare arm). */
    private BlockType shownType  = null;
    /** 1 = fully raised, 0 = dropped off-screen (mid item switch). */
    private float equip = 1f;
    private boolean shownInitialized = false;
    private float bobPhase = 0f;
    private float bobAmp   = 0f;

    public HandRenderer(TextureAtlas atlas) {
        this.atlas  = atlas;
        this.shader = new ShaderProgram("/shaders/item.vert", "/shaders/item.frag");
        this.skinTexture = new Texture(PlayerSkin.PATH); // real steve.png skin
        this.armMesh = buildArmMesh();
    }

    public void render(Matrix4f projection, Player player, float ambient, float delta) {
        if (player.isDead()) return;

        BlockType target = player.getInventory().getType(player.getInventory().getSelected());
        if (!shownInitialized) { // first frame: no equip dip on spawn
            shownType = target;
            shownInitialized = true;
        }

        // Equip dip: lower the hand, swap the item at the bottom, raise it
        if (target != shownType) {
            equip -= EQUIP_SPEED * delta;
            if (equip <= 0f) {
                equip = 0f;
                shownType = target;
            }
        } else if (equip < 1f) {
            equip = Math.min(1f, equip + EQUIP_SPEED * delta);
        }

        // Walk bob: sway follows horizontal speed, eased in and out
        float vx = player.getVelocity().x, vz = player.getVelocity().z;
        float speed = (float) Math.sqrt(vx * vx + vz * vz);
        float targetAmp = player.isOnGround() ? Math.min(speed / 4.3f, 1f) : 0f;
        bobAmp  += (targetAmp - bobAmp) * Math.min(1f, delta * 5f);
        bobPhase += speed * delta * BOB_FREQ * (float) Math.PI;
        float bobX = (float) Math.sin(bobPhase)  * bobAmp * 0.020f;
        float bobY = -Math.abs((float) Math.cos(bobPhase)) * bobAmp * 0.022f;

        // MC's swing easings (1.8 ItemRenderer): sqrt-sine drives the big arc,
        // squared-sine the twist.
        float swing = player.getSwingProgress();
        float sqrtSin  = (float) Math.sin(Math.sqrt(swing) * Math.PI);
        float sqrtSin2 = (float) Math.sin(Math.sqrt(swing) * Math.PI * 2.0);
        float linSin   = (float) Math.sin(swing * Math.PI);
        float sqSin    = (float) Math.sin(swing * swing * Math.PI);
        float equipProgress = 1f - equip; // MC: 0 = raised, 1 = lowered

        glClear(GL_DEPTH_BUFFER_BIT); // hand draws over the world, never inside it
        glDisable(GL_CULL_FACE);

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", identity);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uAmbient", Math.max(0.25f, ambient)); // hand stays readable at night

        if (shownType != null) {
            // Held block — MC's renderItemInFirstPerson BLOCK path verbatim:
            // swing drag translate, then transformFirstPersonItem.
            model.identity()
                 .translate(-0.4f * sqrtSin + 0.56f + bobX,
                            0.2f * sqrtSin2 - 0.52f - 0.6f * equipProgress + bobY,
                            -0.2f * linSin - 0.72f)
                 .rotateY((float) Math.toRadians(45.0))
                 .rotateY((float) Math.toRadians(sqSin * -20.0))
                 .rotateZ((float) Math.toRadians(sqrtSin * -20.0))
                 .rotateX((float) Math.toRadians(sqrtSin * -80.0))
                 .scale(0.4f);
            if (shownType.item) {
                // MC's builtin/generated first-person item pose: sprite faces
                // the camera, tilted 25°, raised and enlarged
                model.translate(0f, 0.2f, 0f)
                     .rotateY((float) Math.toRadians(-45.0)) // cancel the base 45 — face the view
                     .rotateZ((float) Math.toRadians(25.0))
                     .scale(1.7f);
            }
            shader.setUniform("uModel", model);
            atlas.bind(0);
            blockMeshes.computeIfAbsent(shownType, this::buildBlockMesh).render();
        } else {
            // Bare arm — MC's renderPlayerArm transform chain verbatim,
            // including its magic pose offsets.
            model.identity()
                 .translate(-0.3f * sqrtSin + 0.64f + bobX,
                            0.4f * sqrtSin2 - 0.6f - 0.6f * equipProgress + bobY,
                            -0.4f * linSin - 0.72f)
                 .rotateY((float) Math.toRadians(45.0))
                 .rotateY((float) Math.toRadians(sqrtSin * 70.0))
                 .rotateZ((float) Math.toRadians(sqSin * -20.0))
                 .translate(-1.0f, 3.6f, 3.5f)
                 .rotateZ((float) Math.toRadians(120.0))
                 .rotateX((float) Math.toRadians(200.0))
                 .rotateY((float) Math.toRadians(-135.0))
                 .translate(5.6f, 0f, 0f);
            shader.setUniform("uModel", model);
            skinTexture.bind(0);
            armMesh.render();
        }

        shader.detach();
        glEnable(GL_CULL_FACE);
    }

    /** Unit cube centered on the origin — or MC's extruded sprite for items. */
    private Mesh buildBlockMesh(BlockType type) {
        if (type.item) return ItemSpriteMesh.build(atlas, type);
        float lo = -0.5f, hi = 0.5f;
        return buildBox(lo, lo, lo, hi, hi, hi, type);
    }

    /**
     * The arm exactly as MC's ModelBiped bipedRightArm ends up in first
     * person: box (-3,-2,-2)..(1,10,4px) at rotation point (-5,2,0), all at
     * 1/16 scale — shoulder end at y=0, hand end at y=0.75. The skin's
     * y-down texture convention makes the hand cap land on the +y face,
     * which is what flipY encodes.
     */
    private Mesh buildArmMesh() {
        float s = 0.0625f; // MC model render scale (1 skin px = 1/16 block)
        float rx = -5 * s, ry = 2 * s; // bipedRightArm rotation point
        ArrayList<Float> verts = new ArrayList<>();
        ArrayList<Integer> idxs = new ArrayList<>();
        int[] vi = {0};
        PlayerSkin.appendBox(verts, idxs, vi,
            rx - 3 * s, ry - 2 * s, -2 * s,
            rx + 1 * s, ry + 10 * s, 2 * s,
            40, 16, 4, 12, 4, true); // right-arm skin region
        float[] va = new float[verts.size()];
        for (int i = 0; i < va.length; i++) va[i] = verts.get(i);
        int[] ia = new int[idxs.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = idxs.get(i);
        Mesh m = new Mesh();
        m.upload(va, ia);
        return m;
    }

    /** Box mesh with the block's atlas texture per face. */
    private Mesh buildBox(float x0, float y0, float z0, float x1, float y1, float z1,
                          BlockType type) {
        float[][][] faces = {
            {{x0,y1,z1},{x1,y1,z1},{x1,y1,z0},{x0,y1,z0}}, // TOP
            {{x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1}}, // BOTTOM
            {{x1,y0,z0},{x0,y0,z0},{x0,y1,z0},{x1,y1,z0}}, // NORTH
            {{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}}, // SOUTH
            {{x1,y0,z1},{x1,y0,z0},{x1,y1,z0},{x1,y1,z1}}, // EAST
            {{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}}, // WEST
        };
        TextureAtlas.Face[] atlasFaces = {
            TextureAtlas.Face.TOP, TextureAtlas.Face.BOTTOM,
            TextureAtlas.Face.NORTH, TextureAtlas.Face.SOUTH,
            TextureAtlas.Face.EAST, TextureAtlas.Face.WEST,
        };
        float[] lights = {1.0f, 0.5f, 0.8f, 0.8f, 0.6f, 0.6f};

        float[] verts = new float[24 * 6];
        int[] idx = new int[36];
        for (int fc = 0; fc < 6; fc++) {
            float[] uv = atlas.getUV(type, atlasFaces[fc]);
            float[][] uvs = {{uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}, {uv[0], uv[1]}};
            for (int v = 0; v < 4; v++) {
                int vi = fc * 4 + v;
                int p = vi * 6;
                verts[p]     = faces[fc][v][0];
                verts[p + 1] = faces[fc][v][1];
                verts[p + 2] = faces[fc][v][2];
                verts[p + 3] = uvs[v][0];
                verts[p + 4] = uvs[v][1];
                verts[p + 5] = lights[fc];
            }
            int b = fc * 4, q = fc * 6;
            idx[q] = b; idx[q+1] = b+1; idx[q+2] = b+2;
            idx[q+3] = b+2; idx[q+4] = b+3; idx[q+5] = b;
        }
        Mesh m = new Mesh();
        m.upload(verts, idx);
        return m;
    }

    public void cleanup() {
        shader.cleanup();
        skinTexture.cleanup();
        armMesh.cleanup();
        for (Mesh m : blockMeshes.values()) m.cleanup();
        blockMeshes.clear();
    }
}
