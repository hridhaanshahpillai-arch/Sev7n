package sev7n.editor;

import sev7n.Sev7n;
import sev7n.eights.GraphFile;
import sev7n.game.Game;
import sev7n.game.Input;
import sev7n.game.Log;
import sev7n.math.Vec3;
import sev7n.project.AssetImporter;
import sev7n.project.AssetKind;
import sev7n.project.Project;
import sev7n.render.ObjLoader;
import sev7n.render.RenderSettings;
import sev7n.render.SceneRenderer;
import sev7n.render.Texture;
import sev7n.scene.BoxCollider;
import sev7n.scene.Camera;
import sev7n.scene.Entity;
import sev7n.scene.Light;
import sev7n.scene.MeshRenderer;
import sev7n.scene.Scene;
import sev7n.scene.SceneSerializer;
import sev7n.scene.SpriteAnimator;
import sev7n.scene.SpriteRenderer;
import sev7n.scene.Tilemap;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Everything the editor panels share: the project, the scene being edited,
 * the selection, and play mode.
 *
 * Play runs on a copy of the scene, so pressing Stop puts everything back
 * exactly as it was (like Unity).
 */
final class EditorContext {

    /** Things the Create menus can make. */
    static final List<String> CREATABLE = List.of("Empty", "Cube", "Sphere", "Plane", "Cylinder", "Sprite", "Tilemap", "Camera", "Light");

    final Project project;
    final Log log = new Log();
    final Input input = new Input();
    final SceneRenderer renderer;
    final Set<String> builtinNames = Game.builtinNames();
    ScriptEditorPanel scripts; // set once the panels exist
    EightsPanel eights;
    SpriteEditorPanel spriteEditor;

    private Scene editScene;
    private Game game; // non-null while playing
    private boolean paused;
    private boolean stoppedByError; // Live Mode pauses on script errors instead of stopping
    private final LiveEdits liveEdits = new LiveEdits();
    private Map<Path, Long> scriptTimes = Map.of();
    private double scriptCheckTimer = 0;
    private long selectedId = -1;
    private boolean sceneDirty;

