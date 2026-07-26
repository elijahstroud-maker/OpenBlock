package com.openblock.renderer;

import com.openblock.crafting.Tools;
import com.openblock.player.Player;
import com.openblock.world.BlockType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * The player's body rendered in the world for the F5 third-person cameras,
 * posed with Minecraft's ModelBiped animation formulas: walking limb swing
 * driven by the player's limbSwing/limbSwingAmount pair, the punch arc with
 * its torso twist on the right arm, the idle arm sway, and the full sneak
 * pose when crouching.
 *
 * Body yaw follows MC's renderYawOffset rules: looking around only turns the
 * head; the body lags behind, snapping along once the head passes ±75°, and
 * catches up smoothly while walking. The selected hotbar block rides in the
 * right hand, attached to the arm joint so it follows swings.
 */
public class PlayerModelRenderer {
    /** MC's player render scale: Steve is visually 1.875 blocks tall,
     *  slightly taller than the 1.8 hitbox — MC does exactly this. */
    private static final float SCALE = PlayerModel.RENDER_SCALE;
    /** Held block edge length in model pixels (MC third-person: 0.375 blocks). */
    private static final float HELD_BLOCK_PX = 0.375f / SCALE;

    private final ShaderProgram shader;
    private final Texture skin;
    private final TextureAtlas atlas;
    private final PlayerModel model;
    private final PlayerModel.Pose pose = new PlayerModel.Pose();
    private final Matrix4f base = new Matrix4f();
    private final Matrix4f handMatrix = new Matrix4f();
    private final Map<BlockType, Mesh> heldMeshes = new EnumMap<>(BlockType.class);

    /** MC's renderYawOffset — the body's own smoothed yaw, in degrees. */
    private float bodyYawDeg = Float.NaN;

    public PlayerModelRenderer(TextureAtlas atlas) {
        this.atlas = atlas;
        shader = new ShaderProgram("/shaders/item.vert", "/shaders/item.frag");
        skin   = new Texture(PlayerSkin.PATH);
        model  = new PlayerModel();
    }

    public void render(Matrix4f projection, Matrix4f view, Player player,
                       float ambient, float delta) {
        Vector3f eye   = player.getCamera().getPosition();
        Vector3f front = player.getCamera().getFront();
        float headYawDeg = (float) Math.toDegrees(Math.atan2(front.x, front.z));
        float pitchRad   = (float) Math.toRadians(player.getCamera().getPitch());

        // MC's body-follows-head: free head turn up to ±75°, the body drags
        // along past that, and walking reels the body back under the head.
        if (Float.isNaN(bodyYawDeg)) bodyYawDeg = headYawDeg;
        float off = wrapDeg(headYawDeg - bodyYawDeg);
        if (player.getLimbSwingAmount() > 0.05f) {
            bodyYawDeg += off * Math.min(1f, delta * 6f);
        }
        off = wrapDeg(headYawDeg - bodyYawDeg);
        if (off >  75f) bodyYawDeg = headYawDeg - 75f;
        if (off < -75f) bodyYawDeg = headYawDeg + 75f;
        off = wrapDeg(headYawDeg - bodyYawDeg);

        buildPose(player, pitchRad, (float) Math.toRadians(off));

        base.identity()
            .translate(eye.x, player.getFootY(), eye.z)
            .rotateY((float) Math.toRadians(bodyYawDeg))
            .scale(SCALE);

        glDisable(GL_CULL_FACE); // meshes are wound for cull-off rendering

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uAmbient", ambient);
        skin.bind(0);
        model.render(shader, base, pose);

        // Selected block/item rides in the right hand, following the arm joint
        BlockType held = player.getInventory().getType(player.getInventory().getSelected());
        if (held != null) {
            model.rightArmMatrix(base, pose, handMatrix);
            if (held.item && (Tools.isTool(held) || held == BlockType.STICK)) {
                // Tools (and sticks) are gripped like MC's handheld pose: the
                // sprite's flat face turned sideways (normal pointing out from
                // the body) so the side cameras see the tool, not its edge,
                // and the texture diagonal pointing up-forward out of the fist
                // (~35° above horizontal after the -10° roll); the texture's
                // bottom-left grip pixel is anchored inside the fist (handle
                // butt just behind the arm's front face) so the tool reads as
                // held, and swings pivot at the handle. 14.5px = MC's 0.85
                // block handheld third-person size.
                // Per-tool grip anchor, read off each texture's actual handle:
                // pick/axe/hoe handles run long and shallow — the fist sits
                // well up the shaft (pixel ~(5,10)); the sword is gripped at
                // its short handle just below the guard (pixel ~(2.5,13)).
                // The anchor value g maps texture point (-g,-g) into the fist.
                Tools.Info info = Tools.infoFor(held);
                float grip = (info != null && info.type() == Tools.Type.SWORD) ? 0.34f
                           : (info != null) ? 0.30f
                           : 0.28f; // stick
                handMatrix.translate(-1f, -9.8f, 0.5f)
                          .rotateY((float) Math.toRadians(-90.0))
                          .rotateZ((float) Math.toRadians(-10.0))
                          .scale(12f)
                          .translate(grip, grip, 0f)
                          // 180° about the handle diagonal: third person shows
                          // the sprite's BACK face (like MC), so the axe blade
                          // hangs off the correct side without touching the
                          // texture the inventory and first person show. The
                          // grip pixel lies on the axis, so the anchor holds.
                          .rotate((float) Math.PI, 0.7071f, 0.7071f, 0f);
            } else if (held.item) {
                // Small items (ingots, gems, coal) lie flat in the palm, face
                // up with the texture top pointing forward — MC's "generated"
                // third-person pose.
                handMatrix.translate(-1f, -9.5f, 2f)
                          .rotateX((float) Math.toRadians(90.0))
                          .rotateY((float) Math.toRadians(180.0))
                          .scale(9.4f);
            } else {
                handMatrix.translate(-1f, -12f, 1.5f)
                          .rotateY((float) Math.toRadians(45.0))
                          .scale(HELD_BLOCK_PX);
            }
            shader.setUniform("uModel", handMatrix);
            atlas.bind(0);
            heldMeshes.computeIfAbsent(held, this::buildHeldMesh).render();
        }
        shader.detach();

        glEnable(GL_CULL_FACE);
    }

