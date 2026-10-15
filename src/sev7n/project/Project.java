package sev7n.project;

import sev7n.eights.GraphFile;
import sev7n.scene.Camera;
import sev7n.scene.Entity;
import sev7n.scene.Json;
import sev7n.scene.Scene;
import sev7n.scene.SceneSerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A game project on disk:
 *
 *   MyGame/
 *     main.scene        the first scene (the game starts here), as JSON
 *     scenes/           more scenes (levels, menus), e.g. Level2.scene
 *     assets/prefabs/   saved objects to place again and spawn from scripts (.prefab)
 *     scripts/          behaviours: Seven scripts (.7n) and Eights graphs (.8s)
 *     assets/           imported pictures, models and other files
 */
public final class Project {

    private static final String SCENE_FILE = "main.scene";
    public static final String SCRIPT_EXTENSION = ".7n";

    private static final long ASSET_LIST_LIFETIME_MS = 1500;

    private final Path root;
    private final Map<AssetKind, List<String>> assetsByKind = new EnumMap<>(AssetKind.class);
    private long assetsListedAt;

    private Project(Path root) {
        this.root = root;
    }

    /** Opens a project folder, creating a starter project there if it doesn't exist yet. */
    public static Project openOrCreate(Path root) throws IOException {
        Project project = new Project(root.toAbsolutePath().normalize());
        Files.createDirectories(project.scriptsFolder());
        Files.createDirectories(project.assetsFolder());
        if (!Files.exists(project.scenePath())) SceneSerializer.save(starterScene(), project.scenePath());
        return project;
    }

    /** Makes a new, empty project (just a camera) in a folder that doesn't exist yet or is empty. */
    public static Project createNew(Path root) throws IOException {
        if (Files.exists(root)) {
            try (Stream<Path> inside = Files.list(root)) {
                if (inside.findAny().isPresent()) throw new IOException("there's already a folder called " + root.getFileName() + " there");
            }
        }
        return openOrCreate(root);
    }

