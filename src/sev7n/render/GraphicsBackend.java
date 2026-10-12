package sev7n.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * A graphics API that SceneRenderer draws with: OpenGL (GlBackend, used everywhere) or
 * Vulkan (VulkanBackend, for the game window when chosen in Settings).
 *
 * SceneRenderer decides what to draw and in what order; a backend only knows how. One frame:
 *   begin(frame)   clear, and set the camera, light and look every draw shares
 *   drawLit / drawSprite / drawLines, as many as needed
 *   end()
 * Matrices passed to draws are only read during the call, so callers can reuse them.
 */
public interface GraphicsBackend extends AutoCloseable {

    /** What every draw in a frame shares. 'lightColor' is already multiplied by the light's intensity. */
    record Frame(RenderView view, Color background, Vector3f lightDirection, Vector3f lightColor, float ambient,
                 RenderSettings settings) { }

    GpuMesh mesh(MeshData data);

    /** @param pixelArt true keeps pixels sharp (no smoothing) */
    GpuTexture texture(BufferedImage image, boolean pixelArt);

    void begin(Frame frame);

    /** A solid, lit surface (texture may be null). Depth tested and written. */
    void drawLit(GpuMesh mesh, GpuTexture texture, Matrix4f model, Color color);

    /**
     * A see-through picture card: the part (u, v, width, height) of the texture times the tint,
     * unaffected by light. Negative width or height mirrors it.
     */
    void drawSprite(GpuMesh mesh, GpuTexture texture, Matrix4f model, Color tint, float u, float v, float width, float height);

    /**
     * Flat-colored lines (grid, outlines) that fade out between fadeStart and fadeEnd from the eye.
     * They don't hide what's drawn after them; onTop draws them over everything.
     */
    void drawLines(GpuMesh mesh, Matrix4f model, Color color, float alpha, float fadeStart, float fadeEnd, boolean onTop);

    void end();

    /** "OpenGL" or "Vulkan", plus the graphics card's name if known. */
    String description();

    @Override
    void close();
}
