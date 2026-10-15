package sev7n.editor;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.extension.imguizmo.ImGuizmo;
import imgui.extension.imnodes.ImNodes;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import sev7n.Sev7n;
import sev7n.project.Project;
import sev7n.project.ProjectList;
import sev7n.render.AppWindow;
import sev7n.render.Framebuffer;
import sev7n.render.FrameLimiter;
import sev7n.render.SceneRenderer;
import sev7n.scene.Scene;

import org.lwjgl.glfw.GLFWDropCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/**
 * The Sev7n editor: a window running Dear ImGui with dockable panels.
 *
 * Each frame: poll input, update the running game, build the UI (panels render
 * the 3D views into framebuffers as they go), then draw the UI to the screen.
 */
public final class EditorApp implements MainMenu.Actions {

    /**
     * Options for automated checks: save a screenshot after some frames, optionally
     * selecting an entity, opening the script panel, or pressing Play first.
     */
    public record TestRun(Path screenshot, int frames, boolean play, String select, String guide, String tab) { }

    private static final Path LAYOUT_FILE = Path.of(
            sev7n.Platform.dataFolder().toString(), "editor-layout.ini");

    private final ImGuiImplGlfw imguiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imguiGl3 = new ImGuiImplGl3();
    private long window;
    private boolean layoutNeeded;
    private boolean pickDefaultTabs = false;
    private Path fileToOpen;
    private boolean quitConfirmed = false;
    private boolean showUnsavedDialog = false;

    private EditorContext ctx;
    private MainMenu menu;
    private HierarchyPanel hierarchy;
    private InspectorPanel inspector;
    private SceneViewPanel sceneView;
    private GameViewPanel gameView;
    private ConsolePanel console;
    private ProjectPanel projectPanel;
    private ScriptEditorPanel scripts;
    private EightsPanel eights;
    private GuidePanel guides;
    private ExportDialog exportDialog;
    private SettingsPanel settings;
    private SpriteEditorPanel spriteEditor;

    /** Opens the editor on a project folder (created if needed), optionally opening a script too. */
    public static void run(Path projectFolder, Path scriptToOpen, TestRun test) throws IOException {
        new EditorApp().start(projectFolder, scriptToOpen, test);
    }

    private void start(Path projectFolder, Path scriptToOpen, TestRun test) throws IOException {
        Project project = Project.openOrCreate(projectFolder);
        if (test == null) ProjectList.load(null).remember(project.root(), Sev7n.VERSION); // for the Hub's list
        window = AppWindow.open(project.name() + "  —  " + Sev7n.NAME, 1600, 900, test == null);
        installInputCallbacks(); // before ImGui, so ImGui chains to ours
        initImGui();

        SceneRenderer renderer = new SceneRenderer();
        ctx = new EditorContext(project, loadScene(project), renderer);
        createPanels();
        fileToOpen = scriptToOpen; // opened once the panel layout has settled
        ctx.log.system(Sev7n.NAME + " " + Sev7n.VERSION + "  ·  F5 to play  ·  Ctrl+S to save");

        loop(test);

        renderer.close();
        imguiGl3.shutdown();
        imguiGlfw.shutdown();
        ImNodes.destroyContext();
        ImGui.destroyContext();
        AppWindow.close(window);
        if (openHubAfter) {
            try {
                new ProcessBuilder(Sev7n.javaCommand()).start(); // a fresh Sev7n with no project opens the Hub
            } catch (IOException e) {
                System.err.println("Couldn't open the Hub: " + e.getMessage());
            }
        }
    }

    private boolean openHubAfter;

    @Override
    public void openHub() {
        openHubAfter = true;
        glfwSetWindowShouldClose(window, true); // asks about unsaved changes first
    }

    private Scene loadScene(Project project) {
        try {
            return project.loadScene();
        } catch (IOException | RuntimeException e) {
            // never overwrite a scene we couldn't read: start empty and say so
            Scene empty = new Scene("Main");
            System.err.println("Couldn't load " + project.scenePath() + ": " + e.getMessage());
            return empty;
        }
    }

    private void initImGui() throws IOException {
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        Files.createDirectories(LAYOUT_FILE.getParent());
        layoutNeeded = !Files.exists(LAYOUT_FILE);
        io.setIniFilename(LAYOUT_FILE.toString());
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
        io.setConfigWindowsMoveFromTitleBarOnly(true);

        float[] scaleX = new float[1];
        float[] scaleY = new float[1];
        glfwGetWindowContentScale(window, scaleX, scaleY);
        float scale = Math.max(1, scaleX[0]);
        EditorStyle.loadFonts(io, scale);
        EditorStyle.apply(scale);
        ImNodes.createContext();
        EightsPanel.applyStyle(scale);

        imguiGlfw.init(window, true);
        imguiGl3.init("#version 330 core");
    }