    /** Walks up from a file (e.g. a double-clicked script) to the project that contains it, or null. */
    public static Path findRoot(Path file) {
        for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(SCENE_FILE))) return dir;
        }
        return null;
    }

    public Path root() {
        return root;
    }

    public String name() {
        return root.getFileName().toString();
    }

    public Path scenePath() {
        return root.resolve(SCENE_FILE);
    }

    public Path scriptsFolder() {
        return root.resolve("scripts");
    }

    /** Imported pictures, models and so on. */
    public Path assetsFolder() {
        return root.resolve("assets");
    }

    /** "C:\...\MyGame\assets\textures\a.png" -> "assets/textures/a.png" (how scenes refer to files). */
    public String relative(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /**
     * Every imported file of this kind, as project-relative paths, sorted.
     * Cached for a moment, because the editor asks for this every frame.
     */
    public List<String> assets(AssetKind kind) {
        long now = System.currentTimeMillis();
        if (now - assetsListedAt > ASSET_LIST_LIFETIME_MS) {
            assetsByKind.clear();
            assetsListedAt = now;
        }
        return assetsByKind.computeIfAbsent(kind, this::scanAssets);
    }

    private List<String> scanAssets(AssetKind kind) {
        if (!Files.isDirectory(assetsFolder())) return List.of();
        try (Stream<Path> files = Files.walk(assetsFolder())) {
            return files.filter(Files::isRegularFile).filter(p -> AssetKind.of(p) == kind)
                    .map(this::relative).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public Scene loadScene() throws IOException {
        return SceneSerializer.load(scenePath());
    }

    // ---------------------------------------------------------------- scenes

    /** The name of the first scene (main.scene). */
    public static final String MAIN_SCENE = "Main";

    public Path scenesFolder() {
        return root.resolve("scenes");
    }

    /** Every scene: "Main" first, then the ones in scenes/ by name. */
    public List<String> sceneNames() {
        List<String> names = new ArrayList<>(List.of(MAIN_SCENE));
        if (Files.isDirectory(scenesFolder())) {
            try (Stream<Path> files = Files.list(scenesFolder())) {
                files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".scene"))
                        .map(n -> n.substring(0, n.length() - ".scene".length())).sorted().forEach(names::add);
            } catch (IOException ignored) {
                // just Main
            }
        }
        return names;
    }

    /** Where a scene is saved: main.scene for "Main", else scenes/NAME.scene. */
    public Path scenePath(String name) {
        return name.equalsIgnoreCase(MAIN_SCENE) ? scenePath() : scenesFolder().resolve(name + ".scene");
    }

    /** Which scene a .scene file in this project is (e.g. "Level2"), or null if it isn't one. */
    public String sceneName(Path file) {
        Path normal = file.toAbsolutePath().normalize();
        if (normal.equals(scenePath())) return MAIN_SCENE;
        String fileName = normal.getFileName().toString();
        return scenesFolder().equals(normal.getParent()) && fileName.endsWith(".scene")
                ? fileName.substring(0, fileName.length() - ".scene".length()) : null;
    }

    public Scene loadScene(String name) throws IOException {
        Path file = scenePath(name);
        if (!Files.exists(file)) throw new IOException("there's no scene called \"" + name + "\" (scenes: " + sceneNames() + ")");
        return SceneSerializer.load(file);
    }

    public void saveScene(String name, Scene scene) throws IOException {
        Path file = scenePath(name);
        Files.createDirectories(file.getParent());
        SceneSerializer.save(scene, file);
    }

    /** Makes a new scene with just a camera; fails if one with that name exists. */
    public void createScene(String name) throws IOException {
        checkNewSceneName(name);
        Scene scene = starterScene();
        scene.name = name;
        saveScene(name, scene);
    }

    private void checkNewSceneName(String name) throws IOException {
        if (!name.matches("[A-Za-z0-9 _-]+")) throw new IOException("a scene name can only have letters, numbers, spaces, - and _");
        for (String existing : sceneNames()) {
            if (existing.equalsIgnoreCase(name)) throw new IOException("there's already a scene called " + existing);
        }
    }

    /** A copy of a scene under a new name (to make Level3 from Level2). */
    public void duplicateScene(String from, String to) throws IOException {
        checkNewSceneName(to);
        Scene scene = loadScene(from);
        scene.name = to;
        saveScene(to, scene);
    }

    /** Renames a scene's file. Main can't be renamed: it's where the game starts. */
    public void renameScene(String from, String to) throws IOException {
        if (from.equalsIgnoreCase(MAIN_SCENE)) throw new IOException("Main can't be renamed: it's the scene the game starts in");
        if (!from.equalsIgnoreCase(to)) checkNewSceneName(to);
        Files.createDirectories(scenesFolder());
        Files.move(scenePath(from), scenePath(to));
    }

    /** Moves a scene's file to the Recycle Bin (or deletes it, where there isn't one). Main can't be deleted. */
    public void deleteScene(String name) throws IOException {
        if (name.equalsIgnoreCase(MAIN_SCENE)) throw new IOException("Main can't be deleted: it's the scene the game starts in");
        Path file = scenePath(name);
        if (!Files.exists(file)) throw new IOException("there's no scene called " + name);
        boolean trashed = false;
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)) {
                trashed = java.awt.Desktop.getDesktop().moveToTrash(file.toFile());
            }
        } catch (RuntimeException ignored) {
            // no desktop (a server, a test): delete instead
        }
        if (!trashed) Files.delete(file);
    }

    // ---------------------------------------------------------------- prefabs

    /** Saved objects that can be placed again and again (and spawned by scripts). */
    public Path prefabsFolder() {
        return assetsFolder().resolve("prefabs");
    }

    public Path prefabPath(String name) {
        return prefabsFolder().resolve(name + ".prefab");
    }

    public List<String> prefabNames() {
        if (!Files.isDirectory(prefabsFolder())) return List.of();
        try (Stream<Path> files = Files.list(prefabsFolder())) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".prefab"))
                    .map(n -> n.substring(0, n.length() - ".prefab".length())).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Saves an object (its components and settings) as a prefab named after it; returns the prefab's name. */
    public String savePrefab(Entity entity) throws IOException {
        Files.createDirectories(prefabsFolder());
        String name = prefabName(entity);
        Files.writeString(prefabPath(name), Json.write(SceneSerializer.entityToMap(entity)), StandardCharsets.UTF_8);
        return name;
    }

    /** Saves an object and its children (a tank with its turret) as a prefab named after it; returns the prefab's name. */
    public String savePrefab(Scene scene, Entity entity) throws IOException {
        Files.createDirectories(prefabsFolder());
        String name = prefabName(entity);
        Files.writeString(prefabPath(name), Json.write(SceneSerializer.familyToMap(scene, entity)), StandardCharsets.UTF_8);
        return name;
    }

    private static String prefabName(Entity entity) {
        String name = entity.name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        return name.isEmpty() ? "Prefab" : name;
    }

    /** A prefab's saved data, for SceneSerializer.loadInto. */
    public Map<String, Object> loadPrefab(String name) throws IOException {
        Path file = prefabPath(name);
        if (!Files.exists(file)) throw new IOException("there's no prefab called \"" + name + "\" (prefabs: " + prefabNames() + ")");
        return Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
    }

    public void saveScene(Scene scene) throws IOException {
        SceneSerializer.save(scene, scenePath());
    }

    /** Every Seven script (.7n), sorted by name. */
    public List<Path> scripts() {
        return filesEndingWith(SCRIPT_EXTENSION);
    }

    /** Every Eights graph (.8s), sorted by name. */
    public List<Path> graphs() {
        return filesEndingWith(GraphFile.EXTENSION);
    }

    private List<Path> filesEndingWith(String extension) {
        try (Stream<Path> files = Files.list(scriptsFolder())) {
            return files.filter(p -> p.toString().endsWith(extension)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Behaviour names available to attach: one per script or graph, named after the file. */
    public List<String> behaviourNames() {
        return Stream.concat(scripts().stream(), graphs().stream())
                .map(Project::behaviourName).distinct().sorted().toList();
    }

    /** "PlayerMove.7n" or "Enemy.8s" -> "PlayerMove" / "Enemy". */
    public static String behaviourName(Path file) {
        return file.getFileName().toString().replaceFirst("\\.(7n|8s)$", "");
    }

    /** The file that defines a behaviour (the .7n script, else the .8s graph), or null. */
    public Path behaviourFile(String name) {
        for (String extension : List.of(SCRIPT_EXTENSION, GraphFile.EXTENSION)) {
            Path file = scriptsFolder().resolve(name + extension);
            if (Files.exists(file)) return file;
        }
        return null;
    }

    /** Creates scripts/Name.7n with an empty behaviour. */
    public Path createScript(String name) throws IOException {
        Path file = newBehaviourFile(name, SCRIPT_EXTENSION);
        Files.writeString(file, NEW_SCRIPT_TEMPLATE.replace("NAME", name), StandardCharsets.UTF_8);
        return file;
    }

    /** Creates scripts/Name.8s with an Eights graph containing On Start and On Update. */
    public Path createGraph(String name) throws IOException {
        Path file = newBehaviourFile(name, GraphFile.EXTENSION);
        GraphFile.save(GraphFile.starter(), file);
        return file;
    }

    /** Checks a new behaviour name is valid and unused, and returns its file path. */
    private Path newBehaviourFile(String name, String extension) throws IOException {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IOException("Use letters, numbers and _ only, starting with a letter (like PlayerMove)");
        }
        if (behaviourFile(name) != null) throw new IOException("A behaviour called " + name + " already exists");
        return scriptsFolder().resolve(name + extension);
    }

    // ---------------------------------------------------------------- starter content

    /** An empty scene with just a camera, ready for a 2D or 3D game (3D gets a soft default light). */
    private static Scene starterScene() {
        Scene scene = new Scene("Main");
        Entity camera = scene.create("Main Camera");
        camera.transform.position.set(0, 4, 9);
        camera.transform.rotation.set(-22, 0, 0);
        camera.add(new Camera());
        return scene;
    }

    private static final String NEW_SCRIPT_TEMPLATE = """
            behaviour NAME {

                func start() {

                }

                func update(dt) {

                }
            }
            """;
}
