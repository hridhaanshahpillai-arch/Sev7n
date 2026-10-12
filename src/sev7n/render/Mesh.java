package sev7n.render;

import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;

/**
 * Geometry uploaded to the GPU with OpenGL.
 * Every vertex is 8 floats: position (x, y, z), normal (nx, ny, nz), texture coords (u, v).
 */
public final class Mesh implements GpuMesh {

    private static final int FLOATS_PER_VERTEX = MeshData.FLOATS_PER_VERTEX;

    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;
    private final int drawMode;
    private final Vector3f boundsMin;
    private final Vector3f boundsMax;

    public Mesh(MeshData data) {
        this.indexCount = data.indices().length;
        this.drawMode = data.lines() ? GL_LINES : GL_TRIANGLES;
        this.boundsMin = data.boundsMin();
        this.boundsMax = data.boundsMax();

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, data.vertices(), GL_STATIC_DRAW);

        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, data.indices(), GL_STATIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glEnableVertexAttribArray(2);

        glBindVertexArray(0);
    }

    @Override
    public Vector3f boundsMin() {
        return boundsMin;
    }

    @Override
    public Vector3f boundsMax() {
        return boundsMax;
    }

    public void draw() {
        glBindVertexArray(vao);
        glDrawElements(drawMode, indexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    @Override
    public void close() {
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
    }
}
