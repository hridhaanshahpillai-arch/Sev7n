package sev7n.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImString;
import sev7n.Sev7n;
import sev7n.game.Game;
import sev7n.project.Blender;
import sev7n.project.Exporter;
import sev7n.project.Project;
import sev7n.project.ProjectList;
import sev7n.render.AppWindow;
import sev7n.render.vulkan.Vulkan;
import sev7n.ui.Theme;

import java.awt.Color;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/**
 * The Sev7n Hub (like Unity Hub): the window Sev7n opens with when it isn't given a project.
 *
 *   Projects   every project you've opened or made: click one to open it, add one from a
 *              folder, show it in Explorer or take it off the list (its files stay)
 *   New        a new, empty project: its name and where it goes
 *   Installs   this Sev7n: its version and folder, what it can do on this PC (OpenGL, Vulkan,
 *              importing .blend files, exporting games), and where to get new versions
 *   Learn      the guides
 *
 * run() returns the project to open in the editor, or null if the Hub was closed.
 */
public final class HubApp {

    private static final String RELEASES_URL = "https://github.com/hridhaanshahpillai-arch/Sev7n/releases";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private enum Page { PROJECTS, NEW, INSTALLS, LEARN }

    private final ImGuiImplGlfw imguiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imguiGl3 = new ImGuiImplGl3();
    private long window;
    private Page page = Page.PROJECTS;
    private ProjectList projects;
    private Path chosen;
    private float scale = 1;

    // projects page
    private final ImString search = new ImString(128);
    private ProjectList.Entry menuFor;

    // new project page
    private final ImString newName = new ImString(128);
    private final ImString newLocation = new ImString(1024);
    private String newError = "";

    // installs page: checked in the background, since looking for Vulkan takes a moment
    private volatile Vulkan.Support vulkan;
    private volatile String blender;
    private volatile String exporter;

    private GuidePanel guides;
    private volatile Runnable pending; // work from the folder chooser's thread, done on the Hub's

    /** Shows the Hub until a project is picked (returned) or the window is closed (null). 'test' saves a screenshot instead. */
    public static Path run(EditorApp.TestRun test) throws IOException {
        return new HubApp().start(test);
    }

