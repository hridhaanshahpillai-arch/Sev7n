package sev7n.player;

import org.lwjgl.glfw.GLFWVidMode;
import sev7n.Sev7n;
import sev7n.game.Game;
import sev7n.game.Input;
import sev7n.game.Log;
import sev7n.project.Project;
import sev7n.render.AppWindow;
import sev7n.render.FrameLimiter;
import sev7n.render.Framebuffer;
import sev7n.render.RenderSettings;
import sev7n.render.RenderView;
import sev7n.render.SceneRenderer;
import sev7n.render.vulkan.VulkanPresenter;
import sev7n.render.vulkan.VulkanUnavailableException;
import sev7n.scene.Camera;
import sev7n.scene.Entity;
import sev7n.scene.Json;
import sev7n.scene.Scene;
import sev7n.scene.SceneSerializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/**
 * Plays a project in its own window, without the editor: "--play" from the editor's
 * folder, and every exported game. F11 switches fullscreen on and off.
 *
 * It draws with the graphics API chosen in Settings (OpenGL or Vulkan); if Vulkan can't run
 * on this PC, it says why in the terminal and uses OpenGL.
 */
public final class GamePlayer {

    /** For automated checks: save the picture after this many frames, then quit. */
    public record TestShot(Path file, int frames) { }

    private final long window;
    private final int[] windowedBounds = new int[4]; // x, y, width, height before going fullscreen

    private GamePlayer(long window) {
        this.window = window;
    }

    /** Plays a project folder (sev7n --play), from its main scene or from 'sceneFile' if given. */
    public static void play(Path projectFolder, Path sceneFile, TestShot test) throws IOException {
        Project project = Project.openOrCreate(projectFolder);
        Scene scene = sceneFile != null ? SceneSerializer.load(sceneFile) : project.loadScene();
        run(project, scene, project.name() + "  —  " + Sev7n.NAME, test);
    }

    /** Plays an exported game, titled with the name saved in its export.json. */
    public static void playExported(Path gameFolder, Path infoFile) throws IOException {
        String title = "Game";
        if (Files.exists(infoFile)) {
            Map<String, Object> info = Json.object(Json.parse(Files.readString(infoFile)));
            title = Json.string(info, "name", title);
        }
        Project project = Project.openOrCreate(gameFolder);
        run(project, project.loadScene(), title, null);
    }

    private static void run(Project project, Scene scene, String title, TestShot test) throws IOException {
        RenderSettings graphics = RenderSettings.load(project.root().resolve(RenderSettings.FILE_NAME));
        Screen screen = null;
        if (graphics.api == RenderSettings.Api.VULKAN) {
            long window = AppWindow.openForVulkan(title, 1280, 720);
            try {
                screen = new VulkanScreen(window, graphics);
            } catch (VulkanUnavailableException e) {
                System.err.println("Vulkan can't run on this PC (" + e.getMessage() + "), so the game uses OpenGL");
                AppWindow.destroy(window);
            }
        }
        if (screen == null) screen = new GlScreen(AppWindow.open(title, 1280, 720, false), graphics);
        new GamePlayer(screen.window()).loop(project, scene, title, graphics, screen, test);
    }

