package com.openblock.renderer;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Interleaved VAO/VBO/EBO. Vertex layout: [x,y,z, u,v, light] = 6 floats per vertex.
 */
public class Mesh {
    private int vaoId;
    private int vboId;
    private int eboId;
    private int indexCount;

    public static final int FLOATS_PER_VERTEX = 6;
    private static final int STRIDE = FLOATS_PER_VERTEX * Float.BYTES;

    public Mesh() {
        vaoId = glGenVertexArrays();
        vboId = glGenBuffers();
        eboId = glGenBuffers();
    }

    /** Upload or replace vertex and index data on the GPU. */
    public void upload(float[] vertices, int[] indices) {
        this.indexCount = indices.length;

        FloatBuffer vb = MemoryUtil.memAllocFloat(vertices.length);
        IntBuffer   ib = MemoryUtil.memAllocInt(indices.length);
        try {
            vb.put(vertices).flip();
            ib.put(indices).flip();

            glBindVertexArray(vaoId);

            glBindBuffer(GL_ARRAY_BUFFER, vboId);
            glBufferData(GL_ARRAY_BUFFER, vb, GL_DYNAMIC_DRAW);

            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_DYNAMIC_DRAW);

            // position: attribute 0, 3 floats
            glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0L);
            glEnableVertexAttribArray(0);
            // texCoord: attribute 1, 2 floats
            glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE, 3L * Float.BYTES);
            glEnableVertexAttribArray(1);
            // light: attribute 2, 1 float
            glVertexAttribPointer(2, 1, GL_FLOAT, false, STRIDE, 5L * Float.BYTES);
            glEnableVertexAttribArray(2);

            glBindVertexArray(0);
        } finally {
            MemoryUtil.memFree(vb);
            MemoryUtil.memFree(ib);
        }
    }

    public void render() {
        if (indexCount == 0) return;
        glBindVertexArray(vaoId);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
    }

    public boolean isEmpty() {
        return indexCount == 0;
    }

    public void cleanup() {
        glDeleteBuffers(vboId);
        glDeleteBuffers(eboId);
        glDeleteVertexArrays(vaoId);
        indexCount = 0;
    }
}