    /** MC's ModelBiped.setRotationAngles, in this model's y-up +Z-facing space. */
    private void buildPose(Player player, float pitchRad, float headYawOffRad) {
        float k   = player.getLimbSwing() * 0.6662f;
        float amt = player.getLimbSwingAmount();
        float age = player.getAgeTicks();

        // Walking: legs swing 1.4 rad peak, arms counter-swing at 1.0
        pose.rLegX = (float) Math.cos(k) * 1.4f * amt;
        pose.lLegX = (float) Math.cos(k + Math.PI) * 1.4f * amt;
        pose.rArmX = (float) Math.cos(k + Math.PI) * 1.0f * amt;
        pose.lArmX = (float) Math.cos(k) * 1.0f * amt;
        pose.rArmY = 0f;
        pose.lArmY = 0f;

        // Idle sway: arms breathe outward and drift slightly
        float sway = (float) Math.cos(age * 0.09f) * 0.05f + 0.05f;
        pose.rArmZ = -sway;
        pose.lArmZ = sway;
        pose.rArmX += (float) Math.sin(age * 0.067f) * 0.05f;
        pose.lArmX -= (float) Math.sin(age * 0.067f) * 0.05f;

        // Head: yaw is the offset from the body's smoothed yaw
        pose.headRotY = headYawOffRad;
        pose.headRotX = -pitchRad;

        // Punch: torso twist + the right arm's raise-and-strike arc
        float sw = player.getSwingProgress();
        pose.bodyTwistY = 0f;
        if (sw > 0f) {
            pose.bodyTwistY = (float) (Math.sin(Math.sqrt(sw) * Math.PI * 2.0) * 0.2);
            pose.rArmY += pose.bodyTwistY * 2f; // on top of riding the torso group
            pose.lArmX += pose.bodyTwistY;
            float f1 = 1f - sw;
            f1 = 1f - f1 * f1 * f1;
            float f2 = (float) Math.sin(f1 * Math.PI);
            // MC couples the strike to head pitch (their pitch is down-positive)
            float f3 = (float) (Math.sin(sw * Math.PI) * -(-pitchRad - 0.7f) * 0.75f);
            pose.rArmX -= f2 * 1.2f + f3;
            pose.rArmZ += (float) Math.sin(sw * Math.PI) * 0.4f;
        }

        // Sneak: lean the torso; the arms angle BACK (MC's +0.4) so the hands
        // tuck underneath the leaned chest instead of jutting out in front.
        // PlayerModel reseats the hips and drops the whole model.
        pose.sneak = player.isCrouching();
        pose.bodyLeanX = pose.sneak ? 0.5f : 0f;
        if (pose.sneak) {
            pose.rArmX += 0.4f;
            pose.lArmX += 0.4f;
        }
    }

    private static float wrapDeg(float d) {
        return ((d + 180f) % 360f + 360f) % 360f - 180f;
    }

    /** Unit cube centered on the origin with world-style per-face shading. */
    private Mesh buildHeldMesh(BlockType type) {
        // Items (sticks) ride in the hand as extruded sprites
        if (type.item) return ItemSpriteMesh.build(atlas, type);
        float lo = -0.5f, hi = 0.5f;
        float[][][] faces = {
            {{lo,hi,hi},{hi,hi,hi},{hi,hi,lo},{lo,hi,lo}}, // TOP
            {{lo,lo,lo},{hi,lo,lo},{hi,lo,hi},{lo,lo,hi}}, // BOTTOM
            {{hi,lo,lo},{lo,lo,lo},{lo,hi,lo},{hi,hi,lo}}, // NORTH
            {{lo,lo,hi},{hi,lo,hi},{hi,hi,hi},{lo,hi,hi}}, // SOUTH
            {{hi,lo,hi},{hi,lo,lo},{hi,hi,lo},{hi,hi,hi}}, // EAST
            {{lo,lo,lo},{lo,lo,hi},{lo,hi,hi},{lo,hi,lo}}, // WEST
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
        skin.cleanup();
        model.cleanup();
        for (Mesh m : heldMeshes.values()) m.cleanup();
        heldMeshes.clear();
    }
}
