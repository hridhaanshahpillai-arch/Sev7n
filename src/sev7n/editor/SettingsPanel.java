package sev7n.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import sev7n.render.RenderSettings;
import sev7n.render.vulkan.Vulkan;
import sev7n.ui.Theme;

import java.util.List;

/**
 * Settings (click the 7 in the menu bar): how the game looks and runs (graphics API,
 * shading, resolution, frame rate) and how the editor runs. Changes apply straight away and are saved.
 */
final class SettingsPanel {

    static final String TITLE = "Settings";

    private static final List<String> SECTIONS = List.of("Game graphics", "Frame rate", "Editor");
    private static final int[] FPS_CHOICES = {30, 60, 120, 144, 240, 0};
    private static final int[] AA_CHOICES = {0, 2, 4, 8};

    private final EditorContext ctx;
    private final ImBoolean open = new ImBoolean(false);
    private final float[] slider = new float[1];
    private String section = SECTIONS.get(0);
    private boolean focusRequested;
    private Vulkan.Support vulkan;    // null until checked
    private boolean checkingVulkan;

    SettingsPanel(EditorContext ctx) {
        this.ctx = ctx;
    }

    void open() {
        open.set(true);
        focusRequested = true;
    }

    void draw() {
        if (!open.get()) return;
        float scale = ImGui.getFontSize() / 15f;
        ImGui.setNextWindowSize(640 * scale, 470 * scale, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowPos(ImGui.getMainViewport().getCenterX(), ImGui.getMainViewport().getCenterY(),
                ImGuiCond.FirstUseEver, 0.5f, 0.5f);
        if (focusRequested) {
            ImGui.setNextWindowFocus();
            focusRequested = false;
        }
        if (!ImGui.begin(TITLE, open, ImGuiWindowFlags.NoCollapse)) {
            ImGui.end();
            return;
        }
        ImGui.beginChild("##sections", 150 * scale, 0, true);
        for (String name : SECTIONS) {
            if (ImGui.selectable(name, name.equals(section))) section = name;
        }
        ImGui.endChild();
        ImGui.sameLine();
        ImGui.beginChild("##options");
        switch (section) {
            case "Game graphics" -> drawGraphics();
            case "Frame rate" -> drawFrameRate();
            default -> drawEditor();
        }
        ImGui.endChild();
        ImGui.end();
    }

    // ---------------------------------------------------------------- game graphics

    private void drawGraphics() {
        RenderSettings g = ctx.graphics;
        heading("Game graphics");
        Widgets.hint("Saved with your project, so exported games look the same. The Game view shows the result.");
        ImGui.spacing();

        Widgets.label("Preset");
        for (String preset : List.of("Fancy", "Fast", "Retro")) {
            if (ImGui.button(preset)) {
                g.applyPreset(preset);
                changedGraphics();
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip(switch (preset) {
                case "Fancy" -> "Sharpest picture, smoothest edges";
                case "Fast" -> "Lower resolution, no smoothing: for slower PCs";
                default -> "Chunky pixels and flat colors, like early 3D games";
            });
            ImGui.sameLine();
        }
        ImGui.newLine();
        ImGui.separator();

        drawGraphicsApi(g);

        Widgets.label("Shading");
        if (ImGui.beginCombo("##shading", g.shading.label)) {
            for (RenderSettings.Shading mode : RenderSettings.Shading.values()) {
                if (ImGui.selectable(mode.label, mode == g.shading)) {
                    g.shading = mode;
                    changedGraphics();
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip(mode.description);
            }
            ImGui.endCombo();
        }

        int percent = (int) Math.round(g.renderScale * 100);
        int newPercent = percentSlider("Render scale", percent, 25, 200, "%d%%",
                "How many pixels the game draws compared to the window. Below 100% gives chunky retro pixels and runs faster");
        if (newPercent != percent) {
            g.renderScale = newPercent / 100.0;
            changedGraphics();
        }

        Widgets.label("Anti-aliasing");
        if (ImGui.beginCombo("##aa", g.antiAliasing == 0 ? "Off" : g.antiAliasing + "x")) {
            for (int samples : AA_CHOICES) {
                if (ImGui.selectable(samples == 0 ? "Off" : samples + "x", samples == g.antiAliasing)) {
                    g.antiAliasing = samples;
                    changedGraphics();
                }
            }
            ImGui.endCombo();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Smooths jagged edges. Higher is smoother but slower");

        boolean pixelated = Widgets.checkbox("Pixel textures", g.pixelatedTextures,
                "Show every picture and texture with sharp pixels instead of smoothing them");
        if (pixelated != g.pixelatedTextures) {
            g.pixelatedTextures = pixelated;
            changedGraphics();
        }

        int brightness = (int) Math.round(g.brightness * 100);
        int newBrightness = percentSlider("Brightness", brightness, 50, 160,
                brightness < 85 ? "Moody (%d%%)" : brightness > 115 ? "Bright (%d%%)" : "Normal (%d%%)", null);
        if (newBrightness != brightness) {
            g.brightness = newBrightness / 100.0;
            changedGraphics();
        }

        ImGui.separator();
        boolean fog = Widgets.checkbox("Fog", g.fog, "Far things fade into the camera's background color");
        if (fog != g.fog) {
            g.fog = fog;
            changedGraphics();
        }
        if (g.fog) {
            int distance = (int) Math.round(g.fogEnd);
            int newDistance = percentSlider("Render distance", distance, 5, 300, "%d units",
                    "How far you can see before everything has faded into the fog");
            if (newDistance != distance) {
                g.fogEnd = newDistance;
                g.fogStart = newDistance * 0.4;
                changedGraphics();
            }
        }
    }

    private void changedGraphics() {
        ctx.saveGraphics();
    }

    /** OpenGL or Vulkan for the game window, and whether Vulkan works on this PC. */
    private void drawGraphicsApi(RenderSettings g) {
        if (vulkan == null && !checkingVulkan) {
            checkingVulkan = true;
            ctx.inBackground(() -> {
                Vulkan.Support support = Vulkan.check();
                ctx.post(() -> vulkan = support);
            });
        }
        Widgets.label("Graphics API");
        if (ImGui.beginCombo("##api", g.api.label)) {
            for (RenderSettings.Api api : RenderSettings.Api.values()) {
                if (ImGui.selectable(api.label, api == g.api)) {
                    g.api = api;
                    changedGraphics();
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip(api.description);
            }
            ImGui.endCombo();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip(g.api.description);
        if (vulkan == null) {
            Widgets.hint("Checking Vulkan on this PC...");
        } else if (vulkan.available()) {
            ImGui.textColored(EditorStyle.pack(Theme.SUCCESS), "Vulkan works on this PC");
            Widgets.hint(vulkan.detail());
        } else {
            ImGui.textColored(EditorStyle.pack(Theme.ERROR), "Vulkan can't run on this PC: games use OpenGL");
            Widgets.hint(vulkan.detail());
        }
        Widgets.hint("The editor's Game view always draws with OpenGL. To see your game with " + g.api.label
                + ", use Play in Window (Ctrl+F5).");
        if (ImGui.button("Play in Window")) ctx.playInWindow();
        ImGui.separator();
    }

    // ---------------------------------------------------------------- frame rate

    private void drawFrameRate() {
        RenderSettings g = ctx.graphics;
        EditorSettings e = ctx.editorSettings;
        heading("Your game");
        int gameFps = fpsChoice("Max FPS##game", g.maxFps);
        if (gameFps != g.maxFps) {
            g.maxFps = gameFps;
            changedGraphics();
        }
        boolean gameVsync = Widgets.checkbox("VSync##game", g.vsync, "Wait for the screen to refresh: no tearing, usually locks to the screen's rate");
        if (gameVsync != g.vsync) {
            g.vsync = gameVsync;
            changedGraphics();
        }
        Widgets.hint("Used when the game runs on its own (exported, or run.bat --play).");

        ImGui.spacing();
        heading("The editor");
        int editorFps = fpsChoice("Max FPS##editor", e.maxFps);
        boolean editorVsync = Widgets.checkbox("VSync##editor", e.vsync, "Wait for the screen to refresh");
        boolean showFps = Widgets.checkbox("Show FPS", e.showFps, "Show the frame rate in the menu bar");
        if (editorFps != e.maxFps || editorVsync != e.vsync || showFps != e.showFps) {
            e.maxFps = editorFps;
            e.vsync = editorVsync;
            e.showFps = showFps;
            e.save();
        }
        Widgets.hint("A lower limit keeps your PC cooler and quieter while you build.");
    }

    private int fpsChoice(String label, int current) {
        Widgets.label(label.substring(0, label.indexOf("##")));
        int result = current;
        if (ImGui.beginCombo(label.substring(label.indexOf("##")), fpsName(current))) {
            for (int fps : FPS_CHOICES) {
                if (ImGui.selectable(fpsName(fps), fps == current)) result = fps;
            }
            ImGui.endCombo();
        }
        return result;
    }

    private static String fpsName(int fps) {
        return fps <= 0 ? "Unlimited" : fps + " FPS";
    }

    // ---------------------------------------------------------------- editor

    private void drawEditor() {
        EditorSettings e = ctx.editorSettings;
        heading("Editor");
        int size = (int) Math.round(e.uiScale * 100);
        int newSize = percentSlider("UI size", size, 70, 160, "%d%%", "Make the editor's text and controls bigger or smaller");
        boolean grid = Widgets.checkbox("Scene grid", e.showGrid, "The floor grid in the Scene view");
        boolean colliders = Widgets.checkbox("Show colliders", e.showColliders,
                "Draw every Box Collider in the Scene view: green for solid, yellow for triggers");
        boolean live = Widgets.checkbox("Live Mode", e.liveMode,
                "Edit your game while it plays: changes are kept when you stop, and saved scripts reload straight away (also the LIVE button next to Play)");
        if (live != e.liveMode) ctx.setLiveMode(live);
        if (newSize != size || grid != e.showGrid || colliders != e.showColliders) {
            e.uiScale = newSize / 100.0;
            e.showGrid = grid;
            e.showColliders = colliders;
            e.save();
        }
        Widgets.hint("Editor settings are saved on this PC only.");
    }

    // ---------------------------------------------------------------- helpers

    /** A labeled whole-number slider that only reports a change when the value changes. */
    private int percentSlider(String label, int value, int min, int max, String format, String tooltip) {
        Widgets.label(label);
        slider[0] = value;
        int result = value;
        String shown = String.format(format, value).replace("%", "%%"); // ImGui reads % as a format code
        if (ImGui.sliderFloat("##" + label, slider, min, max, shown)) {
            result = Math.round(slider[0]);
        }
        if (tooltip != null && ImGui.isItemHovered()) ImGui.setTooltip(tooltip);
        return result;
    }

    private static void heading(String text) {
        ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(Theme.ACCENT));
        ImGui.pushFont(EditorStyle.boldFont);
        ImGui.textUnformatted(text);
        ImGui.popFont();
        ImGui.popStyleColor();
    }
}
