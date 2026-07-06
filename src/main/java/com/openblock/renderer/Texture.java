package com.openblock.renderer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.stb.STBImage.*;

public class Texture {
    private final int textureId;

    /** Load a PNG from classpath. */
    public Texture(String resourcePath) {
        byte[] bytes;
        try (var is = Texture.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new RuntimeException("Texture not found: " + resourcePath);
            bytes = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read texture: " + resourcePath, e);
        }

        ByteBuffer rawBuf = MemoryUtil.memAlloc(bytes.length);
        rawBuf.put(bytes).flip();

        int[] w = new int[1], h = new int[1], ch = new int[1];
        stbi_set_flip_vertically_on_load(true);
        ByteBuffer pixels = stbi_load_from_memory(rawBuf, w, h, ch, 4);
        MemoryUtil.memFree(rawBuf);

        if (pixels == null) {
            throw new RuntimeException("STB failed to load: " + resourcePath + " — " + stbi_failure_reason());
        }

        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w[0], h[0], 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glGenerateMipmap(GL_TEXTURE_2D);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

        stbi_image_free(pixels);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Create a texture directly from a raw RGBA byte buffer (for procedural atlas). */
    public Texture(ByteBuffer pixels, int width, int height) {
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        // No mipmaps for atlas: at lower mip levels the 272x16 strip collapses and tiles bleed together
        // (no glGenerateMipmap call — GL_NEAREST doesn't use them)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Uploads a tileSize×tileSize RGBA region at (tileCol*tileSize, 0) from a flat byte array. */
    public void updateTile(int tileCol, int tileSize, byte[] frameData, int frameIndex) {
        int frameBytes = tileSize * tileSize * 4;
        ByteBuffer tmp = MemoryUtil.memAlloc(frameBytes);
        tmp.put(frameData, frameIndex * frameBytes, frameBytes).flip();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, tileCol * tileSize, 0, tileSize, tileSize,
                        GL_RGBA, GL_UNSIGNED_BYTE, tmp);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(tmp);
    }

    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    public void cleanup() {
        glDeleteTextures(textureId);
    }
}