    private void loop(Project project, Scene scene, String title, RenderSettings graphics, Screen screen, TestShot test) {
        Log log = new Log();
        Input input = new Input();
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_F11 && action == GLFW_PRESS) toggleFullscreen();
            else input.keyEvent(key, action);
        });
        glfwSetCursorPosCallback(window, (w, x, y) -> input.mouseMoved(x, y));
        glfwSetWindowSizeCallback(window, (w, width, height) -> input.setViewSize(width, height));
        int[] windowWidth = new int[1], windowHeight = new int[1];
        glfwGetWindowSize(window, windowWidth, windowHeight);
        input.setViewSize(windowWidth[0], windowHeight[0]);
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) input.mouseButton(action == GLFW_PRESS);
        });

        Game game = new Game(scene, log, input);
        game.start(project.scriptsFolder());
        SceneRenderer renderer = screen.renderer();
        renderer.setSettings(graphics);
        renderer.assets().setRoot(project.root());
        renderer.assets().onProblem(System.err::println);
        System.out.println("Drawing with " + renderer.backend().description());
        int printed = 0;
        boolean reportedFailure = false;
        int frame = 0;

        double last = glfwGetTime();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            double now = glfwGetTime();
            game.update(Math.min(now - last, 0.1));
            last = now;
            if (game.quitRequested()) break;
            if (game.restartRequested()) {
                // scripts asked to start over: a fresh copy of the scene from its file
                game.stop();
                try {
                    game = new Game(project.loadScene(), log, input);
                } catch (IOException e) {
                    System.err.println("Couldn't restart: " + e.getMessage());
                    break;
                }
                game.start(project.scriptsFolder());
                reportedFailure = false;
            }

            // the console lives in the terminal when there's no editor
            for (; printed < log.entries().size(); printed++) System.out.println(log.entries().get(printed).text());
            if (game.failed() && !reportedFailure) {
                // there may be no terminal (an exported game), so say so in the title bar too
                glfwSetWindowTitle(window, title + "  —  stopped by a script error: " + lastError(log));
                reportedFailure = true;
            }

            frame++;
            boolean finalFrame = test != null && frame >= test.frames();
            screen.show(game.scene(), game.scene().mainCamera(), finalFrame ? test.file() : null);
            if (finalFrame) break;
            FrameLimiter.waitForNextFrame(graphics.maxFps, now);
        }
        game.stop();

        screen.close();
        AppWindow.close(window);
    }

    private static String lastError(Log log) {
        for (int i = log.entries().size() - 1; i >= 0; i--) {
            if (log.entries().get(i).level() == Log.Level.ERROR) return log.entries().get(i).text();
        }
        return "see the log";
    }

    private void toggleFullscreen() {
        if (glfwGetWindowMonitor(window) != 0) {
            glfwSetWindowMonitor(window, 0, windowedBounds[0], windowedBounds[1], windowedBounds[2], windowedBounds[3], GLFW_DONT_CARE);
            return;
        }
        int[] x = new int[1];
        int[] y = new int[1];
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetWindowPos(window, x, y);
        glfwGetWindowSize(window, w, h);
        windowedBounds[0] = x[0];
        windowedBounds[1] = y[0];
        windowedBounds[2] = w[0];
        windowedBounds[3] = h[0];
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode mode = glfwGetVideoMode(monitor);
        if (mode != null) glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
    }

    // ---------------------------------------------------------------- drawing with each graphics API

    /** Draws frames into the window with one graphics API, at the render scale and with anti-aliasing. */
    private interface Screen {
        long window();

        SceneRenderer renderer();

        /** Draws the scene from the camera (black if there's none) and shows it; saves it too if 'screenshot' is set. */
        void show(Scene scene, Entity camera, Path screenshot);

        void close();
    }

    private static int[] framebufferSize(long window) {
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        return new int[] {width[0], height[0]};
    }

    private static int scaled(int size, RenderSettings graphics) {
        return (int) Math.round(size * graphics.renderScale);
    }

    private static final class GlScreen implements Screen {
        private final long window;
        private final RenderSettings graphics;
        private final SceneRenderer renderer = new SceneRenderer();
        private final Framebuffer frame = new Framebuffer();

        GlScreen(long window, RenderSettings graphics) {
            this.window = window;
            this.graphics = graphics;
            glfwSwapInterval(graphics.vsync ? 1 : 0);
        }

        @Override
        public long window() {
            return window;
        }

        @Override
        public SceneRenderer renderer() {
            return renderer;
        }

        @Override
        public void show(Scene scene, Entity camera, Path screenshot) {
            int[] size = framebufferSize(window);
            glViewport(0, 0, size[0], size[1]);
            if (camera != null && size[0] > 0 && size[1] > 0) {
                // draw at the render scale with anti-aliasing, then stretch onto the window
                frame.resize(scaled(size[0], graphics), scaled(size[1], graphics), graphics.antiAliasing, graphics.renderScale < 1);
                frame.bind();
                RenderView view = RenderView.fromCamera(camera, (float) size[0] / size[1]);
                renderer.render(scene, view, camera.get(Camera.class).background, SceneRenderer.Overlays.NONE);
                frame.end();
                frame.drawToScreen(size[0], size[1]);
            } else {
                glClearColor(0, 0, 0, 1);
                glClear(GL_COLOR_BUFFER_BIT);
            }
            if (screenshot != null) {
                try {
                    AppWindow.screenshot(window, screenshot);
                } catch (IOException e) {
                    System.err.println("Screenshot failed: " + e.getMessage());
                }
            }
            glfwSwapBuffers(window);
        }

        @Override
        public void close() {
            frame.close();
            renderer.close();
        }
    }

    private static final class VulkanScreen implements Screen {
        private final long window;
        private final RenderSettings graphics;
        private final VulkanPresenter presenter;
        private final SceneRenderer renderer;

        VulkanScreen(long window, RenderSettings graphics) {
            this.window = window;
            this.graphics = graphics;
            presenter = new VulkanPresenter(window, graphics.vsync);
            renderer = new SceneRenderer(presenter.backend());
        }

        @Override
        public long window() {
            return window;
        }

        @Override
        public SceneRenderer renderer() {
            return renderer;
        }

        @Override
        public void show(Scene scene, Entity camera, Path screenshot) {
            int[] size = framebufferSize(window);
            if (size[0] == 0 || size[1] == 0) return; // minimized
            if (!presenter.beginFrame(scaled(size[0], graphics), scaled(size[1], graphics), graphics.antiAliasing)) return;
            if (camera != null) {
                RenderView view = RenderView.fromCamera(camera, (float) size[0] / size[1]);
                renderer.render(scene, view, camera.get(Camera.class).background, SceneRenderer.Overlays.NONE);
            }
            presenter.endFrame(graphics.renderScale < 1, screenshot);
        }

        @Override
        public void close() {
            renderer.close();
            presenter.close();
        }
    }
}
