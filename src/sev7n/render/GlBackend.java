package sev7n.render;

import org.joml.Matrix4f;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Draws with OpenGL 3.3: the editor always uses this, and so do games unless Vulkan is
 * chosen in Settings. Needs the window's OpenGL context to be current.
 */
public final class GlBackend implements GraphicsBackend {

    private static final int SHADOW_UNIT = 1; // the shadow map's texture unit (pictures use 0)

    private final Shader lit = new Shader(Shaders.LIT_VERTEX, Shaders.LIT_FRAGMENT);
    private final Shader sprite = new Shader(Shaders.LIT_VERTEX, Shaders.SPRITE_FRAGMENT);
    private final Shader unlit = new Shader(Shaders.UNLIT_VERTEX, Shaders.UNLIT_FRAGMENT);
    private final Shader shadow = new Shader(Shaders.SHADOW_VERTEX, Shaders.SHADOW_FRAGMENT);
    private final String description = "OpenGL " + glGetString(GL_VERSION) + " on " + glGetString(GL_RENDERER);

    // the shadow map: a depth picture of the scene from the light, and the framebuffer that draws it
    private final int shadowTexture = glGenTextures();
    private final int shadowFramebuffer = glGenFramebuffers();
    private int shadowSize;

    public GlBackend() {
        resizeShadowMap(1); // always something to bind, even with shadows off
    }

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

    // ---------------------------------------------------------------- shadows

    private void resizeShadowMap(int size) {
        shadowSize = size;
        glBindTexture(GL_TEXTURE_2D, shadowTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, size, size, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        // reading it compares depths (smoothly between pixels); outside it, nothing is in shadow
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[] {1, 1, 1, 1});
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        glBindTexture(GL_TEXTURE_2D, 0);

        glBindFramebuffer(GL_FRAMEBUFFER, shadowFramebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTexture, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        Framebuffer.rebind();
    }

    @Override
    public void beginShadows(Matrix4f lightMatrix, int size) {
        if (size != shadowSize) resizeShadowMap(size);
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFramebuffer);
        glViewport(0, 0, size, size);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE); // two-sided, like everything else
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glClear(GL_DEPTH_BUFFER_BIT);
        // pushes the depth back a little (more on slopes), so surfaces don't shadow themselves
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.5f, 3f);

        shadow.use();
        shadow.set("uLightMatrix", lightMatrix);
        shadow.setTextureUnit("uTexture", 0);
        mode = Mode.NONE;
    }

    @Override
    public void drawShadowCaster(GpuMesh mesh, GpuTexture texture, Matrix4f model) {
        shadow.set("uModel", model);
        if (texture != null) ((Texture) texture).bind(0);
        shadow.set("uUseTexture", texture != null ? 1 : 0);
        ((Mesh) mesh).draw();
    }

    @Override
    public void endShadows() {
        glDisable(GL_POLYGON_OFFSET_FILL);
        Framebuffer.rebind(); // back to the picture the frame is drawn into
    }

    // ---------------------------------------------------------------- the frame

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
            Shadows shadows = frame.shadows();
            glActiveTexture(GL_TEXTURE0 + SHADOW_UNIT);
            glBindTexture(GL_TEXTURE_2D, shadowTexture);
            glActiveTexture(GL_TEXTURE0);
            lit.setTextureUnit("uShadowMap", SHADOW_UNIT);
            if (shadows != null) lit.set("uShadowMatrix", shadows.matrix());
            lit.set("uShadow", shadows != null ? shadows.strength() : 0, shadows != null ? shadows.texel() : 0);
        }
    }

    @Override
    public void close() {
        lit.close();
        sprite.close();
        unlit.close();
        shadow.close();
        glDeleteFramebuffers(shadowFramebuffer);
        glDeleteTextures(shadowTexture);
    }
}
