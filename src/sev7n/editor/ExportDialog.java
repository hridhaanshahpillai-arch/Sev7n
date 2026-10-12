package sev7n.editor;

import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import sev7n.Sev7n;
import sev7n.project.Exporter;
import sev7n.ui.Theme;

import java.io.IOException;
import java.nio.file.Path;

/**
 * File > Export Game: makes a folder with the game that anyone can play,
 * without Sev7n (exports\Name\Name.exe).
 */
final class ExportDialog {

    private static final String TITLE = "Export Game";

    private final EditorContext ctx;
    private final ImString name = new ImString(64);
    private boolean includeJava = true;
    private boolean openRequested;
    private volatile String status = "";      // written by the export thread
    private volatile boolean running;
    private volatile Path result;              // the file that starts the exported game

    ExportDialog(EditorContext ctx) {
        this.ctx = ctx;
    }

    void open() {
        if (name.get().isEmpty()) name.set(ctx.project.name());
        includeJava = Exporter.jpackage() != null;
        openRequested = true;
    }

    void draw() {
        if (openRequested) {
            ImGui.openPopup(TITLE);
            openRequested = false;
        }
        ImGui.setNextWindowSize(460 * ImGui.getFontSize() / 15, 0);
        if (!ImGui.beginPopupModal(TITLE, ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) return;

        Path engine = Sev7n.engineJar();
        Widgets.label("Game name");
        ImGui.inputText("##game-name", name);
        boolean canBundle = Exporter.jpackage() != null;
        if (!canBundle) ImGui.beginDisabled();
        includeJava = Widgets.checkbox("Include Java", includeJava && canBundle,
                "Makes Name.exe that runs on any Windows PC (about 60 MB). Without it the game needs Java installed.");
        if (!canBundle) ImGui.endDisabled();
        Widgets.hint(includeJava
                ? "Makes a folder with " + Exporter.fileName(name.get()) + ".exe. Zip the folder to share it."
                : canBundle ? "Makes a small folder with Play.bat. Players need Java 21 installed."
                            : "This Java can't include itself (no jpackage), so players will need Java 21 installed.");
        Widgets.hint("Saved to: " + destination());
        ImGui.spacing();

        if (engine == null) {
            ImGui.textColored(EditorStyle.pack(Theme.ERROR), "Export works from the built Sev7n (run build.bat, then open it from its shortcut).");
        } else if (running) {
            ImGui.textColored(EditorStyle.pack(Theme.ACCENT), status);
        } else if (result != null) {
            ImGui.textColored(EditorStyle.pack(Theme.SUCCESS), "Done! " + result.getFileName() + " is ready.");
            if (ImGui.button("Show in Explorer")) ProjectPanel.showInExplorer(result.getParent());
            ImGui.sameLine();
            if (ImGui.button("Play it")) start(result);
        } else if (!status.isEmpty()) {
            ImGui.textColored(EditorStyle.pack(Theme.ERROR), status);
        }
        ImGui.spacing();

        if (running || engine == null) ImGui.beginDisabled();
        if (Widgets.primaryButton(result != null ? "Export again" : "Export")) export(engine);
        if (running || engine == null) ImGui.endDisabled();
        if (!running && ImGui.button("Close", -1, 0)) ImGui.closeCurrentPopup();
        ImGui.endPopup();
    }

    private Path destination() {
        return Sev7n.home().resolve("exports").resolve(Exporter.fileName(name.get()));
    }

    private void export(Path engine) {
        ctx.saveAll(); // export what's on screen, not what was last saved
        String gameName = name.get().isBlank() ? ctx.project.name() : name.get().trim();
        Path destination = destination();
        boolean withJava = includeJava;
        running = true;
        result = null;
        status = "Starting...";
        ctx.log.system("Exporting " + gameName + "...");
        ctx.inBackground(() -> {
            try {
                Path start = new Exporter(ctx.project, engine, message -> status = message)
                        .export(gameName, destination, withJava);
                result = start;
                ctx.post(() -> ctx.log.system("Exported " + gameName + " to " + start.getParent()));
            } catch (IOException | RuntimeException e) {
                status = "Export failed: " + e.getMessage();
                ctx.post(() -> ctx.log.error("Export failed: " + e.getMessage()));
            } finally {
                running = false;
            }
        });
    }

    private void start(Path file) {
        try {
            new ProcessBuilder("cmd", "/c", "start", "", file.toString()).directory(file.getParent().toFile()).start();
        } catch (IOException e) {
            ctx.log.error("Couldn't start " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
