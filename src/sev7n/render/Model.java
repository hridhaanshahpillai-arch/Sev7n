package sev7n.render;

import org.joml.Vector3f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Something a Mesh Renderer can draw: a built-in shape (one part) or an imported model
 * (one part per material, each with its own color and texture), on the GPU.
 */
public final class Model implements AutoCloseable {

    /** One material's worth of the model. 'texture' may be null. */
    public record Part(GpuMesh mesh, Color color, GpuTexture texture) { }

    public final List<Part> parts;
    /** Local-space bounding box, for outlines and clicking objects in the scene view. */
    public final Vector3f boundsMin;
    public final Vector3f boundsMax;
    private final boolean ownsMeshes;

    private Model(List<Part> parts, Vector3f boundsMin, Vector3f boundsMax, boolean ownsMeshes) {
        this.parts = parts;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
        this.ownsMeshes = ownsMeshes;
    }

    /** A plain white shape; the Mesh Renderer's color and texture do the rest. */
    static Model of(GpuMesh mesh) {
        return new Model(List.of(new Part(mesh, Color.WHITE, null)), mesh.boundsMin(), mesh.boundsMax(), false);
    }

    /** Uploads a loaded model's geometry and pictures to the GPU. */
    static Model upload(ModelData data, boolean pixelated, GraphicsBackend gpu) {
        List<Part> parts = new ArrayList<>();
        for (ModelData.Part part : data.parts()) {
            GpuTexture texture = part.texture() == null ? null : gpu.texture(part.texture(), pixelated);
            parts.add(new Part(gpu.mesh(part.mesh()), part.color(), texture));
        }
        Vector3f[] bounds = data.bounds();
        return new Model(List.copyOf(parts), bounds[0], bounds[1], true);
    }

    @Override
    public void close() {
        if (!ownsMeshes) return; // built-in shapes are shared and freed by the renderer
        for (Part part : parts) {
            part.mesh().close();
            if (part.texture() != null) part.texture().close();
        }
    }
}
