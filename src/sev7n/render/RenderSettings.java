package sev7n.render;

import sev7n.scene.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a game looks and runs: graphics API, shading, resolution, smoothing, fog, brightness and
 * the frame rate limit. Saved with the project (game.json), so exported games look the same.
 */
public final class RenderSettings {

    public static final String FILE_NAME = "game.json";

    /** How surfaces are lit. */
    public enum Shading {
        LIT("Lit", "Normal lighting with a soft highlight"),
        FLAT("Flat", "No lighting: every surface shows its full color"),
        TOON("Toon", "Cartoon look: light falls off in a few hard steps"),
        WIREFRAME("Wireframe", "Only the edges of every triangle");

        public final String label;
        public final String description;

        Shading(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    /** Which graphics API draws the game window (the editor always uses OpenGL). */
    public enum Api {
        OPENGL("OpenGL", "Works on every PC. The editor always uses it"),
        VULKAN("Vulkan", "The newer, lower-level API: less work for the CPU each frame. Used by the game window and exported games; falls back to OpenGL if a PC can't run it");

        public final String label;
        public final String description;

        Api(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    public Api api = Api.OPENGL;
    public Shading shading = Shading.LIT;
    /** Resolution compared to the window: 0.25 is chunky retro pixels, 1 is sharp, 2 is extra smooth. */
    public double renderScale = 1;
    /** Multisample anti-aliasing: 0 (off), 2, 4 or 8 samples per pixel. */
    public int antiAliasing = 4;
    /** Show every picture with sharp pixels, whatever each sprite says. */
    public boolean pixelatedTextures = false;
    /** Far things fade into the background color. */
    public boolean fog = false;
    /** Where fog starts and where everything is fully faded ("render distance"), in units. */
    public double fogStart = 30;
    public double fogEnd = 80;
    /** 0.6 = moody, 1 = normal, 1.5 = bright. */
    public double brightness = 1;
    /** The game's frame rate limit: 0 = unlimited. */
    public int maxFps = 0;
    /** Wait for the screen's refresh (no tearing, and usually caps the frame rate at the screen's). */
    public boolean vsync = true;

    /** Quick starting points: Fancy, Fast or Retro. */
    public void applyPreset(String preset) {
        switch (preset) {
            case "Fancy" -> {
                shading = Shading.LIT;
                renderScale = 1;
                antiAliasing = 8;
                pixelatedTextures = false;
            }
            case "Fast" -> {
                shading = Shading.LIT;
                renderScale = 0.75;
                antiAliasing = 0;
                pixelatedTextures = false;
            }
            case "Retro" -> {
                shading = Shading.FLAT;
                renderScale = 0.33;
                antiAliasing = 0;
                pixelatedTextures = true;
            }
            default -> { }
        }
    }

    public static RenderSettings load(Path file) {
        RenderSettings settings = new RenderSettings();
        if (!Files.exists(file)) return settings;
        try {
            Map<String, Object> in = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            try {
                settings.shading = Shading.valueOf(Json.string(in, "shading", "LIT"));
            } catch (IllegalArgumentException ignored) {
                // unknown mode from a newer version: keep Lit
            }
            try {
                settings.api = Api.valueOf(Json.string(in, "graphicsApi", "OPENGL"));
            } catch (IllegalArgumentException ignored) {
                // unknown API from a newer version: keep OpenGL
            }
            settings.renderScale = clamp(Json.number(in, "renderScale", settings.renderScale), 0.1, 2);
            settings.antiAliasing = (int) Json.number(in, "antiAliasing", settings.antiAliasing);
            settings.pixelatedTextures = Json.bool(in, "pixelatedTextures", settings.pixelatedTextures);
            settings.fog = Json.bool(in, "fog", settings.fog);
            settings.fogStart = Json.number(in, "fogStart", settings.fogStart);
            settings.fogEnd = Json.number(in, "fogEnd", settings.fogEnd);
            settings.brightness = clamp(Json.number(in, "brightness", settings.brightness), 0.2, 3);
            settings.maxFps = (int) Json.number(in, "maxFps", settings.maxFps);
            settings.vsync = Json.bool(in, "vsync", settings.vsync);
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't read " + file + " (using default graphics): " + e.getMessage());
        }
        return settings;
    }

    public void save(Path file) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graphicsApi", api.name());
        out.put("shading", shading.name());
        out.put("renderScale", renderScale);
        out.put("antiAliasing", antiAliasing);
        out.put("pixelatedTextures", pixelatedTextures);
        out.put("fog", fog);
        out.put("fogStart", fogStart);
        out.put("fogEnd", fogEnd);
        out.put("brightness", brightness);
        out.put("maxFps", maxFps);
        out.put("vsync", vsync);
        Files.writeString(file, Json.write(out), StandardCharsets.UTF_8);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
