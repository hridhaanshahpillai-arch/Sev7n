package sev7n.project;

import sev7n.scene.Json;
import sev7n.ui.AppIcon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns a project into a game other people can play, without the editor.
 *
 * With Java included (needs the JDK's jpackage tool), the result is a folder with
 * Name.exe that runs on any Windows PC. Without, it's a smaller folder with Play.bat
 * that needs Java installed.
 *
 * Either way the folder holds the engine (sev7n.jar), the game (game\) and export.json;
 * the engine notices the game next to it and plays it instead of opening the editor.
 */
public final class Exporter {

    /** Written into every export, so an old export can safely be replaced. */
    private static final String MARKER = "export.json";
    /** Only what the player needs: base Java, pictures/windows (desktop), and LWJGL's needs. */
    private static final String MODULES = "java.base,java.desktop,java.logging,jdk.unsupported";

    private final Project project;
    private final Path engineJar;
    private final Consumer<String> progress;

    public Exporter(Project project, Path engineJar, Consumer<String> progress) {
        this.project = project;
        this.engineJar = engineJar;
        this.progress = progress;
    }

    /** The JDK tool that builds a standalone app, or null if this Java doesn't have it. */
    public static Path jpackage() {
        Path tool = Path.of(System.getProperty("java.home"), "bin", sev7n.Platform.program("jpackage"));
        return Files.isRegularFile(tool) ? tool : null;
    }

    /** A safe name for files and the .exe: "My Game!" -> "My Game". */
    public static String fileName(String gameName) {
        String cleaned = gameName.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        return cleaned.isEmpty() ? "Game" : cleaned;
    }

    /**
     * Exports into 'destination' (replacing an earlier export there) and returns the file
     * to start the game with.
     */
    public Path export(String gameName, Path destination, boolean includeJava) throws IOException {
        return export(gameName, destination, includeJava, null);
    }

    /**
     * Like export(...), with a picture from the project as the game's icon (its .exe and its
     * window), e.g. "assets/textures/icon.png"; null keeps the Sev7n logo.
     */
    public Path export(String gameName, Path destination, boolean includeJava, String iconPath) throws IOException {
        String name = fileName(gameName);
        java.awt.image.BufferedImage icon = null;
        if (iconPath != null && !iconPath.isBlank()) {
            Path iconFile = project.root().resolve(iconPath);
            if (!Files.isRegularFile(iconFile)) throw new IOException("the icon " + iconPath + " isn't in the project any more: pick another one");
            icon = javax.imageio.ImageIO.read(iconFile.toFile());
            if (icon == null) throw new IOException("the icon " + iconPath + " isn't a picture Java can read (use a PNG)");
        }
        if (Files.exists(destination) && !Files.exists(destination.resolve(MARKER))) {
            throw new IOException(destination + " already exists and isn't an earlier export, so it won't be replaced");
        }
        Path staging = Files.createTempDirectory("sev7n-export");
        try {
            progress.accept("Copying the game...");
            Path input = staging.resolve("input");
            Files.createDirectories(input);
            Files.copy(engineJar, input.resolve("sev7n.jar"));
            copyGame(input.resolve("game"));
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", gameName);
            if (icon != null) info.put("icon", "game/" + iconPath.replace('\\', '/')); // the window uses it too
            Files.writeString(input.resolve(MARKER), Json.write(info), StandardCharsets.UTF_8);

            Path built;
            Path start;
            if (includeJava) {
                progress.accept("Packing Java with the game (this takes a minute)...");
                built = packageWithJava(name, input, staging, icon);
                // what jpackage makes: Name.exe on Windows, Name.app on a Mac, bin/Name on Linux
                start = switch (sev7n.Platform.OS) {
                    case WINDOWS -> destination.resolve(name + ".exe");
                    case MAC -> destination.resolve(name + ".app");
                    case LINUX -> destination.resolve("bin").resolve(name);
                };
                Files.copy(input.resolve(MARKER), built.resolve(MARKER)); // marks the top folder too
            } else if (sev7n.Platform.windows()) {
                Files.writeString(input.resolve("Play.bat"),
                        "@echo off\r\nstart \"\" javaw -jar \"%~dp0sev7n.jar\"\r\n", StandardCharsets.US_ASCII);
                built = input;
                start = destination.resolve("Play.bat");
            } else {
                // a shell script for macOS and Linux (a Mac needs Java to open windows on its first thread)
                Files.writeString(input.resolve("play.sh"), """
                        #!/bin/sh
                        cd "$(dirname "$0")"
                        if [ "$(uname)" = "Darwin" ]; then exec java -XstartOnFirstThread -jar sev7n.jar; else exec java -jar sev7n.jar; fi
                        """, StandardCharsets.US_ASCII);
                built = input;
                start = destination.resolve("play.sh");
            }

            if (Files.exists(destination)) Archives.deleteTree(destination);
            Files.createDirectories(destination.getParent());
            if (includeJava && sev7n.Platform.mac()) {
                // a Mac app is a folder called Name.app; the export folder holds it (and the marker)
                AssetImporter.copyTree(built, start);
                Files.copy(input.resolve(MARKER), destination.resolve(MARKER));
            } else {
                AssetImporter.copyTree(built, destination);
            }
            if (!sev7n.Platform.windows() && Files.isRegularFile(start)) start.toFile().setExecutable(true); // copying loses that on Unix
            return start;
        } finally {
            Archives.deleteTree(staging);
        }
    }

    /** The scene, scripts and assets: everything the game needs, nothing else. */
    private void copyGame(Path target) throws IOException {
        Files.createDirectories(target);
        Files.copy(project.scenePath(), target.resolve(project.scenePath().getFileName()));
        Path graphics = project.root().resolve("game.json"); // the game's graphics settings
        if (Files.exists(graphics)) Files.copy(graphics, target.resolve("game.json"));
        Path audio = project.root().resolve(sev7n.audio.MixerSettings.FILE_NAME); // the Audio Mixer's volumes
        if (Files.exists(audio)) Files.copy(audio, target.resolve(sev7n.audio.MixerSettings.FILE_NAME));
        for (Path folder : List.of(project.scriptsFolder(), project.assetsFolder(), project.scenesFolder())) {
            if (Files.isDirectory(folder)) AssetImporter.copyTree(folder, target.resolve(folder.getFileName()));
        }
    }

    private Path packageWithJava(String name, Path input, Path staging, java.awt.image.BufferedImage picture) throws IOException {
        Path tool = jpackage();
        if (tool == null) throw new IOException("this Java has no jpackage tool; export without Java instead");
        Path output = staging.resolve("output");
        List<String> command = new ArrayList<>(List.of(tool.toString(),
                "--type", "app-image",
                "--name", name,
                "--input", input.toString(),
                "--main-jar", "sev7n.jar",
                "--main-class", "sev7n.Sev7n",
                "--dest", output.toString()));
        // the icon: .ico on Windows, .png on Linux (a Mac wants .icns, which Java can't write, so it keeps the default)
        if (sev7n.Platform.windows()) {
            Path icon = staging.resolve("icon.ico");
            AppIcon.writeIco(icon, picture);
            command.addAll(List.of("--icon", icon.toString()));
        } else if (!sev7n.Platform.mac()) {
            Path icon = staging.resolve("icon.png");
            javax.imageio.ImageIO.write(picture != null ? AppIcon.fitted(picture, 256) : AppIcon.render(256), "png", icon.toFile());
            command.addAll(List.of("--icon", icon.toString()));
        }
        if (sev7n.Platform.mac()) command.addAll(List.of("--java-options", "-XstartOnFirstThread"));
        Path javaHome = Path.of(System.getProperty("java.home"));
        if (Files.isDirectory(javaHome.resolve("jmods"))) {
            command.addAll(List.of("--add-modules", MODULES)); // a full JDK: build a small Java just for the game
        } else {
            command.addAll(List.of("--runtime-image", javaHome.toString())); // the Sev7n download: reuse its own Java
        }
        Tools.Result result = Tools.run(command, 10);
        Path app = output.resolve(sev7n.Platform.mac() ? name + ".app" : name);
        if (result.exitCode() != 0 || !Files.isDirectory(app)) {
            throw new IOException("jpackage failed: " + result.lastLine());
        }
        return app;
    }
}
