package sev7n.render;

import org.joml.Vector3f;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A model loaded from a file, before it goes to the GPU: one part per material,
 * each with its geometry, base color and (optional) picture.
 *
 * Animated models also have a Rig (skeleton and animations), and each part a Skin
 * saying which joints move its vertices; 'mesh' then holds the rest pose.
 */
public record ModelData(List<Part> parts, Rig rig) {

    public ModelData(List<Part> parts) {
        this(parts, null);
    }

    /** Geometry drawn with one material. 'texture' and 'skin' may be null. */
    public record Part(MeshData mesh, Color color, BufferedImage texture, Skin skin) {
        public Part(MeshData mesh, Color color, BufferedImage texture) {
            this(mesh, color, texture, null);
        }
    }

    /**
     * How a part's vertices follow the skeleton: each vertex's position and normal before
     * posing, and up to 4 joints (indexes into the Rig's joints) with how much each pulls.
     */
    public record Skin(float[] positions, float[] normals, int[] joints, float[] weights) { }

    /** The box around every part. */
    public Vector3f[] bounds() {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (Part part : parts) {
            min.min(part.mesh().boundsMin());
            max.max(part.mesh().boundsMax());
        }
        return new Vector3f[] {min, max};
    }
}
