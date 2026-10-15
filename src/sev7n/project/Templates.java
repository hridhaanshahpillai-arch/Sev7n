package sev7n.project;

import sev7n.scene.BoxCollider;
import sev7n.scene.Camera;
import sev7n.scene.Entity;
import sev7n.scene.Light;
import sev7n.scene.MeshRenderer;
import sev7n.scene.Scene;
import sev7n.scene.SceneSerializer;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** What a new project can start from (the Hub's "New project" page). */
public final class Templates {

    /** A starting point: its name, what it's for, and what's in it. */
    public record Template(String name, String description, String contents) { }

    public static final List<Template> ALL = List.of(
            new Template("3D", "A 3D world to build in", "A camera, a sun and a ground to stand on"),
            new Template("2D", "Platformers, top-down games, puzzles", "A flat (orthographic) camera, ready for sprites and tilemaps"),
            new Template("Empty", "Start from nothing", "Just a camera"));

    private Templates() { }

    /** Makes a new project in 'folder' (which must not exist yet, or be empty) from a template. */
    public static Project create(Path folder, Template template) throws IOException {
        if (Files.exists(folder)) {
            try (Stream<Path> inside = Files.list(folder)) {
                if (inside.findAny().isPresent()) throw new IOException("there's already a folder called " + folder.getFileName() + " there");
            }
        }
        Files.createDirectories(folder);
        Scene scene = switch (template.name()) {
            case "3D" -> world3d();
            case "2D" -> world2d();
            default -> empty();
        };
        SceneSerializer.save(scene, folder.resolve("main.scene"));
        return Project.openOrCreate(folder);
    }

    private static Scene empty() {
        Scene scene = new Scene("Main");
        Entity camera = scene.create("Main Camera");
        camera.transform.position.set(0, 4, 9);
        camera.transform.rotation.set(-22, 0, 0);
        camera.add(new Camera());
        return scene;
    }

    private static Scene world3d() {
        Scene scene = empty();
        Entity sun = scene.create("Sun");
        sun.transform.position.set(0, 8, 0);
        sun.transform.rotation.set(-50, 30, 0);
        sun.add(new Light());
        Entity ground = scene.create("Ground");
        ground.transform.position.set(0, -0.5, 0);
        ground.transform.scale.set(20, 1, 20);
        MeshRenderer renderer = ground.add(new MeshRenderer());
        renderer.mesh = "cube";
        renderer.color = new Color(0x44475A);
        ground.add(new BoxCollider());
        return scene;
    }

    private static Scene world2d() {
        Scene scene = new Scene("Main");
        Entity camera = scene.create("Main Camera");
        camera.transform.position.set(0, 0, 10);
        Camera lens = camera.add(new Camera());
        lens.orthographic = true;
        lens.size = 5;
        lens.background = new Color(0x1E1F29);
        return scene;
    }
}
