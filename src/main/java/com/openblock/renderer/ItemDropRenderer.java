package com.openblock.renderer;

import com.openblock.world.BlockType;
import com.openblock.world.ItemDrop;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * Renders dropped items as Minecraft-style mini blocks: quarter-size cubes
 * textured from the block atlas, slowly spinning and gently bobbing above
 * where they physically rest.
 */
public class ItemDropRenderer {
    private static final float SCALE     = 0.25f; // mini block edge length
    private static final float VIEW_DIST = 64f;   // items past this aren't drawn
    // MC pacing: one full spin every ~6.3s, bob amplitude 0.1 blocks
    private static final float SPIN_SPEED = 1.0f; // radians per second
    // Face shading, matched to ChunkMesher so items blend with the terrain
    private static final float LIGHT_TOP = 1.00f, LIGHT_BOT = 0.60f;
    private static final float LIGHT_NS  = 0.80f, LIGHT_EW  = 0.85f;

    private final ShaderProgram shader;
    private final TextureAtlas atlas;
    private final Map<BlockType, Mesh> cubes = new EnumMap<>(BlockType.class);
    private final Matrix4f model = new Matrix4f();

    public ItemDropRenderer(TextureAtlas atlas) {
        this.atlas = atlas;
        this.shader = new ShaderProgram("/shaders/item.vert", "/shaders/item.frag");
    }

    public void render(Matrix4f projection, Matrix4f view, List<ItemDrop> drops,
                       Vector3f eye, float ambient) {
        if (drops.isEmpty()) return;

        glDisable(GL_CULL_FACE); // tiny cubes; skip winding worries

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uAmbient", ambient);
        atlas.bind(0);

        for (ItemDrop d : drops) {
            if (d.pos.distanceSquared(eye) > VIEW_DIST * VIEW_DIST) continue;
            // Spin + bob are render-only: the physics box sits still on the ground
            float bob = 0.05f + 0.05f * (float) Math.sin(d.age * 2f + d.phase);
            model.identity()
                 .translate(d.pos.x, d.pos.y + bob, d.pos.z)
                 .rotateY(d.age * SPIN_SPEED + d.phase)
                 .scale(SCALE);
            shader.setUniform("uModel", model);
            cubeFor(d.type).render();
        }

        shader.detach();
        glEnable(GL_CULL_FACE);
    }

    /** Falling sand/gravel: the same cached cubes at full block size, no spin. */
    public void renderFalling(Matrix4f projection, Matrix4f view,
                              List<com.openblock.world.FallingBlock> blocks, float ambient) {
        if (blocks.isEmpty()) return;

        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uAmbient", ambient);
        atlas.bind(0);

        for (com.openblock.world.FallingBlock f : blocks) {
            model.identity().translate(f.bx + 0.5f, f.centerY, f.bz + 0.5f);
            shader.setUniform("uModel", model);
            cubeFor(f.type).render();
        }

        shader.detach();
    }

    /** Lazily builds (and caches) the unit cube mesh for a block type. */
    private Mesh cubeFor(BlockType type) {
        return cubes.computeIfAbsent(type, this::buildCube);
    }

    private Mesh buildCube(BlockType type) {
        float lo = -0.5f, hi = 0.5f;
        // Corner runs per face, same winding as BlockCrackRenderer's cube
        float[][] corners = {
            // TOP
            {lo,hi,hi}, {hi,hi,hi}, {hi,hi,lo}, {lo,hi,lo},
            // BOTTOM
            {lo,lo,lo}, {hi,lo,lo}, {hi,lo,hi}, {lo,lo,hi},
            // NORTH
            {hi,lo,lo}, {lo,lo,lo}, {lo,hi,lo}, {hi,hi,lo},
            // SOUTH
            {lo,lo,hi}, {hi,lo,hi}, {hi,hi,hi}, {lo,hi,hi},
            // EAST
            {hi,lo,hi}, {hi,lo,lo}, {hi,hi,lo}, {hi,hi,hi},
            // WEST
            {lo,lo,lo}, {lo,lo,hi}, {lo,hi,hi}, {lo,hi,lo},
        };
        TextureAtlas.Face[] faces = {
            TextureAtlas.Face.TOP, TextureAtlas.Face.BOTTOM,
            TextureAtlas.Face.NORTH, TextureAtlas.Face.SOUTH,
            TextureAtlas.Face.EAST, TextureAtlas.Face.WEST,
        };
        float[] lights = {LIGHT_TOP, LIGHT_BOT, LIGHT_NS, LIGHT_NS, LIGHT_EW, LIGHT_EW};

        float[] verts = new float[24 * 6];
        int[] idx = new int[36];
        for (int f = 0; f < 6; f++) {
            float[] uv = atlas.getUV(type, faces[f]); // [u0, v0, u1, v1]
            // Corner UV order matches ChunkMesher: (u0,v1) (u1,v1) (u1,v0) (u0,v0)
            float[][] uvs = {{uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}, {uv[0], uv[1]}};
            for (int v = 0; v < 4; v++) {
                int vi = f * 4 + v;
                int p = vi * 6;
                verts[p]     = corners[vi][0];
                verts[p + 1] = corners[vi][1];
                verts[p + 2] = corners[vi][2];
                verts[p + 3] = uvs[v][0];
                verts[p + 4] = uvs[v][1];
                verts[p + 5] = lights[f];
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
