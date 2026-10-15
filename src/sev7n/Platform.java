package sev7n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The few things that differ between Windows, macOS and Linux: where settings live, how to
 * show a folder or open a file, and what programs are called.
 */
public final class Platform {

    public enum Os { WINDOWS, MAC, LINUX }

    public static final Os OS = detect();

    private Platform() { }

    private static Os detect() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return Os.WINDOWS;
        if (name.contains("mac") || name.contains("darwin")) return Os.MAC;
        return Os.LINUX;
    }

    public static boolean windows() {
        return OS == Os.WINDOWS;
    }

    public static boolean mac() {
        return OS == Os.MAC;
    }

    /** "Explorer", "Finder" or "Files": what the file manager is called here (for menu items). */
    public static String fileManager() {
        return switch (OS) {
            case WINDOWS -> "Explorer";
            case MAC -> "Finder";
            case LINUX -> "Files";
        };
    }

    /**
     * Where Sev7n keeps its own settings, project list and saved games:
     * %APPDATA%\Sev7n on Windows, ~/Library/Application Support/Sev7n on a Mac, ~/.config/sev7n on Linux.
     */
    public static Path dataFolder() {
        String home = System.getProperty("user.home");
        return switch (OS) {
            case WINDOWS -> Path.of(System.getenv().getOrDefault("APPDATA", home), "Sev7n");
            case MAC -> Path.of(home, "Library", "Application Support", "Sev7n");
            case LINUX -> {
                String config = System.getenv("XDG_CONFIG_HOME");
                yield Path.of(config != null && !config.isBlank() ? config : home + "/.config", "sev7n");
            }
        };
    }

    /** Shows a folder (or the folder a file is in, with the file picked out) in the file manager. */
    public static void showInFileManager(Path path) {
        try {
            switch (OS) {
                case WINDOWS -> new ProcessBuilder("explorer.exe", path.toString()).start();
                case MAC -> new ProcessBuilder(Files.isDirectory(path) ? List.of("open", path.toString()) : List.of("open", "-R", path.toString())).start();
                case LINUX -> new ProcessBuilder("xdg-open", (Files.isDirectory(path) ? path : path.getParent()).toString()).start();
            }
        } catch (IOException ignored) {
            // nothing useful to do if the file manager won't start
        }
    }

    /** Opens a file with whatever opens it normally (a game, a web page...). */
    public static void open(Path file) throws IOException {
        List<String> command = switch (OS) {
            case WINDOWS -> List.of("cmd", "/c", "start", "", file.toString());
            case MAC -> List.of("open", file.toString());
            case LINUX -> file.toString().endsWith(".sh") ? List.of("sh", file.toString())
                    : Files.isRegularFile(file) && Files.isExecutable(file) ? List.of(file.toString()) // a program: run it
                    : List.of("xdg-open", file.toString());
        };
        new ProcessBuilder(command).directory(file.toAbsolutePath().getParent().toFile()).start();
    }

    /** A program's file name: "jpackage" becomes "jpackage.exe" on Windows. */
    public static String program(String name) {
        return windows() ? name + ".exe" : name;
    }
}
