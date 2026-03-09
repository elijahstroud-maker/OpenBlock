package com.openblock.renderer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.opengl.GL20.*;

public class ShaderProgram {
    private final int programId;
    private final Map<String, Integer> uniformCache = new HashMap<>();

    public ShaderProgram(String vertPath, String fragPath) {
        int vertId = compile(loadResource(vertPath), GL_VERTEX_SHADER);
        int fragId = compile(loadResource(fragPath), GL_FRAGMENT_SHADER);

        programId = glCreateProgram();
        glAttachShader(programId, vertId);
        glAttachShader(programId, fragId);
        glLinkProgram(programId);

        if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader link failed:\n" + glGetProgramInfoLog(programId));
        }

        glDeleteShader(vertId);
        glDeleteShader(fragId);
    }

    private int compile(String source, int type) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader compile failed:\n" + glGetShaderInfoLog(id));
        }
        return id;
    }

    private String loadResource(String path) {
        try (InputStream is = Objects.requireNonNull(
                getClass().getResourceAsStream(path),
                "Resource not found: " + path)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }

    public void use() {
        glUseProgram(programId);
    }

    public void detach() {
        glUseProgram(0);
    }

    public void setUniform(String name, Matrix4f mat) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(loc(name), false, mat.get(stack.mallocFloat(16)));
        }
    }

    public void setUniform(String name, int val) {
        glUniform1i(loc(name), val);
    }

    public void setUniform(String name, float val) {
        glUniform1f(loc(name), val);
    }

    public void setUniform(String name, Vector3f v) {
        glUniform3f(loc(name), v.x, v.y, v.z);
    }

    public void setUniform(String name, float x, float y, float z, float w) {
        glUniform4f(loc(name), x, y, z, w);
    }

    private int loc(String name) {
        return uniformCache.computeIfAbsent(name, n -> glGetUniformLocation(programId, n));
    }

    public void cleanup() {
        glDeleteProgram(programId);
    }
}
