package sev7n.render;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Mesh geometry on the CPU side, before it's uploaded. Also builds the built-in shapes.
 * All solid shapes fit in a 1x1x1 box centered on the origin, so scale = size.
 *
 * Every vertex is 8 floats: position (x, y, z), normal (nx, ny, nz), texture coords (u, v).
 * Texture coords use v = 0 at the TOP of the picture (images are uploaded top row first).
 */
public record MeshData(float[] vertices, int[] indices, boolean lines, Vector3f boundsMin, Vector3f boundsMax) {

    public static final int FLOATS_PER_VERTEX = 8;

    private static final Vector3f UNIT_MIN = new Vector3f(-0.5f);
    private static final Vector3f UNIT_MAX = new Vector3f(0.5f);

    // ---------------------------------------------------------------- solid shapes

    public static MeshData cube() {
        Builder b = new Builder();
        // one quad per face, each with its own normal so edges look sharp
        b.quad(new float[] {-.5f, -.5f, .5f, .5f, -.5f, .5f, .5f, .5f, .5f, -.5f, .5f, .5f}, 0, 0, 1);    // front
        b.quad(new float[] {.5f, -.5f, -.5f, -.5f, -.5f, -.5f, -.5f, .5f, -.5f, .5f, .5f, -.5f}, 0, 0, -1); // back
        b.quad(new float[] {-.5f, -.5f, -.5f, -.5f, -.5f, .5f, -.5f, .5f, .5f, -.5f, .5f, -.5f}, -1, 0, 0); // left
        b.quad(new float[] {.5f, -.5f, .5f, .5f, -.5f, -.5f, .5f, .5f, -.5f, .5f, .5f, .5f}, 1, 0, 0);     // right
        b.quad(new float[] {-.5f, .5f, .5f, .5f, .5f, .5f, .5f, .5f, -.5f, -.5f, .5f, -.5f}, 0, 1, 0);     // top
        b.quad(new float[] {-.5f, -.5f, -.5f, .5f, -.5f, -.5f, .5f, -.5f, .5f, -.5f, -.5f, .5f}, 0, -1, 0); // bottom
        return b.build(false, UNIT_MIN, UNIT_MAX);
    }

    /** A flat 1x1 square at y = 0, facing up. Scale it to make floors. */
    public static MeshData plane() {
        Builder b = new Builder();
        b.quad(new float[] {-.5f, 0, .5f, .5f, 0, .5f, .5f, 0, -.5f, -.5f, 0, -.5f}, 0, 1, 0);
        return b.build(false, new Vector3f(-.5f, -.01f, -.5f), new Vector3f(.5f, .01f, .5f));
    }

    /** A 1x1 square standing up, facing +Z: what sprites are drawn on. */
    public static MeshData quad() {
        Builder b = new Builder();
        b.quad(new float[] {-.5f, -.5f, 0, .5f, -.5f, 0, .5f, .5f, 0, -.5f, .5f, 0}, 0, 0, 1);
        return b.build(false, new Vector3f(-.5f, -.5f, -.01f), new Vector3f(.5f, .5f, .01f));
    }

    public static MeshData sphere(int segments, int rings) {
        Builder b = new Builder();
        for (int ring = 0; ring <= rings; ring++) {
            double phi = Math.PI * ring / rings;
            for (int seg = 0; seg <= segments; seg++) {
                double theta = 2 * Math.PI * seg / segments;
                float nx = (float) (Math.sin(phi) * Math.cos(theta));
                float ny = (float) Math.cos(phi);
                float nz = (float) (Math.sin(phi) * Math.sin(theta));
                b.vertex(nx * .5f, ny * .5f, nz * .5f, nx, ny, nz, (float) seg / segments, (float) ring / rings);
            }
        }
        int row = segments + 1;
        for (int ring = 0; ring < rings; ring++) {
            for (int seg = 0; seg < segments; seg++) {
                int a = ring * row + seg;
                int c = a + row;
                b.triangle(a, a + 1, c);
                b.triangle(a + 1, c + 1, c);
            }
        }
        return b.build(false, UNIT_MIN, UNIT_MAX);
    }