    private void createPanels() {
        menu = new MainMenu(ctx, this);
        hierarchy = new HierarchyPanel(ctx);
        inspector = new InspectorPanel(ctx);
        sceneView = new SceneViewPanel(ctx);
        gameView = new GameViewPanel(ctx);
        console = new ConsolePanel(ctx);
        projectPanel = new ProjectPanel(ctx);
        scripts = new ScriptEditorPanel(ctx.log, ctx.builtinNames);
        eights = new EightsPanel(ctx);
        guides = new GuidePanel(ctx.builtinNames);
        exportDialog = new ExportDialog(ctx);
        settings = new SettingsPanel(ctx);
        spriteEditor = new SpriteEditorPanel(ctx);
        ctx.spriteEditor = spriteEditor;
        ctx.scripts = scripts;
        ctx.eights = eights;
    }

    private void installInputCallbacks() {
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (gameView != null && gameView.hasKeyboard()) ctx.input.keyEvent(key, action);
        });
        glfwSetCharCallback(window, (w, codePoint) -> {
            if (scripts != null) scripts.queueCharacter(codePoint);
        });
        // drag files from Explorer onto the window to import them
        glfwSetDropCallback(window, (w, count, names) -> {
            List<Path> files = new ArrayList<>();
            for (int i = 0; i < count; i++) files.add(Path.of(GLFWDropCallback.getName(names, i)));
            ctx.importFiles(files);
        });
    }

    // ---------------------------------------------------------------- main loop

    private void loop(TestRun test) {
        double last = glfwGetTime();
        int frame = 0;
        while (true) {
            if (glfwWindowShouldClose(window)) {
                if (quitConfirmed || !ctx.anythingUnsaved()) break;
                glfwSetWindowShouldClose(window, false);
                showUnsavedDialog = true;
            }
            glfwPollEvents();
            double now = glfwGetTime();
            double dt = Math.min(now - last, 0.1); // cap big hitches (e.g. dragging the window)
            ctx.update(dt);
            last = now;
            applyEditorSettings();

            imguiGl3.newFrame();
            imguiGlfw.newFrame();
            ImGui.newFrame();
            ImGuizmo.beginFrame();
            drawUi();
            // an edit is finished once nothing is being dragged or typed into: then it becomes one undo step
            boolean editing = ImGui.isAnyItemActive() || ImGui.isMouseDown(0) || ImGui.isMouseDown(1) || ImGuizmo.isUsing();
            ctx.recordUndo(editing, dt);
            ImGui.render();

            Framebuffer.unbind();
            int[] width = new int[1];
            int[] height = new int[1];
            glfwGetFramebufferSize(window, width, height);
            glViewport(0, 0, width[0], height[0]);
            glClearColor(0.1f, 0.1f, 0.13f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            imguiGl3.renderDrawData(ImGui.getDrawData());

            frame++;
            if (test != null && runTestStep(test, frame)) break;
            glfwSwapBuffers(window);
            FrameLimiter.waitForNextFrame(ctx.editorSettings.maxFps, now);
        }
    }

    private Boolean appliedVsync; // null until first applied

    /** VSync and UI size can change any time in Settings. */
    private void applyEditorSettings() {
        EditorSettings settings = ctx.editorSettings;
        if (appliedVsync == null || appliedVsync != settings.vsync) {
            glfwSwapInterval(settings.vsync ? 1 : 0);
            appliedVsync = settings.vsync;
        }
        ImGui.getIO().setFontGlobalScale((float) settings.uiScale);
    }

    private void drawUi() {
        menu.draw();
        int dockspace = ImGui.dockSpaceOverViewport(ImGui.getMainViewport());
        if (pickDefaultTabs) {
            // a frame after building the layout, bring the main tabs to the front
            ImGui.setWindowFocus(ConsolePanel.TITLE);
            ImGui.setWindowFocus(SceneViewPanel.TITLE);
            pickDefaultTabs = false;
        }
        if (layoutNeeded) {
            DockLayout.build(dockspace);
            layoutNeeded = false;
            pickDefaultTabs = true;
        } else if (!pickDefaultTabs && fileToOpen != null) {
            ctx.openScript(fileToOpen, 0);
            fileToOpen = null;
        }

        hierarchy.draw();
        inspector.draw();
        sceneView.draw();
        gameView.draw();
        scripts.draw();
        eights.draw();
        console.draw();
        projectPanel.draw();
        guides.draw();
        exportDialog.draw();
        settings.draw();
        spriteEditor.draw();

        handleShortcuts();
        drawUnsavedDialog();
    }

    private void handleShortcuts() {
        ImGuiIO io = ImGui.getIO();
        boolean typing = io.getWantTextInput() || scripts.editorFocused();
        boolean gameHasKeys = gameView.hasKeyboard();

        if (ImGui.isKeyPressed(ImGuiKey.F5, false)) {
            if (io.getKeyCtrl()) ctx.playInWindow();
            else togglePlay();
        }
        if (ImGui.isKeyPressed(ImGuiKey.F1, false)) guides.toggle();
        if (io.getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.Comma, false)) openSettings();
        if (io.getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.S, false)) ctx.saveAll();
        if (typing || gameHasKeys || eights.focused()) return; // Eights handles its own Delete
        if (io.getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.Z, false)) {
            if (io.getKeyShift()) ctx.redo();
            else ctx.undo();
        }
        if (io.getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.Y, false)) ctx.redo();
        if (io.getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.D, false)) ctx.duplicate(ctx.selected());
        if (ImGui.isKeyPressed(ImGuiKey.Delete, false)) ctx.delete(ctx.selected());
    }

    private void drawUnsavedDialog() {
        if (showUnsavedDialog) {
            ImGui.openPopup("Unsaved changes");
            showUnsavedDialog = false;
        }
        if (!ImGui.beginPopupModal("Unsaved changes", ImGuiWindowFlags.AlwaysAutoResize)) return;
        ImGui.text("You have unsaved changes in " + ctx.project.name() + ".");
        ImGui.spacing();
        if (Widgets.primaryButton("Save and quit")) {
            ctx.saveAll();
            quit();
        }
        if (ImGui.button("Quit without saving", -1, 0)) quit();
        if (ImGui.button("Cancel", -1, 0)) ImGui.closeCurrentPopup();
        ImGui.endPopup();
    }

    /** For automated checks. Returns true when the test run is finished. */
    private boolean runTestStep(TestRun test, int frame) {
        // kept selected every frame: the test window may get stray clicks from whoever is using the PC
        if (frame >= 5 && test.select() != null) ctx.select(ctx.scene().find(test.select()));
        if (frame == 5 && test.guide() != null) {
            if (test.guide().equals("settings")) openSettings();
            else if (test.guide().equals("window")) ctx.playInWindow();
            else if (test.guide().equals("sprites")) spriteEditor.open(ctx.selected());
            else if (test.guide().startsWith("graph:")) eights.open(ctx.project.scriptsFolder().resolve(test.guide().substring(6) + ".8s"), 0);
            else openGuide(test.guide());
        }
        if (frame == 6 && test.tab() != null) ImGui.setWindowFocus(test.tab());
        if (test.play() && frame == 10) togglePlay();
        if (frame < test.frames()) return false;
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        if (width[0] == 0 && frame < test.frames() + 300) {
            glfwRestoreWindow(window); // Windows sometimes starts the test window minimized
            return false;
        }
        try {
            AppWindow.screenshot(window, test.screenshot());
        } catch (IOException e) {
            System.err.println("Screenshot failed: " + e.getMessage());
        }
        ctx.log.entries().forEach(entry -> System.out.println("[" + entry.level() + "] " + entry.text()));
        return true;
    }

    // ---------------------------------------------------------------- MainMenu.Actions

    /** Play also hands the keyboard to the Game view, so the game responds straight away. */
    @Override
    public void togglePlay() {
        ctx.togglePlay();
        if (ctx.playing()) gameView.requestFocus();
    }

    @Override
    public void quit() {
        quitConfirmed = true;
        glfwSetWindowShouldClose(window, true);
    }

    @Override
    public void resetLayout() {
        layoutNeeded = true;
    }

    @Override
    public void focusSelection() {
        sceneView.focusSelection();
    }

    @Override
    public void openGuide(String fileName) {
        guides.open(fileName);
    }

    @Override
    public void exportGame() {
        exportDialog.open();
    }

    @Override
    public void openSettings() {
        settings.open();
    }
}
