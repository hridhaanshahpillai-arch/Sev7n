package sev7n.render;

import org.joml.Vector3f;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A model loaded from a file, before it goes to the GPU: one part per material,
 * each with its geometry, base color and (optional) picture.
 */
public record ModelData(List<Part> parts) {

    /** Geometry drawn with one material. 'texture' may be null. */
    public record Part(MeshData mesh, Color color, BufferedImage texture) { }

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
