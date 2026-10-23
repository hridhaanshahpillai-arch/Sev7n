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
 *   beginShadows / drawShadowCaster / endShadows   (only with shadows on) the scene's depth as the light sees it
 *   begin(frame)   clear, and set the camera, light and look every draw shares
 *   drawLit / drawSprite / drawLines, as many as needed
 *   end()
 * Matrices passed to draws are only read during the call, so callers can reuse them.
 */
public interface GraphicsBackend extends AutoCloseable {

    /** What every draw in a frame shares. 'lightColor' is already multiplied by the light's intensity. */
    record Frame(RenderView view, Color background, Vector3f lightDirection, Vector3f lightColor, float ambient,
                 RenderSettings settings, Shadows shadows) { }

    /**
     * The shadows lit surfaces get this frame, from the shadow map drawn just before.
     * @param matrix    world to the light's view (the one given to beginShadows)
     * @param strength  0..1: how dark shadows are
     * @param texel     how wide one shadow map pixel is in the world, to lift surfaces off their own shadow
     */
    record Shadows(Matrix4f matrix, float strength, float texel) { }

    /** Starts drawing the shadow map (size x size pixels) from the light: world to its view with this matrix. */
    void beginShadows(Matrix4f lightMatrix, int size);

    /** Something that blocks light. Textured ones let light through where their picture is see-through. */
    void drawShadowCaster(GpuMesh mesh, GpuTexture texture, Matrix4f model);

    void endShadows();

    GpuMesh mesh(MeshData data);

    /** Geometry whose vertices change (animated characters). The vertex count and triangles never change. */
    GpuMesh dynamicMesh(MeshData data);

    /** New vertices for a dynamicMesh, in the same layout and number. Call while drawing a frame, before drawing it. */
    void updateMesh(GpuMesh mesh, float[] vertices);

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
     * A particle: a see-through card (the whole texture times the tint, or a plain card if null)
     * that is depth tested but never hides what's drawn after it. Glowing ones add their light.
     */
    default void drawParticle(GpuMesh mesh, GpuTexture texture, Matrix4f model, Color tint, boolean glow) {
        drawParticle(mesh, texture, model, tint, glow, 0, 0, 1, 1);
    }

    /** A particle showing only the part (u, v, width, height) of its texture (glowing HUD shapes). */
    void drawParticle(GpuMesh mesh, GpuTexture texture, Matrix4f model, Color tint, boolean glow, float u, float v, float width, float height);

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
