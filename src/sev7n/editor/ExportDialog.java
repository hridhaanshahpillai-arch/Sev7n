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
    private String icon = ""; // a picture in the project, or "" for the Sev7n logo
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
                "Makes a game that runs on any " + systemName() + " computer (about 60 MB). Without it the game needs Java installed. "
                        + "Exports are for the system you export on: export on a Mac for Macs, on Linux for Linux.");
        if (!canBundle) ImGui.endDisabled();
        // the game's icon: any picture in the project (square ones look best), or the Sev7n logo
        Widgets.label("Icon");
        java.util.List<String> pictures = ctx.project.assets(sev7n.project.AssetKind.TEXTURE);
        if (!icon.isEmpty() && !pictures.contains(icon)) icon = "";
        if (ImGui.beginCombo("##icon", icon.isEmpty() ? "Sev7n logo" : Path.of(icon).getFileName().toString())) {
            if (ImGui.selectable("Sev7n logo", icon.isEmpty())) icon = "";
            for (String picture : pictures) {
                if (ImGui.selectable(picture, picture.equals(icon))) icon = picture;
            }
            ImGui.endCombo();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("The picture on the game's .exe and its window. A square PNG looks best");
        String game = Exporter.fileName(name.get());
        String launcher = switch (sev7n.Platform.OS) {
            case WINDOWS -> game + ".exe";
            case MAC -> game + ".app";
            case LINUX -> "bin/" + game;
        };
        Widgets.hint(includeJava
                ? "Makes a folder with " + launcher + ". Zip the folder to share it."
                : canBundle ? "Makes a small folder with " + (sev7n.Platform.windows() ? "Play.bat" : "play.sh") + ". Players need Java 21 installed."
                            : "This Java can't include itself (no jpackage), so players will need Java 21 installed.");
        Widgets.hint("Saved to: " + destination());
        ImGui.spacing();

        if (engine == null) {
            ImGui.textColored(EditorStyle.pack(Theme.ERROR), "Export works from the built Sev7n (run " + (sev7n.Platform.windows() ? "build.bat" : "./build.sh")
                    + ", then open Sev7n from it).");
        } else if (running) {
            ImGui.textColored(EditorStyle.pack(Theme.ACCENT), status);
        } else if (result != null) {
            ImGui.textColored(EditorStyle.pack(Theme.SUCCESS), "Done! " + result.getFileName() + " is ready.");
            if (ImGui.button("Show in " + sev7n.Platform.fileManager())) ProjectPanel.showInExplorer(result.getParent());
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
        String iconPath = icon.isEmpty() ? null : icon;
        running = true;
        result = null;
        status = "Starting...";
        ctx.log.system("Exporting " + gameName + "...");
        ctx.inBackground(() -> {
            try {
                Path start = new Exporter(ctx.project, engine, message -> status = message)
                        .export(gameName, destination, withJava, iconPath);
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

    private static String systemName() {
        return switch (sev7n.Platform.OS) {
            case WINDOWS -> "Windows";
            case MAC -> "Mac";
            case LINUX -> "Linux";
        };
    }

    private void start(Path file) {
        try {
            sev7n.Platform.open(file);
        } catch (IOException e) {
            ctx.log.error("Couldn't start " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