    private Path start(EditorApp.TestRun test) throws IOException {
        projects = ProjectList.load(Sev7n.home().resolve("projects"));
        window = AppWindow.open(Sev7n.NAME + " Hub", 1180, 720, false);
        initImGui();
        guides = new GuidePanel(Game.builtinNames());
        newName.set(uniqueName("My Game"));
        newLocation.set(defaultLocation().toString());
        if (test != null && test.tab() != null) page = Page.valueOf(test.tab().toUpperCase(Locale.ROOT));
        checkInstall();

        int frame = 0;
        while (!glfwWindowShouldClose(window) && chosen == null) {
            glfwPollEvents();
            Runnable work = pending;
            if (work != null) {
                pending = null;
                work.run();
            }
            imguiGl3.newFrame();
            imguiGlfw.newFrame();
            ImGui.newFrame();
            draw();
            guides.draw();
            ImGui.render();
            int[] width = new int[1], height = new int[1];
            glfwGetFramebufferSize(window, width, height);
            glViewport(0, 0, width[0], height[0]);
            glClearColor(0.1f, 0.1f, 0.13f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            imguiGl3.renderDrawData(ImGui.getDrawData());
            frame++;
            if (test != null && frame >= test.frames()) {
                AppWindow.screenshot(window, test.screenshot());
                break;
            }
            glfwSwapBuffers(window);
        }

        imguiGl3.shutdown();
        imguiGlfw.shutdown();
        ImGui.destroyContext();
        AppWindow.close(window);
        if (chosen != null) projects.remember(chosen, Sev7n.VERSION);
        return chosen;
    }

    private void initImGui() {
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(null); // nothing to remember about the Hub's layout
        float[] scaleX = new float[1], scaleY = new float[1];
        glfwGetWindowContentScale(window, scaleX, scaleY);
        float dpi = Math.max(1, scaleX[0]);
        EditorStyle.loadFonts(io, dpi);
        EditorStyle.apply(dpi);
        imguiGlfw.init(window, true);
        imguiGl3.init("#version 330 core");
    }

    // ---------------------------------------------------------------- layout

    private void draw() {
        scale = ImGui.getFontSize() / 15f;
        ImVec2 size = ImGui.getMainViewport().getSize();
        ImGui.setNextWindowPos(0, 0);
        ImGui.setNextWindowSize(size.x, size.y);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, EditorStyle.pack(Theme.EDITOR));
        ImGui.begin("##hub", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize
                | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoSavedSettings);
        ImGui.popStyleColor();
        ImGui.popStyleVar(2);

        drawSidebar(size.y);
        ImGui.sameLine(0, 0);
        ImGui.pushStyleColor(ImGuiCol.ChildBg, EditorStyle.pack(Theme.EDITOR));
        ImGui.beginChild("##page", 0, 0, false);
        ImGui.popStyleColor();
        ImGui.pushStyleColor(ImGuiCol.FrameBg, EditorStyle.pack(Theme.PANEL)); // text fields stand out from the page
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 10 * scale, 7 * scale);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 6 * scale);
        ImGui.setCursorPos(36 * scale, 28 * scale);
        ImGui.beginGroup();
        switch (page) {
            case PROJECTS -> drawProjects();
            case NEW -> drawNewProject();
            case INSTALLS -> drawInstalls();
            case LEARN -> drawLearn();
        }
        ImGui.endGroup();
        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
        ImGui.endChild();
        ImGui.end();
    }

    private void drawSidebar(float height) {
        float width = 230 * scale;
        ImGui.pushStyleColor(ImGuiCol.ChildBg, EditorStyle.pack(Theme.PANEL));
        ImGui.beginChild("##sidebar", width, height, false);
        ImGui.popStyleColor();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImVec2 origin = ImGui.getWindowPos();

        // the logo: "Sev7n" with a purple 7, and "Hub"
        ImGui.pushFont(EditorStyle.titleFont);
        float x = origin.x + 26 * scale;
        float y = origin.y + 26 * scale;
        for (String part : new String[] {"Sev", "7", "n"}) {
            draw.addText(x, y, EditorStyle.pack(part.equals("7") ? Theme.ACCENT : Theme.TEXT), part);
            x += ImGui.calcTextSize(part).x;
        }
        float logoHeight = ImGui.getTextLineHeight();
        ImGui.popFont();
        draw.addText(x + 8 * scale, y + logoHeight - ImGui.getTextLineHeight() - 3 * scale, EditorStyle.pack(Theme.TEXT_MUTED), "Hub");

        ImGui.setCursorPos(14 * scale, 26 * scale + logoHeight + 30 * scale);
        navItem("Projects", Page.PROJECTS, width);
        navItem("Installs", Page.INSTALLS, width);
        navItem("Learn", Page.LEARN, width);

        String version = Sev7n.NAME + " " + Sev7n.VERSION;
        draw.addText(origin.x + 26 * scale, origin.y + height - 40 * scale, EditorStyle.pack(Theme.TEXT_FAINT), version);
        ImGui.endChild();
    }

    private void navItem(String label, Page target, float width) {
        ImGui.setCursorPosX(14 * scale);
        boolean active = page == target || (target == Page.PROJECTS && page == Page.NEW);
        ImVec2 at = ImGui.getCursorScreenPos();
        float w = width - 28 * scale, h = 38 * scale;
        boolean clicked = ImGui.invisibleButton("##nav" + label, w, h);
        boolean hovered = ImGui.isItemHovered();
        ImDrawList draw = ImGui.getWindowDrawList();
        if (active || hovered) draw.addRectFilled(at.x, at.y, at.x + w, at.y + h, EditorStyle.pack(active ? Theme.SELECTION : Theme.SURFACE), 6 * scale);
        if (active) draw.addRectFilled(at.x, at.y + 8 * scale, at.x + 3 * scale, at.y + h - 8 * scale, EditorStyle.pack(Theme.ACCENT), 2);
        draw.addText(at.x + 16 * scale, at.y + (h - ImGui.getTextLineHeight()) / 2, EditorStyle.pack(active ? Theme.TEXT : Theme.TEXT_MUTED), label);
        if (clicked) page = target;
        ImGui.dummy(0, 4 * scale);
    }

    private void heading(String title) {
        ImGui.pushFont(EditorStyle.titleFont);
        ImGui.text(title);
        ImGui.popFont();
    }

    /** Space for the page's content: the window's width less the margins. */
    private float pageWidth() {
        return ImGui.getWindowWidth() - 72 * scale;
    }

    // ---------------------------------------------------------------- projects

    private void drawProjects() {
        float width = pageWidth();
        float top = ImGui.getCursorPosY();
        heading("Projects");
        float buttonsY = top + 4 * scale;
        ImGui.setCursorPos(36 * scale + width - 290 * scale, buttonsY);
        if (button("Add", 120 * scale, 34 * scale, false)) {
            FilePicker.chooseFolder("Add a project folder", Sev7n.home().resolve("projects"), folder -> pending = () -> addExisting(folder));
        }
        ImGui.sameLine(0, 10 * scale);
        if (button("New project", 160 * scale, 34 * scale, true)) {
            newName.set(uniqueName("My Game"));
            newError = "";
            page = Page.NEW;
        }
        ImGui.setCursorPos(36 * scale, top + 60 * scale);

        ImGui.setNextItemWidth(320 * scale);
        ImGui.inputTextWithHint("##search", "Search projects", search);
        ImGui.spacing();
        ImGui.spacing();

        // column headings
        ImVec2 at = ImGui.getCursorScreenPos();
        ImDrawList draw = ImGui.getWindowDrawList();
        int faint = EditorStyle.pack(Theme.TEXT_FAINT);
        draw.addText(at.x + 16 * scale, at.y, faint, "NAME");
        draw.addText(at.x + width * 0.58f, at.y, faint, "MODIFIED");
        draw.addText(at.x + width * 0.78f, at.y, faint, "SEV7N VERSION");
        ImGui.dummy(width, ImGui.getTextLineHeight() + 6 * scale);
        draw.addLine(at.x, at.y + ImGui.getTextLineHeight() + 4 * scale, at.x + width, at.y + ImGui.getTextLineHeight() + 4 * scale, EditorStyle.pack(Theme.SURFACE), 1);

        String query = search.get().trim().toLowerCase(Locale.ROOT);
        List<ProjectList.Entry> list = projects.entries().stream()
                .filter(e -> query.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(query) || e.folder().toString().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        ImGui.pushStyleColor(ImGuiCol.ChildBg, EditorStyle.pack(Theme.EDITOR));
        ImGui.beginChild("##projects", width, 0, false);
        ImGui.popStyleColor();
        if (list.isEmpty()) {
            ImGui.dummy(0, 40 * scale);
            ImGui.textDisabled(query.isEmpty() ? "No projects yet. Make one with New project, or Add a folder you already have." : "No projects match \"" + search.get() + "\".");
        }
        for (ProjectList.Entry entry : list) projectRow(entry, width);
        ImGui.endChild();
        projectMenu();
    }

    private void projectRow(ProjectList.Entry entry, float width) {
        float h = 58 * scale;
        ImVec2 at = ImGui.getCursorScreenPos();
        ImGui.pushID(entry.folder().toString());
        boolean clicked = ImGui.invisibleButton("##row", width - 48 * scale, h);
        boolean hovered = ImGui.isItemHovered();
        ImGui.sameLine(0, 0);
        boolean menu = ImGui.invisibleButton("##more", 48 * scale, h);
        boolean menuHovered = ImGui.isItemHovered();
        ImGui.popID();
        boolean exists = entry.exists();

        ImDrawList draw = ImGui.getWindowDrawList();
        if (hovered || menuHovered) draw.addRectFilled(at.x, at.y, at.x + width, at.y + h, EditorStyle.pack(Theme.SURFACE), 8 * scale);
        float line = ImGui.getTextLineHeight();
        ImGui.pushFont(EditorStyle.boldFont);
        draw.addText(at.x + 16 * scale, at.y + 10 * scale, EditorStyle.pack(exists ? Theme.TEXT : Theme.TEXT_FAINT), entry.name());
        ImGui.popFont();
        draw.addText(at.x + 16 * scale, at.y + 14 * scale + line, EditorStyle.pack(exists ? Theme.TEXT_FAINT : Theme.ERROR),
                fit(exists ? entry.folder().toString() : "Folder not found: " + entry.folder(), width * 0.58f - 32 * scale));
        float middle = at.y + (h - line) / 2;
        draw.addText(at.x + width * 0.58f, middle, EditorStyle.pack(Theme.TEXT_MUTED), exists ? ago(entry.modified()) : "");
        String version = entry.version().isEmpty() ? "" : entry.version();
        if (!version.isEmpty()) {
            boolean current = version.equals(Sev7n.VERSION);
            draw.addText(at.x + width * 0.78f, middle, EditorStyle.pack(current ? Theme.TEXT_MUTED : Theme.WARNING), version);
        }
        // three dots for the menu
        float dotsX = at.x + width - 26 * scale;
        for (int i = -1; i <= 1; i++) {
            draw.addCircleFilled(dotsX + i * 6 * scale, at.y + h / 2, 2.2f * scale, EditorStyle.pack(menuHovered ? Theme.TEXT : Theme.TEXT_FAINT));
        }

        if (clicked && exists) chosen = entry.folder();
        if (menu || (clicked && !exists)) {
            menuFor = entry;
            ImGui.openPopup("##projectMenu");
        }
    }

    private void projectMenu() {
        if (!ImGui.beginPopup("##projectMenu")) return;
        if (menuFor != null) {
            ImGui.textDisabled(menuFor.name());
            ImGui.separator();
            if (menuFor.exists()) {
                if (ImGui.menuItem("Open")) chosen = menuFor.folder();
                if (ImGui.menuItem("Show in " + sev7n.Platform.fileManager())) ProjectPanel.showInExplorer(menuFor.folder());
            }
            if (ImGui.menuItem("Remove from list")) {
                projects.forget(menuFor.folder());
                menuFor = null;
            }
            if (menuFor != null && menuFor.exists()) ImGui.textDisabled("(removing keeps the files)");
        }
        ImGui.endPopup();
    }

    private void addExisting(Path folder) {
        if (!Files.isRegularFile(folder.resolve("main.scene"))) {
            Path root = Project.findRoot(folder.resolve("x"));
            if (root == null) return; // not a project; nothing to add
            folder = root;
        }
        projects.remember(folder, "");
    }

    // ---------------------------------------------------------------- new project

    private void drawNewProject() {
        float width = pageWidth();
        heading("New project");
        ImGui.textDisabled("An empty project with a camera: give it a name and choose where it goes.");
        ImGui.dummy(0, 22 * scale);

        float fieldWidth = Math.min(560 * scale, width);
        ImGui.text("Project name");
        ImGui.setNextItemWidth(fieldWidth);
        ImGui.inputText("##name", newName);
        ImGui.dummy(0, 8 * scale);
        ImGui.text("Location");
        ImGui.setNextItemWidth(fieldWidth - 110 * scale);
        ImGui.inputText("##location", newLocation);
        ImGui.sameLine(0, 10 * scale);
        if (button("Browse...", 100 * scale, ImGui.getFrameHeight(), false)) {
            FilePicker.chooseFolder("Where should the project go?", Path.of(newLocation.get()), folder -> pending = () -> newLocation.set(folder.toString()));
        }
        Path target = newTarget();
        ImGui.dummy(0, 4 * scale);
        ImGui.textDisabled(target == null ? "" : "It will be made in " + target);
        if (!newError.isEmpty()) ImGui.textColored(EditorStyle.pack(Theme.ERROR), newError);
        ImGui.dummy(0, 16 * scale);
        if (button("Cancel", 120 * scale, 38 * scale, false)) page = Page.PROJECTS;
        ImGui.sameLine(0, 10 * scale);
        if (button("Create project", 180 * scale, 38 * scale, true)) createProject(target);
    }
    /** Where the new project would go, or null if the name or folder isn't usable. */
    private Path newTarget() {
        String name = newName.get().trim();
        if (name.isEmpty() || newLocation.get().isBlank()) return null;
        try {
            return Path.of(newLocation.get().trim()).resolve(name).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null; // characters a folder can't have
        }
    }

    private void createProject(Path target) {
        if (target == null) {
            newError = "Give the project a name and a location.";
            return;
        }
        try {
            Project.createNew(target);
            chosen = target;
        } catch (IOException | RuntimeException e) {
            newError = "Couldn't make the project: " + e.getMessage();
        }
    }

    /** "My Game", or "My Game 2"... if that's taken in the default location. */
    private String uniqueName(String base) {
        Path folder = defaultLocation();
        String name = base;
        for (int i = 2; Files.exists(folder.resolve(name)); i++) name = base + " " + i;
        return name;
    }

    /** Sev7n's own projects folder if it can be written to, else Documents\Sev7n Projects. */
    private static Path defaultLocation() {
        Path own = Sev7n.home().resolve("projects");
        if (Files.isDirectory(own) && Files.isWritable(own)) return own;
        return Path.of(System.getProperty("user.home"), "Documents", "Sev7n Projects");
    }

    // ---------------------------------------------------------------- installs

    private void checkInstall() {
        Thread check = new Thread(() -> {
            blender = Blender.find().map(Path::toString).orElse(null);
            exporter = Exporter.jpackage() == null ? null : Exporter.jpackage().toString();
            vulkan = Vulkan.check();
        }, "hub-install-check");
        check.setDaemon(true);
        check.start();
    }

    private void drawInstalls() {
        float width = Math.min(pageWidth(), 820 * scale);
        heading("Installs");
        ImGui.textDisabled("The Sev7n on this PC, and what it can do here.");
        ImGui.dummy(0, 18 * scale);

        // this install
        ImVec2 at = ImGui.getCursorScreenPos();
        float h = 96 * scale;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(at.x, at.y, at.x + width, at.y + h, EditorStyle.pack(Theme.PANEL), 10 * scale);
        ImGui.pushFont(EditorStyle.headingFont);
        draw.addText(at.x + 22 * scale, at.y + 18 * scale, EditorStyle.pack(Theme.TEXT), Sev7n.NAME + " " + Sev7n.VERSION);
        ImGui.popFont();
        draw.addText(at.x + 22 * scale, at.y + 56 * scale, EditorStyle.pack(Theme.TEXT_FAINT), Sev7n.home().toString());
        draw.addCircleFilled(at.x + width - 130 * scale, at.y + 30 * scale, 5 * scale, EditorStyle.pack(Theme.SUCCESS));
        draw.addText(at.x + width - 118 * scale, at.y + 22 * scale, EditorStyle.pack(Theme.SUCCESS), "Installed");
        ImGui.dummy(width, h);
        ImGui.dummy(0, 8 * scale);
        if (button("Show in " + sev7n.Platform.fileManager(), 170 * scale, 34 * scale, false)) ProjectPanel.showInExplorer(Sev7n.home());
        ImGui.sameLine(0, 10 * scale);
        if (button("Get new versions", 180 * scale, 34 * scale, true)) browse(RELEASES_URL);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Opens Sev7n's Releases page on GitHub");

        ImGui.dummy(0, 26 * scale);
        ImGui.pushFont(EditorStyle.boldFont);
        ImGui.text("Modules");
        ImGui.popFont();
        ImGui.dummy(0, 6 * scale);
        module("OpenGL", "Draws the editor and games. Every PC that runs Sev7n has it.", true, null, null);
        Vulkan.Support v = vulkan;
        module("Vulkan", v == null ? "Checking..." : v.available() ? "Games can draw with Vulkan: " + v.detail() : "Not on this PC (" + v.detail() + "). Games use OpenGL.",
                v != null && v.available(), null, null);
        String b = blender;
        module("Blender (.blend and .fbx import)", b != null ? "Found: " + b : "Not installed. Sev7n uses Blender to bring in .blend and .fbx models.",
                b != null, b == null ? "Download Blender" : null, Blender.DOWNLOAD_URL);
        String e = exporter;
        module("Game export", e != null ? "Ready: File > Export Game makes a game that runs on its own (" + switch (sev7n.Platform.OS) {
                    case WINDOWS -> ".exe";
                    case MAC -> ".app";
                    case LINUX -> "Linux program";
                } + ")" : "Needs the full Java (with jpackage) to export games.",
                e != null, null, null);
    }

    private void module(String name, String detail, boolean ready, String actionLabel, String url) {
        float width = Math.min(pageWidth(), 820 * scale);
        float h = 60 * scale;
        ImVec2 at = ImGui.getCursorScreenPos();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(at.x, at.y, at.x + width, at.y + h, EditorStyle.pack(Theme.PANEL), 8 * scale);
        draw.addCircleFilled(at.x + 22 * scale, at.y + h / 2, 5 * scale, EditorStyle.pack(ready ? Theme.SUCCESS : Theme.TEXT_FAINT));
        ImGui.pushFont(EditorStyle.boldFont);
        draw.addText(at.x + 40 * scale, at.y + 10 * scale, EditorStyle.pack(Theme.TEXT), name);
        ImGui.popFont();
        draw.addText(at.x + 40 * scale, at.y + 32 * scale, EditorStyle.pack(Theme.TEXT_FAINT), detail);
        ImGui.dummy(width, h);
        if (actionLabel != null) {
            ImVec2 after = ImGui.getCursorScreenPos();
            ImGui.setCursorScreenPos(at.x + width - 180 * scale, at.y + (h - 30 * scale) / 2);
            if (button(actionLabel, 164 * scale, 30 * scale, false)) browse(url);
            ImGui.setCursorScreenPos(after.x, after.y);
        }
        ImGui.dummy(0, 6 * scale);
    }

    // ---------------------------------------------------------------- learn

    /** A guide on the Learn page: its file, which section it's in, what it's about, and its picture. */
    private record Lesson(String file, String section, String about, String icon) { }

    private static final List<Lesson> LESSONS = List.of(
            new Lesson("README.md", "Start here", "A tour of the editor, your first object and your first script.", "book"),
            new Lesson("SEVEN.md", "Start here", "The scripting language: variables, functions and every command.", "code"),
            new Lesson("EIGHTS.md", "Start here", "Visual scripting: connect nodes instead of typing code.", "nodes"),
            new Lesson("WORLDS.md", "Worlds", "Mouse-look, shooting, terrain and big open worlds.", "mountain"),
            new Lesson("SCENES.md", "Worlds", "Levels, saving and loading, prefabs and fixedUpdate.", "layers"),
            new Lesson("PHYSICS.md", "Worlds", "Rigidbodies, colliders, forces, raycasts and triggers.", "ball"),
            new Lesson("2D.md", "Worlds", "Sprites, sprite sheets, animation and tilemaps.", "grid"),
            new Lesson("ASSETS.md", "Worlds", "Bring in models, pictures, sounds and asset packs.", "box"),
            new Lesson("ANIMATION.md", "Characters", "Animated 3D characters, smooth blending and tweens.", "run"),
            new Lesson("NPC.md", "Characters", "Characters that see, hear, chase, remember and keep routines.", "eye"),
            new Lesson("RAGDOLLS.md", "Characters", "Bodies that go limp, tumble down stairs and get back up.", "figure"),
            new Lesson("EFFECTS.md", "Characters", "Fire, smoke, sparks, blood, magic and more.", "spark"),
            new Lesson("HUD.md", "Your game", "On-screen text, health bars, menus, holograms and sound.", "screen"),
            new Lesson("SETTINGS.md", "Your game", "Graphics, Vulkan, frame rate and Live Mode.", "gear"),
            new Lesson("EXPORT.md", "Your game", "Turn your project into a game anyone can play.", "rocket"));

    private static final List<String> SECTIONS = List.of("Start here", "Worlds", "Characters", "Your game");
    private static final Color[] SECTION_COLORS = {Theme.ACCENT, new Color(0x8BE9FD), new Color(0x50FA7B), new Color(0xFF79C6)};
    private final ImString learnSearch = new ImString(64);

    private void drawLearn() {
        float width = pageWidth();
        float top = ImGui.getCursorPosY();
        heading("Learn");
        ImGui.textDisabled("Everything in Sev7n, one guide at a time. They're in the editor too: press F1.");
        ImGui.setCursorPos(36 * scale + width - 280 * scale, top + 6 * scale);
        ImGui.setNextItemWidth(280 * scale);
        ImGui.inputTextWithHint("##learnSearch", "Search guides", learnSearch);
        ImGui.setCursorPos(36 * scale, top + 72 * scale);

        String query = learnSearch.get().trim().toLowerCase(Locale.ROOT);
        ImGui.pushStyleColor(ImGuiCol.ChildBg, EditorStyle.pack(Theme.EDITOR));
        ImGui.beginChild("##lessons", width + 20 * scale, 0, false);
        ImGui.popStyleColor();
        if (query.isEmpty()) startHere(width);

        float gap = 16 * scale;
        int columns = Math.max(1, (int) ((width + gap) / (262 * scale + gap)));
        float cardWidth = (width - gap * (columns - 1)) / columns;
        boolean any = false;
        for (int s = 0; s < SECTIONS.size(); s++) {
            String section = SECTIONS.get(s);
            List<Lesson> lessons = LESSONS.stream().filter(l -> l.section().equals(section))
                    .filter(l -> query.isEmpty() || title(l).toLowerCase(Locale.ROOT).contains(query) || l.about().toLowerCase(Locale.ROOT).contains(query))
                    .toList();
            if (lessons.isEmpty()) continue;
            any = true;
            sectionHeading(section, SECTION_COLORS[s], lessons.size());
            for (int i = 0; i < lessons.size(); i++) {
                if (i % columns != 0) ImGui.sameLine(0, gap);
                lessonCard(lessons.get(i), cardWidth, SECTION_COLORS[s]);
                if (i % columns == columns - 1 || i == lessons.size() - 1) ImGui.dummy(0, 6 * scale);
            }
            ImGui.dummy(0, 10 * scale);
        }
        if (!any) ImGui.textDisabled("No guides match \"" + learnSearch.get() + "\".");
        ImGui.dummy(0, 20 * scale);
        ImGui.endChild();
    }

    /** The banner at the top: where someone new should begin. */
    private void startHere(float width) {
        float h = 150 * scale;
        ImVec2 at = ImGui.getCursorScreenPos();
        ImDrawList draw = ImGui.getWindowDrawList();
        int left = EditorStyle.pack(new Color(0x4B3A6E)), right = EditorStyle.pack(new Color(0x2A3550));
        draw.addRectFilledMultiColor(at.x, at.y, at.x + width, at.y + h, left, right, right, left);
        draw.addRect(at.x, at.y, at.x + width, at.y + h, EditorStyle.pack(EditorStyle.withAlpha(Theme.ACCENT, 90)), 0, 0, 1.5f * scale);
        // a big faint 7 on the right
        ImGui.pushFont(EditorStyle.titleFont);
        float sevenSize = ImGui.getFontSize() * 4.2f;
        draw.addText(EditorStyle.titleFont, (int) sevenSize, at.x + width - sevenSize * 0.9f, at.y + h / 2 - sevenSize * 0.6f,
                EditorStyle.pack(EditorStyle.withAlpha(Theme.ACCENT, 60)), "7");
        draw.addText(at.x + 28 * scale, at.y + 24 * scale, EditorStyle.pack(Theme.TEXT), "New to Sev7n?");
        ImGui.popFont();
        draw.addText(at.x + 28 * scale, at.y + 62 * scale, EditorStyle.pack(new Color(0xD8D8E6)),
                "Start with the tour, then learn Seven: you'll have something moving in minutes.");
        ImGui.dummy(width, h);
        ImVec2 after = ImGui.getCursorScreenPos();
        ImGui.setCursorScreenPos(at.x + 28 * scale, at.y + h - 52 * scale);
        if (button("Take the tour", 160 * scale, 34 * scale, true)) guides.open("README.md");
        ImGui.sameLine(0, 10 * scale);
        if (button("Learn Seven", 140 * scale, 34 * scale, false)) guides.open("SEVEN.md");
        ImGui.setCursorScreenPos(after.x, after.y + 22 * scale);
    }

    private void sectionHeading(String section, Color color, int count) {
        ImVec2 at = ImGui.getCursorScreenPos();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addCircleFilled(at.x + 5 * scale, at.y + ImGui.getTextLineHeight() / 2 + 1, 4 * scale, EditorStyle.pack(color));
        ImGui.setCursorScreenPos(at.x + 18 * scale, at.y);
        ImGui.pushFont(EditorStyle.boldFont);
        ImGui.text(section);
        ImGui.popFont();
        ImGui.sameLine(0, 8 * scale);
        ImGui.textDisabled(count + (count == 1 ? " guide" : " guides"));
        ImGui.dummy(0, 4 * scale);
    }

    private void lessonCard(Lesson lesson, float w, Color color) {
        float h = 92 * scale;
        ImVec2 at = ImGui.getCursorScreenPos();
        boolean clicked = ImGui.invisibleButton("##lesson" + lesson.file(), w, h);
        boolean hovered = ImGui.isItemHovered();
        float lift = hovered ? 2 * scale : 0;
        ImDrawList draw = ImGui.getWindowDrawList();
        float y = at.y - lift;
        if (hovered) draw.addRectFilled(at.x + 2, at.y + 4, at.x + w + 2, at.y + h + 4, EditorStyle.pack(new Color(0, 0, 0, 70)), 12 * scale);
        draw.addRectFilled(at.x, y, at.x + w, y + h, EditorStyle.pack(hovered ? Theme.SURFACE : Theme.PANEL), 12 * scale);
        if (hovered) draw.addRect(at.x, y, at.x + w, y + h, EditorStyle.pack(EditorStyle.withAlpha(color, 160)), 12 * scale, 0, 1.5f * scale);

        // the picture: a tinted tile with a drawn icon
        float tile = 52 * scale, tx = at.x + 18 * scale, ty = y + (h - tile) / 2;
        draw.addRectFilled(tx, ty, tx + tile, ty + tile, EditorStyle.pack(EditorStyle.withAlpha(color, hovered ? 70 : 42)), 10 * scale);
        icon(draw, lesson.icon(), tx + tile / 2, ty + tile / 2, tile * 0.3f, EditorStyle.pack(color));

        float textX = tx + tile + 16 * scale, textWidth = at.x + w - textX - 14 * scale;
        ImGui.pushFont(EditorStyle.boldFont);
        draw.addText(textX, y + 18 * scale, EditorStyle.pack(Theme.TEXT), title(lesson));
        ImGui.popFont();
        List<String> lines = wrap(lesson.about(), textWidth, 2);
        for (int i = 0; i < lines.size(); i++) {
            draw.addText(textX, y + 44 * scale + i * ImGui.getTextLineHeight(), EditorStyle.pack(Theme.TEXT_MUTED), lines.get(i));
        }
        if (clicked) guides.open(lesson.file());
    }

    private static String title(Lesson lesson) {
        for (GuidePanel.Guide guide : GuidePanel.GUIDES) {
            if (guide.file().getFileName().toString().equals(lesson.file())) return guide.title();
        }
        return lesson.file();
    }

    /** Splits text into at most 'maxLines' lines that fit 'width' (the last one ends in "..." if it had to be cut). */
    private static List<String> wrap(String text, float width, int maxLines) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String attempt = line.isEmpty() ? word : line + " " + word;
            if (ImGui.calcTextSize(attempt).x <= width || line.isEmpty()) {
                line = new StringBuilder(attempt);
            } else {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        if (lines.size() > maxLines) {
            List<String> cut = new java.util.ArrayList<>(lines.subList(0, maxLines));
            cut.set(maxLines - 1, fit(cut.get(maxLines - 1) + " ...", width));
            return cut;
        }
        return lines;
    }

    /** A small line drawing for each guide. */
    private void icon(ImDrawList d, String kind, float x, float y, float s, int c) {
        float t = 2 * scale;
        switch (kind) {
            case "book" -> {
                d.addRect(x - s, y - s * 0.8f, x, y + s * 0.8f, c, 2 * scale, 0, t);
                d.addRect(x, y - s * 0.8f, x + s, y + s * 0.8f, c, 2 * scale, 0, t);
            }
            case "code" -> {
                d.addLine(x - s * 0.35f, y - s * 0.7f, x - s, y, c, t);
                d.addLine(x - s, y, x - s * 0.35f, y + s * 0.7f, c, t);
                d.addLine(x + s * 0.35f, y - s * 0.7f, x + s, y, c, t);
                d.addLine(x + s, y, x + s * 0.35f, y + s * 0.7f, c, t);
                d.addLine(x + s * 0.15f, y - s * 0.8f, x - s * 0.15f, y + s * 0.8f, c, t);
            }
            case "nodes" -> {
                d.addRectFilled(x - s, y - s * 0.8f, x - s * 0.3f, y - s * 0.2f, c, 2 * scale);
                d.addRectFilled(x + s * 0.3f, y + s * 0.2f, x + s, y + s * 0.8f, c, 2 * scale);
                d.addBezierCubic(x - s * 0.3f, y - s * 0.5f, x + s * 0.2f, y - s * 0.5f, x - s * 0.2f, y + s * 0.5f, x + s * 0.3f, y + s * 0.5f, c, t, 16);
            }
            case "mountain" -> {
                d.addTriangle(x - s, y + s * 0.7f, x - s * 0.2f, y - s * 0.6f, x + s * 0.5f, y + s * 0.7f, c, t);
                d.addTriangle(x, y + s * 0.7f, x + s * 0.5f, y - s * 0.1f, x + s, y + s * 0.7f, c, t);
                d.addCircleFilled(x + s * 0.65f, y - s * 0.65f, s * 0.2f, c);
            }
            case "layers" -> {
                for (int i = 0; i < 3; i++) {
                    float o = (i - 1) * s * 0.45f;
                    d.addQuad(x, y - s * 0.45f + o, x + s, y + o, x, y + s * 0.45f + o, x - s, y + o, c, t);
                }
            }
            case "ball" -> {
                d.addCircle(x, y - s * 0.25f, s * 0.55f, c, 20, t);
                d.addLine(x - s, y + s * 0.75f, x + s, y + s * 0.75f, c, t);
                d.addLine(x - s * 0.9f, y - s * 0.9f, x - s * 0.6f, y - s * 0.5f, c, t);
            }
            case "grid" -> {
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        if ((i + j) % 2 == 0) d.addRectFilled(x - s + i * s * 0.67f, y - s + j * s * 0.67f, x - s + (i + 1) * s * 0.67f - 2, y - s + (j + 1) * s * 0.67f - 2, c, 1);
                    }
                }
            }
            case "box" -> {
                d.addQuad(x, y - s, x + s, y - s * 0.5f, x, y, x - s, y - s * 0.5f, c, t);
                d.addLine(x - s, y - s * 0.5f, x - s, y + s * 0.5f, c, t);
                d.addLine(x + s, y - s * 0.5f, x + s, y + s * 0.5f, c, t);
                d.addLine(x, y, x, y + s, c, t);
                d.addLine(x - s, y + s * 0.5f, x, y + s, c, t);
                d.addLine(x + s, y + s * 0.5f, x, y + s, c, t);
            }
            case "run" -> {
                d.addCircleFilled(x + s * 0.3f, y - s * 0.8f, s * 0.22f, c);
                d.addLine(x + s * 0.2f, y - s * 0.5f, x - s * 0.1f, y + s * 0.2f, c, t);
                d.addLine(x - s * 0.1f, y + s * 0.2f, x + s * 0.5f, y + s * 0.9f, c, t);
                d.addLine(x - s * 0.1f, y + s * 0.2f, x - s * 0.7f, y + s * 0.7f, c, t);
                d.addLine(x + s * 0.1f, y - s * 0.3f, x + s * 0.8f, y - s * 0.1f, c, t);
                d.addLine(x + s * 0.1f, y - s * 0.3f, x - s * 0.6f, y - s * 0.4f, c, t);
            }
            case "eye" -> {
                d.addBezierQuadratic(x - s, y, x, y - s * 1.1f, x + s, y, c, t, 16);
                d.addBezierQuadratic(x - s, y, x, y + s * 1.1f, x + s, y, c, t, 16);
                d.addCircleFilled(x, y, s * 0.3f, c);
            }
            case "figure" -> {
                d.addCircleFilled(x - s * 0.6f, y + s * 0.1f, s * 0.22f, c);
                d.addLine(x - s * 0.35f, y + s * 0.25f, x + s * 0.4f, y + s * 0.5f, c, t);
                d.addLine(x + s * 0.4f, y + s * 0.5f, x + s, y + s * 0.1f, c, t);
                d.addLine(x + s * 0.4f, y + s * 0.5f, x + s * 0.9f, y + s * 0.85f, c, t);
                d.addLine(x, y + s * 0.35f, x + s * 0.1f, y - s * 0.3f, c, t);
                d.addLine(x - s, y + s * 0.85f, x + s, y + s * 0.85f, c, t);
            }
            case "spark" -> {
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float inner = s * 0.3f, outer = i % 2 == 0 ? s : s * 0.6f;
                    d.addLine(x + (float) Math.cos(a) * inner, y + (float) Math.sin(a) * inner, x + (float) Math.cos(a) * outer, y + (float) Math.sin(a) * outer, c, t);
                }
            }
            case "screen" -> {
                d.addRect(x - s, y - s * 0.7f, x + s, y + s * 0.6f, c, 3 * scale, 0, t);
                d.addRectFilled(x - s * 0.7f, y - s * 0.4f, x, y - s * 0.2f, c, 1);
                d.addRectFilled(x - s * 0.7f, y, x + s * 0.5f, y + s * 0.15f, c, 1);
                d.addLine(x - s * 0.4f, y + s * 0.95f, x + s * 0.4f, y + s * 0.95f, c, t);
            }
            case "gear" -> {
                d.addCircle(x, y, s * 0.55f, c, 20, t);
                d.addCircleFilled(x, y, s * 0.2f, c);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    d.addLine(x + (float) Math.cos(a) * s * 0.6f, y + (float) Math.sin(a) * s * 0.6f, x + (float) Math.cos(a) * s, y + (float) Math.sin(a) * s, c, t * 1.4f);
                }
            }
            default -> { // rocket
                d.addTriangle(x, y - s, x + s * 0.45f, y + s * 0.3f, x - s * 0.45f, y + s * 0.3f, c, t);
                d.addTriangle(x - s * 0.45f, y + s * 0.3f, x - s * 0.8f, y + s * 0.8f, x - s * 0.25f, y + s * 0.3f, c, t);
                d.addTriangle(x + s * 0.45f, y + s * 0.3f, x + s * 0.8f, y + s * 0.8f, x + s * 0.25f, y + s * 0.3f, c, t);
                d.addCircleFilled(x, y + s * 0.85f, s * 0.15f, c);
            }
        }
    }
    // ---------------------------------------------------------------- helpers

    /** A rounded button; 'primary' ones are purple. */
    private boolean button(String label, float w, float h, boolean primary) {
        ImVec2 at = ImGui.getCursorScreenPos();
        boolean clicked = ImGui.invisibleButton("##btn" + label, w, h);
        boolean hovered = ImGui.isItemHovered();
        boolean held = ImGui.isItemActive();
        Color fill = primary ? (hovered ? Theme.ACCENT_HOVER : Theme.ACCENT) : (held ? Theme.SURFACE_HI : hovered ? Theme.SELECTION : Theme.SURFACE);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(at.x, at.y, at.x + w, at.y + h, EditorStyle.pack(fill), 6 * scale);
        ImVec2 text = ImGui.calcTextSize(label);
        draw.addText(at.x + (w - text.x) / 2, at.y + (h - text.y) / 2, EditorStyle.pack(primary ? Theme.BLACK : Theme.TEXT), label);
        return clicked;
    }

    /** The text, shortened in the middle with "..." if it's wider than 'width' (long folder paths). */
    private static String fit(String text, float width) {
        if (ImGui.calcTextSize(text).x <= width) return text;
        int keepEnd = text.length() / 2;
        int keepStart = text.length() / 2;
        while (keepStart > 4) {
            String shorter = text.substring(0, keepStart) + "..." + text.substring(text.length() - keepEnd);
            if (ImGui.calcTextSize(shorter).x <= width) return shorter;
            keepStart -= 2;
            if (keepEnd > 12) keepEnd -= 1;
        }
        return "..." + text.substring(Math.max(0, text.length() - keepEnd));
    }

    /** "just now", "5 minutes ago", "yesterday", "12 Oct 2026". */
    private static String ago(long millis) {
        if (millis <= 0) return "";
        long seconds = (System.currentTimeMillis() - millis) / 1000;
        if (seconds < 60) return "just now";
        if (seconds < 3600) return (seconds / 60) + (seconds < 120 ? " minute ago" : " minutes ago");
        LocalDate day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (day.equals(today)) return (seconds / 3600) + (seconds < 7200 ? " hour ago" : " hours ago");
        if (day.equals(today.minusDays(1))) return "yesterday";
        return DATE.format(day);
    }

    private static void browse(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (IOException | RuntimeException e) {
            try {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } catch (IOException ignored) {
                // no browser to open
            }
        }
    }
}
