package sev7n.project;

import sev7n.scene.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The projects the Hub lists: every project opened or created (remembered in
 * %APPDATA%\Sev7n\projects.json, newest first), plus any project found in Sev7n's own projects
 * folder. Removing one from the list never deletes its files.
 */
public final class ProjectList {

    /** One project in the list. 'version' is the Sev7n it was last opened with ("" if never). */
    public record Entry(Path folder, long lastOpened, String version) {
        public String name() {
            return folder.getFileName() == null ? folder.toString() : folder.getFileName().toString();
        }

        /** False if the folder was moved or deleted (or it isn't a project any more). */
        public boolean exists() {
            return Files.isRegularFile(folder.resolve("main.scene"));
        }

        /** When the project was last changed: its newest scene or script, or when it was last opened. */
        public long modified() {
            long newest = lastOpened;
            for (Path file : List.of(folder.resolve("main.scene"), folder.resolve("scripts"))) {
                try {
                    if (Files.exists(file)) newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
                } catch (IOException ignored) {
                    // unreadable: keep what we have
                }
            }
            return newest;
        }
    }

    private static final Path FILE = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")), "Sev7n", "projects.json");

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    private ProjectList(Path file) {
        this.file = file;
    }

    /** The saved list, plus projects found in 'projectsFolder' (e.g. Sev7n's own projects folder) that aren't in it yet. */
    public static ProjectList load(Path projectsFolder) {
        return load(FILE, projectsFolder);
    }

    static ProjectList load(Path file, Path projectsFolder) {
        ProjectList list = new ProjectList(file);
        try {
            if (Files.exists(file)) {
                for (Object item : Json.list(Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8))), "projects")) {
                    Map<String, Object> project = Json.object(item);
                    String folder = Json.string(project, "folder", "");
                    if (folder.isBlank()) continue;
                    list.entries.add(new Entry(Path.of(folder), (long) Json.number(project, "lastOpened", 0), Json.string(project, "version", "")));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Couldn't read the project list (" + e.getMessage() + "), starting a new one");
        }
        if (projectsFolder != null && Files.isDirectory(projectsFolder)) {
            try (Stream<Path> folders = Files.list(projectsFolder)) {
                folders.filter(f -> Files.isRegularFile(f.resolve("main.scene")))
                        .map(f -> f.toAbsolutePath().normalize())
                        .filter(f -> list.find(f) == null)
                        .forEach(f -> list.entries.add(new Entry(f, 0, "")));
            } catch (IOException ignored) {
                // nothing to add
            }
        }
        list.sort();
        return list;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Moves a project to the top of the list (just opened with this version of Sev7n), adding it if needed. */
    public void remember(Path folder, String version) {
        Path normal = folder.toAbsolutePath().normalize();
        entries.removeIf(e -> e.folder().equals(normal));
        entries.add(0, new Entry(normal, System.currentTimeMillis(), version));
        save();
    }

    /** Takes a project off the list (its files stay where they are). */
    public void forget(Path folder) {
        Path normal = folder.toAbsolutePath().normalize();
        entries.removeIf(e -> e.folder().equals(normal));
        save();
    }

    private Entry find(Path folder) {
        for (Entry e : entries) if (e.folder().equals(folder)) return e;
        return null;
    }

    private void sort() {
        entries.sort(Comparator.comparingLong(Entry::modified).reversed());
    }

    private void save() {
        List<Object> projects = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> project = new LinkedHashMap<>();
            project.put("folder", e.folder().toString());
            project.put("lastOpened", (double) e.lastOpened());
            project.put("version", e.version());
            projects.add(project);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(Map.of("projects", projects)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Couldn't save the project list: " + e.getMessage());
        }
    }
}