    // background work (importing) hands results back to the main thread through this queue
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sev7n-import");
        thread.setDaemon(true);
        return thread;
    });
    private final Queue<Runnable> mainThread = new ConcurrentLinkedQueue<>();
    private int importsRunning = 0;
    private int assetsVersion = 0;

    // editor tools
    private long paintingId = -1;    // the tilemap being painted, or -1
    private int paintTile = 0;       // the tile to paint, or Tilemap.EMPTY to erase
    private long previewingId = -1;  // the entity whose animation plays in the editor, or -1

    /** How the editor runs on this PC. */
    final EditorSettings editorSettings = EditorSettings.load();
    /** How the game looks and runs (saved with the project, so exports match). */
    final RenderSettings graphics;

    EditorContext(Project project, Scene scene, SceneRenderer renderer) {
        this.project = project;
        this.editScene = scene;
        this.renderer = renderer;
        this.graphics = RenderSettings.load(project.root().resolve(RenderSettings.FILE_NAME));
        renderer.setSettings(graphics);
        renderer.assets().setRoot(project.root());
        renderer.assets().onProblem(log::warning);
    }

    /** A picture to show in the editor's panels, or null. The editor always draws with OpenGL. */
    Texture texture(String path, boolean pixelArt, boolean removeBackground) {
        return (Texture) renderer.assets().texture(path, pixelArt, removeBackground);
    }

    /** Saves the game graphics settings next to the scene. */
    void saveGraphics() {
        try {
            graphics.save(project.root().resolve(RenderSettings.FILE_NAME));
        } catch (IOException e) {
            log.error("Couldn't save the graphics settings: " + e.getMessage());
        }
    }

    /** Runs something on the main (UI) thread at the start of the next frame. Safe from any thread. */
    void post(Runnable task) {
        mainThread.add(task);
    }

    /** Runs slow work (importing, exporting) off the UI thread, one job at a time. */
    void inBackground(Runnable task) {
        background.submit(task);
    }

    // ---------------------------------------------------------------- importing assets

    /** Imports files in the background; results show up in the Console. */
    void importFiles(List<Path> files) {
        if (files.isEmpty()) return;
        importsRunning++;
        log.system("Importing " + files.size() + (files.size() == 1 ? " file..." : " files..."));
        background.submit(() -> {
            AssetImporter importer = new AssetImporter(project, message -> post(() -> {
                if (message.problem()) log.warning(message.text());
                else log.info(message.text());
            }));
            importer.importAll(files);
            post(() -> {
                importsRunning--;
                assetsVersion++;
            });
        });
    }

    /** Converts a .blend or .fbx that's already in the project into a .glb next to it (needs Blender). */
    void convertModel(Path file) {
        importsRunning++;
        log.system("Converting " + file.getFileName() + " with Blender...");
        background.submit(() -> {
            new AssetImporter(project, message -> post(() -> {
                if (message.problem()) log.warning(message.text());
                else log.info(message.text());
            })).convert(file);
            post(() -> {
                importsRunning--;
                assetsVersion++;
            });
        });
    }

    boolean importing() {
        return importsRunning > 0;
    }

    /** Bumps when imports finish, so the Project panel knows to look at the folder again. */
    int assetsVersion() {
        return assetsVersion;
    }

    /** Makes an object from an imported picture (a sprite) or model, standing on the ground at a position. */
    Entity createFromAsset(String path, Vec3 position) {
        return createFromAsset(path, position, true);
    }

    /** Like createFromAsset, but 2D placement (standOnGround false) keeps a sprite centered on the point. */
    Entity createFromAsset(String path, Vec3 position, boolean standOnGround) {
        AssetKind kind = AssetKind.of(Path.of(path));
        if (kind != AssetKind.TEXTURE && kind != AssetKind.MODEL) {
            log.warning(Path.of(path).getFileName() + " can't be placed in the scene (only pictures and .glb / .gltf / .obj models)");
            return null;
        }
        Scene scene = scene();
        Entity entity = scene.create(scene.uniqueName(AssetKind.baseName(Path.of(path))));
        entity.transform.position.set(position);
        if (kind == AssetKind.TEXTURE) {
            SpriteRenderer sprite = entity.add(new SpriteRenderer());
            sprite.texture = path;
            if (standOnGround) entity.transform.position.y += sprite.size / 2; // not halfway through the ground
        } else {
            MeshRenderer mesh = entity.add(new MeshRenderer());
            mesh.mesh = path;
            applyModelMaterial(mesh, path);
            entity.add(new BoxCollider());
        }
        select(entity);
        markDirty();
        return entity;
    }

    /**
     * Shows a model in its own colors. .glb/.gltf models carry their materials inside, so the
     * renderer just stays white; an .obj's color and texture come from its .mtl file.
     */
    private void applyModelMaterial(MeshRenderer mesh, String modelPath) {
        if (!modelPath.toLowerCase().endsWith(".obj")) {
            mesh.color = Color.WHITE;
            return;
        }
        try {
            ObjLoader.Material material = ObjLoader.material(project.root().resolve(modelPath));
            mesh.color = material.color() != null ? material.color() : Color.WHITE;
            if (material.texture() != null) {
                mesh.texture = project.relative(material.texture());
                mesh.color = Color.WHITE; // let the texture show its real colors
            }
        } catch (IOException e) {
            mesh.color = Color.WHITE;
        }
    }

    /** Dropping a picture on an object: put it on its sprite, or wrap its mesh in it. */
    void applyTexture(Entity entity, String path) {
        SpriteRenderer sprite = entity.get(SpriteRenderer.class);
        MeshRenderer mesh = entity.get(MeshRenderer.class);
        if (sprite != null) sprite.texture = path;
        else if (mesh != null) mesh.texture = path;
        else entity.add(new SpriteRenderer()).texture = path;
        select(entity);
        markDirty();
    }

    // ---------------------------------------------------------------- scene & selection

    /** The scene panels should show: the running copy while playing, otherwise the edited one. */
    Scene scene() {
        return game != null ? game.scene() : editScene;
    }

    Entity selected() {
        return selectedId < 0 ? null : scene().findById(selectedId);
    }

    void select(Entity entity) {
        selectedId = entity == null ? -1 : entity.id();
    }

    /**
     * Call after any edit. Edits made while playing are thrown away on Stop, so they don't
     * count (in Live Mode they're copied to the edited scene, which marks it changed then).
     */
    void markDirty() {
        if (!playing()) sceneDirty = true;
    }

    /** Live Mode is on and the game is running: editor changes are kept. */
    boolean live() {
        return playing() && editorSettings.liveMode;
    }

    boolean sceneDirty() {
        return sceneDirty;
    }

    boolean anythingUnsaved() {
        return sceneDirty || scripts.anyModified() || eights.anyModified();
    }

    Entity create(String kind) {
        Scene scene = scene();
        Entity entity = scene.create(scene.uniqueName(kind.equals("Empty") ? "Entity" : kind));
        switch (kind) {
            case "Camera" -> {
                entity.transform.position.set(0, 2, 6);
                entity.add(new Camera());
            }
            case "Light" -> {
                entity.name = scene.uniqueName("Light");
                entity.transform.position.set(0, 5, 0);
                entity.transform.rotation.set(-50, 30, 0);
                entity.add(new Light());
            }
            case "Empty" -> { }
            case "Tilemap" -> {
                entity.add(new Tilemap());
                paintTiles(entity);
            }
            case "Sprite" -> {
                entity.transform.position.set(0, 0.5, 0);
                entity.add(new SpriteRenderer());
            }
            default -> {
                MeshRenderer renderer = entity.add(new MeshRenderer());
                renderer.mesh = kind.toLowerCase();
                if (kind.equals("Plane")) entity.transform.scale.set(5, 1, 5);
                else {
                    entity.transform.position.set(0, 0.5, 0);
                    entity.add(new BoxCollider());
                }
            }
        }
        select(entity);
        markDirty();
        return entity;
    }

    void duplicate(Entity entity) {
        if (entity == null) return;
        select(scene().duplicate(entity));
        markDirty();
    }

    void delete(Entity entity) {
        if (entity == null) return;
        scene().remove(entity);
        if (entity.id() == selectedId) select(null);
        markDirty();
    }

    // ---------------------------------------------------------------- tile painting & animation preview

    /** Starts painting tiles on this entity's Tilemap in the Scene view, or stops painting (null). */
    void paintTiles(Entity entity) {
        paintingId = entity == null ? -1 : entity.id();
    }

    boolean paintingTiles(Entity entity) {
        return entity != null && entity.id() == paintingId;
    }

    /** The tilemap being painted, if it's still selected; painting stops when the selection changes. */
    Entity paintingTilemap() {
        Entity selected = selected();
        return paintingTiles(selected) && selected.has(Tilemap.class) ? selected : null;
    }

    int paintTile() {
        return paintTile;
    }

    void setPaintTile(int tile) {
        paintTile = tile;
    }

    /** Plays an entity's Sprite Animator in the editor (null stops). */
    void preview(Entity entity) {
        previewingId = entity == null ? -1 : entity.id();
    }

    boolean previewing(Entity entity) {
        return entity != null && entity.id() == previewingId;
    }

    // ---------------------------------------------------------------- saving

    void saveAll() {
        scripts.saveAll();
        eights.saveAll();
        if (playing() && !live()) {
            log.warning("Scene not saved: stop playing first (changes made during Play aren't kept; turn on Live Mode to keep them)");
            return;
        }
        if (live()) {
            liveEdits.apply(game.scene(), editScene); // this frame's edits too
            liveEdits.capture(game.scene());
        }
        try {
            project.saveScene(editScene);
            sceneDirty = false;
            log.system("Saved " + project.scenePath().getFileName());
        } catch (IOException e) {
            log.error("Couldn't save the scene: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- play mode

    boolean playing() {
        return game != null;
    }

    boolean paused() {
        return paused;
    }

    void togglePause() {
        if (playing()) paused = !paused;
    }

    void togglePlay() {
        if (playing()) stop();
        else play();
    }

    void play() {
        scripts.saveAll(); // scripts and graphs are read from disk, so make sure they're current
        eights.saveAll();
        input.reset();
        game = new Game(SceneSerializer.copy(editScene), log, input);
        log.system("Playing " + project.name() + "  (click the Game view to give it the keyboard)"
                + (editorSettings.liveMode ? "  ·  Live Mode: your edits are kept, saved scripts reload" : ""));
        if (!game.start(project.scriptsFolder())) {
            game = null;
            log.system("Stopped: fix the error above, then press Play again");
            return;
        }
        liveEdits.capture(game.scene());
        scriptTimes = scriptTimes();
    }

    void stop() {
        if (game == null) return;
        if (live()) liveEdits.apply(game.scene(), editScene);
        game = null;
        paused = false;
        stoppedByError = false;
        input.reset();
        log.system(editorSettings.liveMode ? "Stopped (Live Mode kept your changes)" : "Stopped");
    }

    /**
     * Plays the game in its own window, the way it runs when exported (with the graphics API
     * chosen in Settings). Uses the scene as it is now, saved or not; its output goes to the Console.
     */
    void playInWindow() {
        scripts.saveAll();
        eights.saveAll();
        try {
            Path scene = Files.createTempFile("sev7n-play-", ".scene");
            scene.toFile().deleteOnExit();
            SceneSerializer.save(editScene, scene);
            List<String> command = new ArrayList<>(Sev7n.javaCommand());
            command.addAll(List.of("--play", project.root().toString(), "--scene", scene.toString()));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            log.system("Playing " + project.name() + " in a window with " + graphics.api.label + "...");
            Thread reader = new Thread(() -> {
                try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = output.readLine()) != null; ) {
                        String text = line;
                        post(() -> log.info("[window] " + text));
                    }
                } catch (IOException ignored) {
                    // the window closed
                }
            }, "sev7n-play-window");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            log.error("Couldn't open the game window: " + e.getMessage());
        }
    }

    /** Turns Live Mode on or off; turning it on while playing starts keeping edits from now on. */
    void setLiveMode(boolean on) {
        if (on == editorSettings.liveMode) return;
        editorSettings.liveMode = on;
        editorSettings.save();
        if (playing()) {
            liveEdits.capture(game.scene());
            scriptTimes = scriptTimes();
        }
    }

    /** Runs queued main-thread work and advances the running game, if any. Called once per frame. */
    void update(double dt) {
        for (Runnable task; (task = mainThread.poll()) != null; ) task.run();
        if (game == null) previewAnimation(dt);
        if (game == null) return;
        if (live()) {
            // everything that changed since the last update was done in the editor
            if (liveEdits.apply(game.scene(), editScene)) {
                sceneDirty = true;
                game.syncBehaviours();
            }
            reloadChangedScripts(dt);
        }
        if (!paused) game.update(dt);
        if (game.failed() && !stoppedByError) {
            if (live()) {
                paused = true;
                stoppedByError = true;
                log.system("Paused by the error above. Fix the script and save: the game carries on from here.");
            } else {
                stop();
                log.system("Stopped because of the error above. Click it to jump to the line.");
            }
        }
        if (live()) liveEdits.capture(game.scene());
    }

    /** Live Mode: reloads the scripts when any of them is saved (here or in another editor). */
    private void reloadChangedScripts(double dt) {
        scriptCheckTimer -= dt;
        if (scriptCheckTimer > 0) return;
        scriptCheckTimer = 0.25;
        Map<Path, Long> now = scriptTimes();
        if (now.equals(scriptTimes)) return;
        scriptTimes = now;
        if (game.reloadScripts(project.scriptsFolder())) {
            log.system("Reloaded scripts" + (stoppedByError ? ": carrying on" : ""));
            if (stoppedByError) paused = false;
            stoppedByError = false;
            game.syncBehaviours();
        } else {
            log.system("Still running the old version: fix the error above and save again");
        }
    }

    private Map<Path, Long> scriptTimes() {
        Map<Path, Long> times = new HashMap<>();
        try (Stream<Path> files = Files.list(project.scriptsFolder())) {
            files.filter(Game::isScript).forEach(file -> {
                try {
                    times.put(file, Files.getLastModifiedTime(file).toMillis());
                } catch (IOException ignored) {
                    // deleted between listing and reading: it's gone either way
                }
            });
        } catch (IOException ignored) {
            // no scripts folder yet
        }
        return times;
    }

    private void previewAnimation(double dt) {
        Entity entity = previewingId < 0 ? null : editScene.findById(previewingId);
        SpriteAnimator animator = entity == null ? null : entity.get(SpriteAnimator.class);
        SpriteRenderer sprite = entity == null ? null : entity.get(SpriteRenderer.class);
        if (animator != null && sprite != null) animator.update(dt, sprite);
        else previewingId = -1;
    }

    // ---------------------------------------------------------------- scripts

    /** Opens a Seven script in the Script panel, or an Eights graph in the Eights panel. */
    void openScript(Path file, int line) {
        if (file.toString().endsWith(GraphFile.EXTENSION)) eights.open(file, line);
        else scripts.open(file, line);
    }

    /** Opens a script by file name (e.g. from a console error), relative to the scripts folder. */
    void openScript(String fileName, int line) {
        openScript(project.scriptsFolder().resolve(fileName), line);
    }
}
