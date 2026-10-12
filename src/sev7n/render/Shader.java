package sev7n.render;

import org.joml.Matrix4f;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20C.*;

/** A compiled GPU program (vertex + fragment shader) with easy uniform setters. */
public final class Shader implements AutoCloseable {

    private final int program;
    private final Map<String, Integer> uniforms = new HashMap<>();
    private final float[] matrixBuffer = new float[16];

    public Shader(String vertexSource, String fragmentSource) {
        int vertex = compile(GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL_FRAGMENT_SHADER, fragmentSource);
        program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader link failed: " + glGetProgramInfoLog(program));
        }
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader compile failed: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    public void use() {
        glUseProgram(program);
    }

    public void set(String name, Matrix4f matrix) {
        glUniformMatrix4fv(location(name), false, matrix.get(matrixBuffer));
    }

    public void set(String name, float x, float y, float z) {
        glUniform3f(location(name), x, y, z);
    }

    public void set(String name, Color color) {
        set(name, color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f);
    }

    public void set(String name, float value) {
        glUniform1f(location(name), value);
    }

    public void set(String name, float x, float y, float z, float w) {
        glUniform4f(location(name), x, y, z, w);
    }

    public void setRgba(String name, Color color) {
        glUniform4f(location(name), color.getRed() / 255f, color.getGreen() / 255f,
                color.getBlue() / 255f, color.getAlpha() / 255f);
    }

    /** For int uniforms (set(name, 1) would send a float). */
    public void setInt(String name, int value) {
        glUniform1i(location(name), value);
    }

    /** Points a sampler uniform at a texture unit. */
    public void setTextureUnit(String name, int unit) {
        glUniform1i(location(name), unit);
    }

    private int location(String name) {
        return uniforms.computeIfAbsent(name, n -> glGetUniformLocation(program, n));
    }

    @Override
    public void close() {
        glDeleteProgram(program);
    }
}
