package sev7n.render;

import org.joml.Matrix4f;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.lwjgl.opengl.GL11C.*;

/**
 * Draws with OpenGL 3.3: the editor always uses this, and so do games unless Vulkan is
 * chosen in Settings. Needs the window's OpenGL context to be current.
 */
public final class GlBackend implements GraphicsBackend {

    private final Shader lit = new Shader(Shaders.LIT_VERTEX, Shaders.LIT_FRAGMENT);
    private final Shader sprite = new Shader(Shaders.LIT_VERTEX, Shaders.SPRITE_FRAGMENT);
    private final Shader unlit = new Shader(Shaders.UNLIT_VERTEX, Shaders.UNLIT_FRAGMENT);
    private final String description = "OpenGL " + glGetString(GL_VERSION) + " on " + glGetString(GL_RENDERER);

    /** Which kind of drawing the GL state is set up for, so it only changes when needed. */
    private enum Mode { NONE, LIT, SPRITE, PARTICLE, LINES }

    private Mode mode = Mode.NONE;
    private Frame frame;

    @Override
    public GpuMesh mesh(MeshData data) {
        return new Mesh(data);
    }

    @Override
    public GpuMesh dynamicMesh(MeshData data) {
        return new Mesh(data, true);
    }

    @Override
    public void updateMesh(GpuMesh mesh, float[] vertices) {
        ((Mesh) mesh).update(vertices);
    }

    @Override
    public GpuTexture texture(BufferedImage image, boolean pixelArt) {
        return Texture.fromImage(image, pixelArt);
    }

    @Override
    public void begin(Frame frame) {
        this.frame = frame;
        Color background = frame.background();
        glClearColor(background.getRed() / 255f, background.getGreen() / 255f, background.getBlue() / 255f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDisable(GL_CULL_FACE); // planes and sprites should be visible from both sides
        mode = Mode.NONE;
    }

    @Override
    public void drawLit(GpuMesh mesh, GpuTexture texture, Matrix4f model, Color color) {
        use(Mode.LIT);
        lit.set("uModel", model);
        if (texture != null) ((Texture) texture).bind(0);
        lit.set("uUseTexture", texture != null ? 1 : 0);
        lit.set("uColor", color);
        ((Mesh) mesh).draw();
    }

    @Override
    public void drawSprite(GpuMesh mesh, GpuTexture texture, Matrix4f model, Color tint, float u, float v, float width, float height) {
        use(Mode.SPRITE);
        if (texture != null) ((Texture) texture).bind(0);
        sprite.set("uUseTexture", texture != null ? 1 : 0);
        sprite.setRgba("uTint", tint);
        sprite.set("uModel", model);
        sprite.set("uUVRect", u, v, width, height);
        ((Mesh) mesh).draw();
    }

    @Override
    public void drawParticle(GpuMesh mesh, GpuTexture texture, Matrix4f model, Color tint, boolean glow, float u, float v, float width, float height) {
        use(Mode.PARTICLE);
        glBlendFunc(GL_SRC_ALPHA, glow ? GL_ONE : GL_ONE_MINUS_SRC_ALPHA); // glowing particles add their light
        if (texture != null) ((Texture) texture).bind(0);
        sprite.set("uUseTexture", texture != null ? 1 : 0);
        sprite.setRgba("uTint", tint);
        sprite.set("uModel", model);
        sprite.set("uUVRect", u, v, width, height);
        ((Mesh) mesh).draw();
    }

    @Override
    public void drawLines(GpuMesh mesh, Matrix4f model, Color color, float alpha, float fadeStart, float fadeEnd, boolean onTop) {
        use(Mode.LINES);
        unlit.set("uModel", model);
        unlit.set("uColor", color);
        unlit.set("uAlpha", alpha);
        unlit.set("uFadeStart", fadeStart);
        unlit.set("uFadeEnd", fadeEnd);
        if (onTop) glDisable(GL_DEPTH_TEST);
        ((Mesh) mesh).draw();
        if (onTop) glEnable(GL_DEPTH_TEST);
    }

    @Override
    public void end() {
        use(Mode.NONE);
        frame = null;
    }

    @Override
    public String description() {
        return description;
    }

    /** Switches the GL state and shader to a kind of drawing, setting what all its draws share. */
    private void use(Mode next) {
        if (next == mode) return;
        // leave the old mode
        if (mode != Mode.LIT) glDisable(GL_BLEND);
        if (mode == Mode.LINES || mode == Mode.PARTICLE) glDepthMask(true);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        mode = next;
        if (next == Mode.NONE) return;

        RenderSettings settings = frame.settings();
        RenderView view = frame.view();
        if ((next == Mode.LIT || next == Mode.SPRITE) && settings.shading == RenderSettings.Shading.WIREFRAME) glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        if (next != Mode.LIT) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        if (next == Mode.LINES || next == Mode.PARTICLE) glDepthMask(false); // see-through: never hide what's drawn later

        Shader shader = switch (next) {
            case LIT -> lit;
            case SPRITE, PARTICLE -> sprite;
            default -> unlit;
        };
        shader.use();
        shader.set("uView", view.view());
        shader.set("uProjection", view.projection());
        shader.set("uEye", view.eye().x, view.eye().y, view.eye().z);
        if (next == Mode.LINES) return;

        shader.setTextureUnit("uTexture", 0);
        shader.set("uFogColor", frame.background());
        shader.set("uFogStart", settings.fog ? (float) settings.fogStart : 0f);
        shader.set("uFogEnd", settings.fog ? (float) Math.max(settings.fogEnd, settings.fogStart + 0.01) : 0f);
        shader.set("uBrightness", (float) settings.brightness);
        if (next == Mode.LIT) {
            lit.set("uUVRect", 0, 0, 1, 1);
            lit.setInt("uShading", switch (settings.shading) {
                case FLAT -> 1;
                case TOON -> 2;
                default -> 0;
            });
            lit.set("uLightDirection", frame.lightDirection().x, frame.lightDirection().y, frame.lightDirection().z);
            lit.set("uLightColor", frame.lightColor().x, frame.lightColor().y, frame.lightColor().z);
            lit.set("uAmbient", frame.ambient());
        }
    }

    @Override
    public void close() {
        lit.close();
        sprite.close();
        unlit.close();
    }
}
