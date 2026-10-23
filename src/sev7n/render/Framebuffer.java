package sev7n.render;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;

/**
 * An off-screen image to render into. The editor draws the Scene and Game views
 * into framebuffers, then shows them as pictures inside its panels; the game player
 * draws into one and then stretches it onto the window (for render scale).
 *
 * With anti-aliasing, drawing goes into a multisampled buffer that end() smooths
 * down into the picture.
 */
public final class Framebuffer implements AutoCloseable {

    private int fbo;
    private int colorTexture;
    private int depthBuffer;
    private int msFbo;          // multisampled buffer (0 when anti-aliasing is off)
    private int msColor;
    private int msDepth;
    private int width;
    private int height;
    private int samples;
    private boolean pixelated;

    /** Makes sure the framebuffer is exactly this size, recreating it if needed. */
    public void resize(int newWidth, int newHeight) {
        resize(newWidth, newHeight, 0, false);
    }

    /**
     * @param newSamples   anti-aliasing samples (0 = off)
     * @param newPixelated show the picture with sharp pixels when stretched (render scale below 1)
     */
    public void resize(int newWidth, int newHeight, int newSamples, boolean newPixelated) {
        newWidth = Math.max(1, newWidth);
        newHeight = Math.max(1, newHeight);
        newSamples = newSamples <= 1 ? 0 : Math.min(newSamples, glGetInteger(GL_MAX_SAMPLES));
        if (fbo != 0 && newWidth == width && newHeight == height && newSamples == samples && newPixelated == pixelated) return;
        close();
        width = newWidth;
        height = newHeight;
        samples = newSamples;
        pixelated = newPixelated;

        colorTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
        int filter = pixelated ? GL_NEAREST : GL_LINEAR;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glBindTexture(GL_TEXTURE_2D, 0);

        depthBuffer = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);

        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthBuffer);
        check();

        if (samples > 0) {
            msColor = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, msColor);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, width, height);
            msDepth = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, msDepth);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH24_STENCIL8, width, height);
            glBindRenderbuffer(GL_RENDERBUFFER, 0);
            msFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, msFbo);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msColor);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, msDepth);
            check();
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private static void check() {
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Framebuffer is incomplete");
        }
    }

    /** The framebuffer being drawn into (null: the window), so drawing elsewhere for a moment can come back to it. */
    private static Framebuffer bound;

    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, msFbo != 0 ? msFbo : fbo);
        glViewport(0, 0, width, height);
        bound = this;
    }

    /**
     * Binds the framebuffer that was being drawn into again (after the shadow map). Remembered
     * here because asking OpenGL (glGet) makes the CPU wait for the graphics card on some drivers.
     */
    static void rebind() {
        if (bound != null) bound.bind();
        else glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Finishes drawing: smooths the anti-aliased image into the picture, then unbinds. */
    public void end() {
        if (msFbo != 0) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, msFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        bound = null;
    }

    /** Stretches the finished picture over the whole window (sharp pixels if pixelated). */
    public void drawToScreen(int screenWidth, int screenHeight) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, width, height, 0, 0, screenWidth, screenHeight, GL_COLOR_BUFFER_BIT,
                pixelated ? GL_NEAREST : GL_LINEAR);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public static void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        bound = null;
    }

    public int texture() {
        return colorTexture;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    @Override
    public void close() {
        if (bound == this) bound = null;
        if (fbo == 0) return;
        glDeleteFramebuffers(fbo);
        glDeleteTextures(colorTexture);
        glDeleteRenderbuffers(depthBuffer);
        if (msFbo != 0) {
            glDeleteFramebuffers(msFbo);
            glDeleteRenderbuffers(msColor);
            glDeleteRenderbuffers(msDepth);
        }
        fbo = 0;
        msFbo = 0;
    }
}
