package sev7n.render;

import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import sev7n.ui.AppIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/** Creates the OS window with an OpenGL 3.3 context, shared by the editor and the game player. */
public final class AppWindow {

    private AppWindow() { }

    /** Opens a window and makes its OpenGL context current. Returns the GLFW handle. */
    public static long open(String title, int width, int height, boolean maximized) {
        if (!glfwInit()) throw new IllegalStateException("Couldn't start GLFW (no graphics display?)");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_MAXIMIZED, maximized ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_SAMPLES, 4);

        long window = glfwCreateWindow(width, height, title, 0, 0);
        if (window == 0) throw new IllegalStateException("Couldn't create a window. Does this PC support OpenGL 3.3?");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        glfwSwapInterval(1); // vsync
        setIcon(window);
        return window;
    }

    /** Opens a window with no OpenGL context, for drawing with Vulkan. Returns the GLFW handle. */
    public static long openForVulkan(String title, int width, int height) {
        if (!glfwInit()) throw new IllegalStateException("Couldn't start GLFW (no graphics display?)");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        long window = glfwCreateWindow(width, height, title, 0, 0);
        if (window == 0) throw new IllegalStateException("Couldn't create a window");
        setIcon(window);
        return window;
    }

    /** Closes a window but keeps GLFW running (to open another one). */
    public static void destroy(long window) {
        glfwDestroyWindow(window);
    }

    /** Gives the window the purple 7 icon (taskbar and title bar). */
    private static void setIcon(long window) {
        int[] sizes = {16, 32, 48, 64};
        try (GLFWImage.Buffer images = GLFWImage.malloc(sizes.length)) {
            ByteBuffer[] pixels = new ByteBuffer[sizes.length];
            for (int i = 0; i < sizes.length; i++) {
                BufferedImage image = AppIcon.windowIcon(sizes[i]);
                pixels[i] = toRgba(image);
                images.position(i).width(sizes[i]).height(sizes[i]).pixels(pixels[i]);
            }
            images.position(0);
            glfwSetWindowIcon(window, images);
            for (ByteBuffer buffer : pixels) MemoryUtil.memFree(buffer);
        }
    }

    private static ByteBuffer toRgba(BufferedImage image) {
        ByteBuffer buffer = MemoryUtil.memAlloc(image.getWidth() * image.getHeight() * 4);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                buffer.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >> 24));
            }
        }
        return buffer.flip();
    }

    /** Saves what's currently on screen as a PNG (used for automated checks). */
    public static void screenshot(long window, Path file) throws IOException {
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetFramebufferSize(window, w, h);
        ByteBuffer pixels = MemoryUtil.memAlloc(w[0] * h[0] * 4);
        glReadPixels(0, 0, w[0], h[0], GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(w[0], h[0], BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h[0]; y++) {
            for (int x = 0; x < w[0]; x++) {
                int i = (x + (h[0] - 1 - y) * w[0]) * 4; // OpenGL rows start at the bottom
                int rgb = ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255);
                image.setRGB(x, y, rgb);
            }
        }
        MemoryUtil.memFree(pixels);
        ImageIO.write(image, "png", file.toFile());
    }

    public static void close(long window) {
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
