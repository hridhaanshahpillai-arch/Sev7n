package sev7n.render;

/** A picture uploaded to the GPU by a GraphicsBackend (OpenGL or Vulkan). v = 0 is the top of the picture. */
public interface GpuTexture extends AutoCloseable {

    int width();

    int height();

    default float aspect() {
        return (float) width() / height();
    }

    @Override
    void close();
}
