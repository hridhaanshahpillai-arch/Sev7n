package sev7n.render;

import org.joml.Matrix4f;

/**
 * Moves a part's vertices to follow a posed skeleton ("skinning"): each vertex goes where
 * its joints take it, mixed by its weights. Done on the CPU, which is plenty fast for
 * game characters (thousands of vertices) and works the same with OpenGL and Vulkan.
 */
final class Skinning {

    private Skinning() { }

    /**
     * Writes posed positions and normals into 'vertices' (the mesh's own layout: 8 floats per
     * vertex, so texture coordinates stay as they are).
     */
    static void apply(ModelData.Skin skin, Matrix4f[] joints, float[] vertices) {
        float[] p = skin.positions();
        float[] n = skin.normals();
        int[] j = skin.joints();
        float[] w = skin.weights();
        int stride = MeshData.FLOATS_PER_VERTEX;
        int count = p.length / 3;
        for (int v = 0; v < count; v++) {
            float px = p[v * 3], py = p[v * 3 + 1], pz = p[v * 3 + 2];
            float nx = n[v * 3], ny = n[v * 3 + 1], nz = n[v * 3 + 2];
            float ox = 0, oy = 0, oz = 0, mx = 0, my = 0, mz = 0;
            for (int k = 0; k < 4; k++) {
                float weight = w[v * 4 + k];
                if (weight == 0) continue;
                Matrix4f m = joints[j[v * 4 + k]];
                ox += weight * (m.m00() * px + m.m10() * py + m.m20() * pz + m.m30());
                oy += weight * (m.m01() * px + m.m11() * py + m.m21() * pz + m.m31());
                oz += weight * (m.m02() * px + m.m12() * py + m.m22() * pz + m.m32());
                mx += weight * (m.m00() * nx + m.m10() * ny + m.m20() * nz);
                my += weight * (m.m01() * nx + m.m11() * ny + m.m21() * nz);
                mz += weight * (m.m02() * nx + m.m12() * ny + m.m22() * nz);
            }
            float length = (float) Math.sqrt(mx * mx + my * my + mz * mz);
            if (length > 0) {
                mx /= length;
                my /= length;
                mz /= length;
            }
            int at = v * stride;
            vertices[at] = ox;
            vertices[at + 1] = oy;
            vertices[at + 2] = oz;
            vertices[at + 3] = mx;
            vertices[at + 4] = my;
            vertices[at + 5] = mz;
        }
    }
}
