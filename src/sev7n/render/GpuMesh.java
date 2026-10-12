package sev7n.render;

import org.joml.Vector3f;

/** Geometry uploaded to the GPU by a GraphicsBackend (OpenGL or Vulkan). */
public interface GpuMesh extends AutoCloseable {

    /** Local-space bounding box, used for outlines and clicking objects in the scene view. */
    Vector3f boundsMin();

    Vector3f boundsMax();

    @Override
    void close();
}
