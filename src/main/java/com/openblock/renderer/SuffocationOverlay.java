package com.openblock.renderer;

import com.openblock.world.BlockType;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * The buried-head effect: when the camera is inside an opaque block (sand
 * poured on your head), the actual block cell is drawn as a heavily darkened,
 * inward-facing cube around the camera. Because the cube sits at the block's
 * real world position, looking around shows its faces in true perspective —
 * it reads as being inside the block, not as a texture pasted on the screen.
 * It also plugs the x-ray view: with the eye inside a block all terrain faces
 * are culled and you'd otherwise see straight through the world.
 */
public class SuffocationOverlay {
    private static final float DARKNESS = 0.12f; // MC draws it nearly black
    /** Cube overshoot past the cell so faces can't hit the near plane (0.1)
     *  even with the eye pressed right up against a block boundary. */
    private static final float SCALE = 1.3f;

    private final ShaderProgram shader;
    private final TextureAtlas atlas;
    private final Map<BlockType, Mesh> cubes = new EnumMap<>(BlockType.class);
    private final Matrix4f model = new Matrix4f();

    public SuffocationOverlay(TextureAtlas atlas) {
        this.atlas  = atlas;
        this.shader = new ShaderProgram("/shaders/item.vert", "/shaders/item.frag");
    }

    /** Draws the block cell (bx, by, bz) as a dark cube around the camera. */
    public void render(Matrix4f projection, Matrix4f view,
                       BlockType type, int bx, int by, int bz) {
        // Depth off both ways: this must cover everything already drawn, and
        // must not stamp the depth buffer for what comes after.
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE); // we're looking at the cube's inside faces

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uAmbient", DARKNESS);
        atlas.bind(0);

        model.identity()
             .translate(bx + 0.5f, by + 0.5f, bz + 0.5f)
             .scale(SCALE);
        shader.setUniform("uModel", model);
        cubes.computeIfAbsent(type, this::buildCube).render();

        shader.detach();
        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
    }

    /** Unit cube around the origin, each face carrying the block's texture. */
    private Mesh buildCube(BlockType type) {
        float lo = -0.5f, hi = 0.5f;
        float[][] corners = {
            {lo,hi,hi}, {hi,hi,hi}, {hi,hi,lo}, {lo,hi,lo},   // TOP
            {lo,lo,lo}, {hi,lo,lo}, {hi,lo,hi}, {lo,lo,hi},   // BOTTOM
            {hi,lo,lo}, {lo,lo,lo}, {lo,hi,lo}, {hi,hi,lo},   // NORTH
            {lo,lo,hi}, {hi,lo,hi}, {hi,hi,hi}, {lo,hi,hi},   // SOUTH
            {hi,lo,hi}, {hi,lo,lo}, {hi,hi,lo}, {hi,hi,hi},   // EAST
            {lo,lo,lo}, {lo,lo,hi}, {lo,hi,hi}, {lo,hi,lo},   // WEST
        };
        TextureAtlas.Face[] faces = {
            TextureAtlas.Face.TOP, TextureAtlas.Face.BOTTOM,
            TextureAtlas.Face.NORTH, TextureAtlas.Face.SOUTH,
            TextureAtlas.Face.EAST, TextureAtlas.Face.WEST,
        };

        float[] verts = new float[24 * 6];
        int[] idx = new int[36];
        for (int f = 0; f < 6; f++) {
            float[] uv = atlas.getUV(type, faces[f]);
            float[][] uvs = {{uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}, {uv[0], uv[1]}};
            for (int v = 0; v < 4; v++) {
                int vi = f * 4 + v;
                int p = vi * 6;
                verts[p]     = corners[vi][0];
                verts[p + 1] = corners[vi][1];
                verts[p + 2] = corners[vi][2];
                verts[p + 3] = uvs[v][0];
                verts[p + 4] = uvs[v][1];
                verts[p + 5] = 1.0f; // flat light; the darkness comes from uAmbient
            }
            int b = f * 4, q = f * 6;
            idx[q] = b; idx[q+1] = b+1; idx[q+2] = b+2;
            idx[q+3] = b+2; idx[q+4] = b+3; idx[q+5] = b;
        }
        Mesh m = new Mesh();
        m.upload(verts, idx);
        return m;
    }

    public void cleanup() {
        if (shader != null) shader.cleanup();
        for (Mesh m : cubes.values()) m.cleanup();
        cubes.clear();
    }
}