    public static MeshData cylinder(int segments) {
        Builder b = new Builder();
        // side
        for (int seg = 0; seg <= segments; seg++) {
            double theta = 2 * Math.PI * seg / segments;
            float x = (float) Math.cos(theta);
            float z = (float) Math.sin(theta);
            float u = (float) seg / segments;
            b.vertex(x * .5f, -.5f, z * .5f, x, 0, z, u, 1);
            b.vertex(x * .5f, .5f, z * .5f, x, 0, z, u, 0);
        }
        for (int seg = 0; seg < segments; seg++) {
            int i = seg * 2;
            b.triangle(i, i + 1, i + 2);
            b.triangle(i + 1, i + 3, i + 2);
        }
        // caps
        for (int side = -1; side <= 1; side += 2) {
            float y = side * .5f;
            int center = b.vertex(0, y, 0, 0, side, 0, .5f, .5f);
            for (int seg = 0; seg <= segments; seg++) {
                double theta = 2 * Math.PI * seg / segments;
                float cx = (float) Math.cos(theta);
                float cz = (float) Math.sin(theta);
                b.vertex(cx * .5f, y, cz * .5f, 0, side, 0, .5f + cx * .5f, .5f + cz * .5f);
            }
            for (int seg = 0; seg < segments; seg++) {
                if (side > 0) b.triangle(center, center + seg + 2, center + seg + 1);
                else b.triangle(center, center + seg + 1, center + seg + 2);
            }
        }
        return b.build(false, UNIT_MIN, UNIT_MAX);
    }

    // ---------------------------------------------------------------- line shapes

    /** The 12 edges of a unit cube: the selection outline. */
    public static MeshData wireCube() {
        Builder b = new Builder();
        for (int i = 0; i < 8; i++) {
            b.point((i & 1) == 0 ? -.5f : .5f, (i & 2) == 0 ? -.5f : .5f, (i & 4) == 0 ? -.5f : .5f);
        }
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] edge : edges) b.line(edge[0], edge[1]);
        return b.build(true, UNIT_MIN, UNIT_MAX);
    }

    /** Floor grid lines from -halfSize to +halfSize, one unit apart, at y = 0. */
    public static MeshData grid(int halfSize) {
        Builder b = new Builder();
        for (int i = -halfSize; i <= halfSize; i++) {
            b.line(b.point(i, 0, -halfSize), b.point(i, 0, halfSize));
            b.line(b.point(-halfSize, 0, i), b.point(halfSize, 0, i));
        }
        return b.build(true, new Vector3f(-halfSize, 0, -halfSize), new Vector3f(halfSize, 0, halfSize));
    }

    /** Collects vertices and indices while a shape is generated (also used by the model loader). */
    public static final class Builder {
        private final List<Float> vertices = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();
        private int count = 0;

        public int vertex(float x, float y, float z, float nx, float ny, float nz, float u, float v) {
            vertices.add(x);
            vertices.add(y);
            vertices.add(z);
            vertices.add(nx);
            vertices.add(ny);
            vertices.add(nz);
            vertices.add(u);
            vertices.add(v);
            return count++;
        }

        /** A vertex for line shapes, which don't need normals or texture coords. */
        int point(float x, float y, float z) {
            return vertex(x, y, z, 0, 0, 0, 0, 0);
        }

        public void triangle(int a, int b, int c) {
            indices.add(a);
            indices.add(b);
            indices.add(c);
        }

        void line(int a, int b) {
            indices.add(a);
            indices.add(b);
        }

        /** Four corners (bottom-left, bottom-right, top-right, top-left) sharing one normal, mapped to the whole picture. */
        void quad(float[] corners, float nx, float ny, float nz) {
            float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
            int first = count;
            for (int i = 0; i < 4; i++) {
                vertex(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2], nx, ny, nz, uv[i][0], uv[i][1]);
            }
            triangle(first, first + 1, first + 2);
            triangle(first, first + 2, first + 3);
        }

        public boolean isEmpty() {
            return indices.isEmpty();
        }

        public MeshData build(boolean lines, Vector3f min, Vector3f max) {
            float[] v = new float[vertices.size()];
            for (int i = 0; i < v.length; i++) v[i] = vertices.get(i);
            int[] idx = indices.stream().mapToInt(Integer::intValue).toArray();
            return new MeshData(v, idx, lines, new Vector3f(min), new Vector3f(max));
        }
    }
}
