package sev7n.editor;

import sev7n.scene.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** How the editor itself runs on this PC (saved in %APPDATA%\Sev7n\settings.json, not with the game). */
final class EditorSettings {

    private static final Path FILE = Path.of(
            System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")), "Sev7n", "settings.json");

    /** The editor's frame rate limit: 0 = unlimited. */
    int maxFps = 0;
    boolean vsync = true;
    boolean showFps = true;
    /** Text and controls size: 1 = normal. */
    double uiScale = 1;
    boolean showGrid = true;
    boolean showColliders = false;
    /** Live Mode: edits made while playing are kept, and saved scripts reload straight away. */
    boolean liveMode = true;

    static EditorSettings load() {
        EditorSettings settings = new EditorSettings();
        if (!Files.exists(FILE)) return settings;
        try {
            Map<String, Object> in = Json.object(Json.parse(Files.readString(FILE, StandardCharsets.UTF_8)));
            settings.maxFps = (int) Json.number(in, "maxFps", settings.maxFps);
            settings.vsync = Json.bool(in, "vsync", settings.vsync);
            settings.showFps = Json.bool(in, "showFps", settings.showFps);
            settings.uiScale = Math.max(0.6, Math.min(2, Json.number(in, "uiScale", settings.uiScale)));
            settings.showGrid = Json.bool(in, "showGrid", settings.showGrid);
            settings.showColliders = Json.bool(in, "showColliders", settings.showColliders);
            settings.liveMode = Json.bool(in, "liveMode", settings.liveMode);
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't read editor settings: " + e.getMessage());
        }
        return settings;
    }

    void save() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxFps", maxFps);
        out.put("vsync", vsync);
        out.put("showFps", showFps);
        out.put("uiScale", uiScale);
        out.put("showGrid", showGrid);
        out.put("showColliders", showColliders);
        out.put("liveMode", liveMode);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, Json.write(out), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Couldn't save editor settings: " + e.getMessage());
        }
    }
}
