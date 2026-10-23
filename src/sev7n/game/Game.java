package sev7n.game;

import sev7n.eights.Compiler;
import sev7n.eights.GraphFile;
import sev7n.lang.Interpreter;
import sev7n.project.Project;
import sev7n.lang.SevenError;
import sev7n.lang.runtime.BehaviourClass;
import sev7n.lang.runtime.BehaviourInstance;
import sev7n.math.Vec3;
import sev7n.scene.Animator;
import sev7n.scene.Behaviour;
import sev7n.scene.BoxCollider;
import sev7n.scene.Entity;
import sev7n.scene.MeshRenderer;
import sev7n.scene.Npc;
import sev7n.scene.Ragdoll;
import sev7n.scene.Rigidbody;
import sev7n.scene.ParticleEmitter;
import sev7n.scene.Json;
import sev7n.scene.Scene;
import sev7n.scene.SceneSerializer;
import sev7n.scene.SpriteAnimator;
import sev7n.scene.SpriteRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A running game: a scene plus its behaviours.
 *
 * Every frame (update), with dt multiplied by the time scale (0 while a pause menu is open):
 *   1. update(dt) on every behaviour, then lateUpdate(dt)
 *   2. NPCs look around, decide and walk; the clock moves on
 *   3. physics moves rigidbodies, and limp ragdolls tumble
 *   4. sprite animators pick their frames
 *   5. onCollide(other) for colliders that started touching
 *   6. destroyed entities are removed
 *
 * If a script fails, the error goes to the log and failed() becomes true.
 */
public final class Game {

    private final Scene scene;
    private final Log log;
    private final Input input;
    private final Interpreter seven;
    private final Collisions collisions = new Collisions();
    private final Physics physics = new Physics();
    private final Tweens tweens = new Tweens();
    private final Clips clips = new Clips();
    private Network network = Network.get(); // multiplayer (one per program; tests give each game its own)
    private Sound sound = new Sound(Path.of("."));
    private boolean soundHandedOver; // the scene before passed its sound on (the music keeps going)

    private final Map<Entity, List<BehaviourInstance>> behaviours = new LinkedHashMap<>();
    private final Map<Entity, EntityProxy> proxies = new HashMap<>();
    private final Map<Entity, NpcBrain> brains = new LinkedHashMap<>();
    private final Map<Entity, Limp> limp = new HashMap<>();
    private final NpcBrain.Events npcEvents = this::notify;
    private final Set<Entity> destroyQueue = new LinkedHashSet<>();
    private double time = 0;
    /** 1 = normal, 0 = the world stands still (pause menus), 0.3 = slow motion. Scripts still run. */
    private double timeScale = 1;
    private boolean quitRequested;
    private boolean restartRequested;
    private final Ui ui;
    /** The time of day in the game, 0..24 (see hour() in scripts). */
    private double hour = 8;
    /** Real seconds in one game day. */
    private double dayLength = 600;
    private boolean failed = false;

    // scenes, saves and prefabs
    private Path scriptsFolder;
    private String sceneName = "Main";
    private String saveName = "Game";
    private final Map<String, Object> values = new LinkedHashMap<>(); // setValue/getValue: kept across scenes and in saves
    private String sceneRequest;                  // loadScene() asked for this scene
    private Map<String, Object> loadRequest;      // loadGame() asked for this saved game
    private Game successor;                       // the game that took over (another scene, a loaded save)

    /** fixedUpdate(dt) runs this often (seconds), however fast the frames come. */
    static final double FIXED_STEP = 1 / 60.0;
    private double fixedTime;

    public Game(Scene scene, Log log, Input input) {
        this.scene = scene;
        this.log = log;
        this.input = input;
        this.seven = new Interpreter(log::info);
        this.ui = new Ui(input, scene.hud);
        EngineApi.install(seven, this);
    }

    // ---------------------------------------------------------------- lifecycle

    /** Loads every script, attaches behaviours and calls start(). Returns false on errors. */
    public boolean start(Path scriptsFolder) {
        this.scriptsFolder = scriptsFolder;
        if (scriptsFolder.toAbsolutePath().getParent() != null) {
            if (!soundHandedOver) sound = new Sound(scriptsFolder.toAbsolutePath().getParent());
            clips.setProjectFolder(scriptsFolder.toAbsolutePath().getParent());
            saveName = scriptsFolder.toAbsolutePath().getParent().getFileName().toString();
        }
        return guard(() -> {
            sound.update(scene); // the ears are on the camera before anything plays
            loadScripts(scriptsFolder);
            for (Entity entity : new ArrayList<>(scene.entities())) {
                for (Behaviour component : entity.getAll(Behaviour.class)) {
                    attach(entity, component.script, 0);
                }
            }
            // things set to play on start begin first, so scripts' start() can change or stop them
            startAudioSources();
            startClips();
            for (Map.Entry<Entity, List<BehaviourInstance>> entry : new ArrayList<>(behaviours.entrySet())) {
                for (BehaviourInstance instance : new ArrayList<>(entry.getValue())) {
                    seven.callMethod(instance, "start");
                }
            }
            animate(0); // so sprites show their first frame straight away
        });
    }

    /** Animation Players set to play on start begin (posed at their first frame straight away). */
    private void startClips() {
        for (Entity entity : scene.entities()) {
            sev7n.scene.AnimationPlayer player = entity.get(sev7n.scene.AnimationPlayer.class);
            if (player == null || !player.playOnStart || player.clip.isBlank()) continue;
            try {
                clips.play(scene, entity, null);
            } catch (IllegalArgumentException e) {
                log.warning(entity.name + "'s Animation Player: " + e.getMessage());
            }
        }
    }

    /** Audio Sources set to play on start begin; one that can't play says why in the console and the rest go on. */
    private void startAudioSources() {
        for (Entity entity : scene.entities()) {
            sev7n.scene.AudioSource source = entity.get(sev7n.scene.AudioSource.class);
            if (source == null || !source.playOnStart || !entity.active || source.clip.isBlank()) continue;
            try {
                sound.playSource(entity);
            } catch (IllegalArgumentException e) {
                log.warning(entity.name + "'s Audio Source: " + e.getMessage());
            }
        }
    }

    public void update(double dt) {
        if (failed) return;
        double realDt = dt;
        dt *= timeScale;
        time += dt;
        scene.hud.clear(); // scripts draw the HUD afresh every frame
        ui.beginFrame();
        final double scaled = dt;
        guard(() -> {
            network.deliver(this::networkEvent, realDt); // players joining and leaving, messages; then our shared values go out
            // fixedUpdate(dt) runs at a steady FIXED_STEP, however fast the frames come
            // (steady steps are what physics-heavy and networked games need)
            fixedTime += scaled;
            for (int steps = 0; fixedTime >= FIXED_STEP && steps < 5; steps++) {
                fixedTime -= FIXED_STEP;
                callAll("fixedUpdate", FIXED_STEP);
            }
            if (fixedTime > FIXED_STEP) fixedTime = 0; // too far behind: catch up rather than spiral
            for (Map.Entry<Entity, List<BehaviourInstance>> entry : new ArrayList<>(behaviours.entrySet())) {
                if (!entry.getKey().active || destroyQueue.contains(entry.getKey())) continue;
                for (BehaviourInstance instance : new ArrayList<>(entry.getValue())) {
                    seven.callMethod(instance, "update", scaled);
                }
            }
            // lateUpdate(dt) runs after every update: cameras that follow, menus drawn over everything else
            for (Map.Entry<Entity, List<BehaviourInstance>> entry : new ArrayList<>(behaviours.entrySet())) {
                if (!entry.getKey().active || destroyQueue.contains(entry.getKey())) continue;
                for (BehaviourInstance instance : new ArrayList<>(entry.getValue())) {
                    seven.callMethod(instance, "lateUpdate", scaled);
                }
            }
            tweens.update(scaled, seven::callFunction);
            hour = (hour + scaled * 24 / dayLength) % 24;
            updateNpcs(scaled);
            clips.update(scene, scaled, (entity, function) -> send(entity, function, new Object[0])); // before physics: riders go with animated platforms
            physics.step(scene, scaled);
            sev7n.scene.Hierarchy.update(scene); // children follow their parents
            CameraRig.update(scene, input, realDt); // Third Person Cameras follow where things ended up
            sound.update(scene);                 // ears on the camera, Audio Sources on their objects
            if (scaled > 0) updateRagdolls(scaled);
            animate(scaled);
            Collisions.Changes contacts = collisions.update(scene);
            for (Collisions.Contact contact : contacts.started()) {
                notifyCollision(contact.a(), contact.b(), "onCollide");
                notifyCollision(contact.b(), contact.a(), "onCollide");
            }
            for (Collisions.Contact contact : contacts.ended()) {
                if (!scene.contains(contact.a()) || !scene.contains(contact.b())) continue;
                notifyCollision(contact.a(), contact.b(), "onCollideEnd");
                notifyCollision(contact.b(), contact.a(), "onCollideEnd");
            }
        });
        flushDestroyed();
        fades(realDt);
        ui.endFrame();
        input.endFrame();
        if (sceneRequest != null || loadRequest != null) changeOver();
    }

    private void callAll(String method, Object... args) {
        for (Map.Entry<Entity, List<BehaviourInstance>> entry : new ArrayList<>(behaviours.entrySet())) {
            if (!entry.getKey().active || destroyQueue.contains(entry.getKey())) continue;
            for (BehaviourInstance instance : new ArrayList<>(entry.getValue())) seven.callMethod(instance, method, args);
        }
    }

    // ---------------------------------------------------------------- scenes, saves, prefabs

    /** The game that took over from this one (after loadScene or loadGame), or null. Whoever runs games switches to it. */
    public Game successor() {
        return successor;
    }

    /** Which scene this is ("Main", "Level2"...). */
    public String sceneName() {
        return sceneName;
    }

    /** The name saves are kept under (the project's, or an exported game's). */
    public void setSaveName(String name) {
        saveName = name;
    }

    Map<String, Object> values() {
        return values;
    }

    Map<Entity, List<BehaviourInstance>> behaviourInstances() {
        return behaviours;
    }

    void requestScene(String name) {
        requestScene(name, 0);
    }

    /** Switches scene at the end of the frame, or after fading to black for 'fade' seconds (then the new scene fades in). */
    void requestScene(String name, double fade) {
        if (fade <= 0) {
            sceneRequest = name;
            fadeLength = 0;
            return;
        }
        if (fadingTo != null) return; // already on the way somewhere
        fadingTo = name;
        fadeLength = fade;
        fadeTime = 0;
    }

    /** Every scene in the project ("Main" first). */
    List<String> sceneNames() {
        if (scriptsFolder == null) return List.of(sceneName);
        try {
            return Project.openOrCreate(scriptsFolder.toAbsolutePath().getParent()).sceneNames();
        } catch (IOException e) {
            return List.of(sceneName);
        }
    }

    // scene fades: out before a change, in after it (in real seconds, so they work while the game is paused too)
    private String fadingTo;
    private double fadeLength;
    private double fadeTime;
    private double fadeInLength;
    private double fadeInTime;

    /** Runs the fades and draws the black over everything; 'realDt' is unscaled by timeScale. */
    private void fades(double realDt) {
        double darkness = 0;
        if (fadingTo != null) {
            fadeTime += realDt;
            darkness = Math.min(1, fadeTime / fadeLength);
            if (fadeTime >= fadeLength) {
                sceneRequest = fadingTo;
                fadingTo = null;
            }
        } else if (fadeInTime < fadeInLength) {
            fadeInTime += realDt;
            darkness = Math.max(0, 1 - fadeInTime / fadeInLength);
        }
        if (darkness > 0) scene.hud.addPlaced(new sev7n.scene.Hud.Box(0, 0, scene.hud.width(), sev7n.scene.Hud.HEIGHT, new java.awt.Color(0, 0, 0, (int) Math.round(darkness * 255))));
    }

    /** Builds the next game (another scene, or a saved one) at the end of the frame. */
    private void changeOver() {
        String wanted = sceneRequest;
        Map<String, Object> saved = loadRequest;
        sceneRequest = null;
        loadRequest = null;
        try {
            Scene next = saved != null ? Snapshot.world(saved) : Project.openOrCreate(scriptsFolder.toAbsolutePath().getParent()).loadScene(wanted);
            Game game = new Game(next, log, input);
            game.sceneName = saved != null ? Json.string(saved, "scene", "Main") : wanted;
            game.saveName = saveName;
            game.values.putAll(values);
            game.timeScale = timeScale;
            game.hour = hour;
            game.dayLength = dayLength;
            if (fadeLength > 0 && saved == null) game.fadeInLength = fadeLength; // came through a fade: fade back in
            game.sound = sound.handOver(); // this scene's sounds stop; the music plays on into the next
            game.soundHandedOver = true;
            game.start(scriptsFolder);
            if (saved != null) Snapshot.restore(game, saved);
            successor = game;
        } catch (IOException | RuntimeException e) {
            log.error("Couldn't " + (saved != null ? "load the saved game" : "open the scene \"" + wanted + "\"") + ": " + e.getMessage());
        }
    }

    private Path saveFile(String slot) {
        String clean = slot.replaceAll("[^A-Za-z0-9 _-]", "_");
        return sev7n.Platform.dataFolder().resolve("saves").resolve(saveName).resolve(clean + ".json");
    }

    void saveGame(String slot) throws IOException {
        Path file = saveFile(slot);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(Snapshot.capture(this)), StandardCharsets.UTF_8);
    }

    /** Loads a saved game at the end of this frame; false if there's no save in that slot. */
    boolean loadGame(String slot) throws IOException {
        Path file = saveFile(slot);
        if (!Files.exists(file)) return false;
        loadRequest = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        return true;
    }

    boolean hasSave(String slot) {
        return Files.exists(saveFile(slot));
    }

    boolean deleteSave(String slot) throws IOException {
        return Files.deleteIfExists(saveFile(slot));
    }

    /** A copy of a prefab (with its behaviours started), at a position. */
    EntityProxy spawnPrefab(String name, Vec3 position) throws IOException {
        Map<String, Object> data = Project.openOrCreate(scriptsFolder.toAbsolutePath().getParent()).loadPrefab(name);
        Entity entity = SceneSerializer.placeFamily(scene, data, name, position);
        List<Entity> family = new ArrayList<>(List.of(entity));
        family.addAll(sev7n.scene.Hierarchy.descendants(scene, entity));
        for (Entity member : family) {
            for (Behaviour component : member.getAll(Behaviour.class)) {
                BehaviourInstance instance = attach(member, component.script, 0);
                seven.callMethod(instance, "start");
            }
        }
        return proxy(entity);
    }

    public Scene scene() {
        return scene;
    }

    /** True once a script called quit(): the editor stops playing, a game window closes. */
    public boolean quitRequested() {
        return quitRequested;
    }

    /** True once a script called restart(): whoever runs the game starts it again from the scene file. */
    public boolean restartRequested() {
        return restartRequested;
    }

    void quit() {
        quitRequested = true;
    }

    void restart() {
        restartRequested = true;
    }

    double timeScale() {
        return timeScale;
    }

    void setTimeScale(double scale) {
        timeScale = Math.max(0, scale);
    }

    Ui ui() {
        return ui;
    }

    /** The game is over (Stop pressed, window closed): silences its sounds and music. */
    public void stop() {
        sound.stopAll();
        if (network == Network.get()) Network.shutdown();
        else network.close();
    }

    // ---------------------------------------------------------------- multiplayer

    Network network() {
        return network;
    }

    /** For tests: two games in one program, each with its own connection. */
    public void useOwnNetwork() {
        network = new Network();
    }

    private void networkEvent(Network.Event event) {
        switch (event.kind()) {
            case "connected" -> callAll("onConnected", (double) event.player());
            case "disconnected" -> callAll("onDisconnected");
            case "joined" -> callAll("onPlayerJoined", (double) event.player());
            case "left" -> callAll("onPlayerLeft", (double) event.player());
            case "message" -> callAll("onMessage", event.name(), event.value(), (double) event.player());
            default -> log.warning("Network: " + event.name());
        }
    }

    /** Every name scripts get for free (core + engine functions), e.g. for syntax highlighting. */
    public static Set<String> builtinNames() {
        return new Game(new Scene("probe"), new Log(), new Input()).seven.globalNames();
    }

    public boolean failed() {
        return failed;
    }

    // ---------------------------------------------------------------- used by scripts

    Input input() {
        return input;
    }

    double time() {
        return time;
    }

    Physics physics() {
        return physics;
    }

    Sound sound() {
        return sound;
    }

    /** The game's audio engine (the editor's Audio Mixer shows its levels and changes its volumes live). */
    public sev7n.audio.AudioMixer audioMixer() {
        return sound.mixer();
    }

    Tweens tweens() {
        return tweens;
    }

    Clips clips() {
        return clips;
    }

    /** True if a point is inside something solid: a non-trigger collider or a solid tile. */
    boolean solidAt(Vec3 point) {
        return Physics.solidAt(scene, point);
    }

    EntityProxy proxy(Entity entity) {
        return proxies.computeIfAbsent(entity, e -> new EntityProxy(e, this));
    }

    /** Creates a new entity with a mesh and a collider, e.g. for bullets or pickups. */
    EntityProxy spawn(String mesh, Vec3 position) {
        String name = mesh.contains("/") ? mesh.substring(mesh.lastIndexOf('/') + 1).replaceFirst("\\.\\w+$", "") : mesh;
        Entity entity = scene.create(scene.uniqueName(Character.toUpperCase(name.charAt(0)) + name.substring(1)));
        MeshRenderer renderer = entity.add(new MeshRenderer());
        renderer.mesh = mesh;
        entity.add(new BoxCollider());
        entity.transform.position.set(position);
        return proxy(entity);
    }

    /** Removes an entity (and its children) at the end of the frame. */
    void destroy(Entity entity) {
        destroyQueue.add(entity);
        destroyQueue.addAll(sev7n.scene.Hierarchy.descendants(scene, entity));
    }

    BehaviourInstance addBehaviour(Entity entity, String name, int line) {
        entity.add(new Behaviour(name));
        BehaviourInstance instance = attach(entity, name, line);
        seven.callMethod(instance, "start");
        return instance;
    }

    BehaviourInstance findBehaviour(Entity entity, String name) {
        for (BehaviourInstance instance : behaviours.getOrDefault(entity, List.of())) {
            if (instance.type().name().equals(name)) return instance;
        }
        return null;
    }

    // ---------------------------------------------------------------- internals

    /** Runs every Seven script, and every Eights graph after compiling it to Seven. */
    private void loadScripts(Path folder) {
        Map<String, String> sources = readScripts(folder);
        // check them all first, so a typo in one file doesn't leave the others half loaded
        sources.forEach((fileName, source) -> seven.check(source, fileName));
        sources.forEach((fileName, source) -> seven.run(source, fileName));
    }

    /** Every script's Seven code by file name, in file name order. */
    private static Map<String, String> readScripts(Path folder) {
        Map<String, String> sources = new LinkedHashMap<>();
        if (!Files.isDirectory(folder)) return sources;
        List<Path> files;
        try (Stream<Path> list = Files.list(folder)) {
            files = list.filter(Game::isScript).sorted().toList();
        } catch (IOException e) {
            throw new SevenError("Couldn't read the scripts folder: " + e.getMessage(), 0);
        }
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            try {
                sources.put(fileName, fileName.endsWith(GraphFile.EXTENSION)
                        ? Compiler.compile(GraphFile.load(file), Project.behaviourName(file)).source()
                        : Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | IllegalArgumentException e) {
                throw new SevenError("Couldn't read " + fileName + ": " + e.getMessage(), 0);
            }
        }
        return sources;
    }

    public static boolean isScript(Path file) {
        String name = file.toString();
        return name.endsWith(".7n") || name.endsWith(GraphFile.EXTENSION);
    }

    /** Creates a behaviour instance for an entity, wired to 'entity' and 'transform'. */
    private BehaviourInstance attach(Entity entity, String name, int line) {
        BehaviourInstance instance = instantiate(entity, name, line);
        behaviours.computeIfAbsent(entity, e -> new ArrayList<>()).add(instance);
        return instance;
    }

    private BehaviourInstance instantiate(Entity entity, String name, int line) {
        if (name == null || name.isBlank()) {
            throw new SevenError("'" + entity.name + "' has a Behaviour with no script chosen", line);
        }
        if (!(seven.lookupGlobal(name) instanceof BehaviourClass type)) {
            throw new SevenError("No behaviour called '" + name + "' (used by '" + entity.name + "')."
                    + " Is there a 'behaviour " + name + " { }' in a script?", line);
        }
        return seven.instantiate(type, bindings(entity));
    }

    private Map<String, Object> bindings(Entity entity) {
        EntityProxy proxy = proxy(entity);
        return Map.of("entity", proxy, "transform", proxy.transform());
    }

    // ---------------------------------------------------------------- live mode

    /**
     * Live Mode: runs the scripts again and moves every behaviour onto its new code without
     * restarting. Fields keep their values unless their starting value was edited.
     * If a script has an error, it's logged and the old code keeps running.
     * A game stopped by a script error carries on once the reload works.
     */
    public boolean reloadScripts(Path scriptsFolder) {
        try {
            loadScripts(scriptsFolder);
            for (Map.Entry<Entity, List<BehaviourInstance>> entry : behaviours.entrySet()) {
                for (BehaviourInstance instance : entry.getValue()) {
                    if (seven.lookupGlobal(instance.type().name()) instanceof BehaviourClass type) {
                        seven.upgrade(instance, type, bindings(entry.getKey()));
                    }
                }
            }
        } catch (SevenError e) {
            log.error(e);
            return false;
        } catch (StackOverflowError | RuntimeException e) {
            log.error("Couldn't reload the scripts: " + e);
            return false;
        }
        failed = false;
        return true;
    }

    /**
     * Live Mode: matches running behaviours to the Behaviour components after they were
     * edited while playing: new ones are created (and started), removed ones stop.
     * Behaviours with no script chosen yet are skipped.
     */
    public void syncBehaviours() {
        if (failed) return;
        guard(() -> {
            for (Entity entity : scene.entities()) {
                List<String> wanted = new ArrayList<>();
                for (Behaviour component : entity.getAll(Behaviour.class)) {
                    if (seven.lookupGlobal(component.script) instanceof BehaviourClass) wanted.add(component.script);
                }
                List<BehaviourInstance> running = behaviours.getOrDefault(entity, List.of());
                if (wanted.equals(running.stream().map(i -> i.type().name()).toList())) continue;

                List<BehaviourInstance> unused = new ArrayList<>(running);
                List<BehaviourInstance> kept = new ArrayList<>();
                List<BehaviourInstance> added = new ArrayList<>();
                for (String name : wanted) {
                    BehaviourInstance match = unused.stream().filter(i -> i.type().name().equals(name)).findFirst().orElse(null);
                    if (match != null) unused.remove(match);
                    else added.add(match = instantiate(entity, name, 0));
                    kept.add(match);
                }
                if (kept.isEmpty()) behaviours.remove(entity);
                else behaviours.put(entity, kept);
                for (BehaviourInstance instance : added) seven.callMethod(instance, "start");
            }
        });
    }

    /** Moves every Sprite Animator and particle effect along. Finished one-off effects are removed. */
    private void animate(double dt) {
        Solids solids = null; // gathered once, only if some effect bounces off things
        for (Entity entity : scene.entities()) {
            if (!entity.active) continue;
            SpriteAnimator animator = entity.get(SpriteAnimator.class);
            SpriteRenderer sprite = entity.get(SpriteRenderer.class);
            if (animator != null && sprite != null) animator.update(dt, sprite);
            Animator modelAnimator = entity.get(Animator.class);
            if (modelAnimator != null) modelAnimator.update(dt);
            ParticleEmitter emitter = entity.get(ParticleEmitter.class);
            if (emitter != null) {
                if (emitter.collide && solids == null) solids = Solids.of(scene);
                emitter.update(dt, entity.transform, solids);
                if (emitter.destroyWhenDone && emitter.done()) destroy(entity);
            }
        }
    }

    /** A one-off particle effect from a preset (e.g. "Explosion") that removes itself when it's over. */
    EntityProxy spawnEffect(String preset, Vec3 position) {
        Entity entity = scene.create(scene.uniqueName(preset));
        ParticleEmitter emitter = entity.add(ParticleEmitter.preset(preset));
        emitter.loop = false;
        emitter.destroyWhenDone = true;
        entity.transform.position.set(position);
        return proxy(entity);
    }

    // ---------------------------------------------------------------- ragdolls

    /** A limp body, plus where the entity sits relative to its hips and how high its origin is above its feet. */
    private record Limp(RagdollSim sim, Vec3 offset, double feet, int head) { }

    /**
     * Starts, moves and ends ragdolls: a Ragdoll switched to active starts falling from the pose
     * it was drawn in (keeping its Rigidbody's speed); switched back, the entity stands up where
     * its hips ended up. While limp, the entity follows its hips, so cameras following it still work.
     */
    private void updateRagdolls(double dt) {
        for (Entity entity : scene.entities()) {
            Ragdoll ragdoll = entity.get(Ragdoll.class);
            if (ragdoll == null || !entity.active) continue;
            Limp body = limp.get(entity);
            if (ragdoll.active && body == null) {
                if (ragdoll.skeleton == null || ragdoll.pose == null) {
                    ragdoll.active = false; // never drawn yet, so there's no pose to fall from
                    continue;
                }
                body = goLimp(entity, ragdoll);
                limp.put(entity, body);
            }
            if (ragdoll.active) {
                body.sim().step(scene, dt, physics.gravity, entity);
                ragdoll.points = body.sim().positions();
                entity.transform.position.set(body.sim().position(0).minus(body.offset()));
            } else if (body != null) {
                standUp(entity, body);
                limp.remove(entity);
            }
        }
    }

    /** True while an entity's ragdoll lies still (it fell and stopped moving); false if it's moving or not limp. */
    public boolean ragdollResting(Entity entity) {
        Limp body = limp.get(entity);
        return body != null && body.sim().resting();
    }

    private Limp goLimp(Entity entity, Ragdoll ragdoll) {
        entity.transform.position.set(ragdoll.poseOrigin); // where it was in the pose it falls from
        Vec3 velocity = new Vec3(ragdoll.pushX, ragdoll.pushY, ragdoll.pushZ);
        ragdoll.pushX = ragdoll.pushY = ragdoll.pushZ = 0;
        Rigidbody body = entity.get(Rigidbody.class);
        if (body != null) {
            velocity = velocity.plus(body.velocity);
            body.velocity.set(0, 0, 0);
        }
        RagdollSim sim = new RagdollSim(ragdoll.skeleton, ragdoll.pose, ragdoll.poseForward, velocity, ragdoll);
        ragdoll.points = sim.positions();
        ragdoll.fall++;
        Vec3 hips = sim.position(0);
        int head = 0; // the highest bone while standing
        for (int b = 1; b < ragdoll.skeleton.size(); b++) if (ragdoll.pose[b * 3 + 1] > ragdoll.pose[head * 3 + 1]) head = b;
        return new Limp(sim, hips.minus(entity.transform.position), entity.transform.position.y - sim.lowest(), head);
    }

    /**
     * Puts the entity back on its feet under where its hips are, facing from its head toward its
     * feet: the way get-up animations stand up from lying on the back.
     */
    private void standUp(Entity entity, Limp body) {
        Vec3 hips = body.sim().position(0);
        Vec3 towardFeet = hips.minus(body.sim().position(body.head()));
        if (Math.hypot(towardFeet.x, towardFeet.z) > 0.1) {
            entity.transform.rotation.y = Math.toDegrees(Math.atan2(-towardFeet.x, -towardFeet.z));
        }
        double ground = body.sim().lowest();
        Physics.RayHit hit = Physics.raycast(scene, hips, new Vec3(0, -1, 0), 10, entity);
        if (hit != null) ground = hit.point().y;
        entity.transform.position.set(hips.x, ground + body.feet(), hips.z);
    }

    // ---------------------------------------------------------------- NPCs

    double hour() {
        return hour;
    }

    void setHour(double value) {
        hour = ((value % 24) + 24) % 24;
    }

    double dayLength() {
        return dayLength;
    }

    void setDayLength(double seconds) {
        dayLength = Math.max(1, seconds);
    }

    /** The mind of an entity with an NPC component (made the first time it's needed), or null. */
    NpcBrain brain(Entity entity) {
        Npc npc = entity.get(Npc.class);
        if (npc == null) return null;
        NpcBrain brain = brains.get(entity);
        if (brain == null || brain.npc != npc) brains.put(entity, brain = new NpcBrain(entity, npc));
        return brain;
    }

    private void updateNpcs(double dt) {
        for (Entity entity : new ArrayList<>(scene.entities())) {
            if (!entity.active || destroyQueue.contains(entity) || Ragdoll.limp(entity)) continue;
            NpcBrain brain = brain(entity);
            if (brain != null) brain.update(scene, dt, time, hour, npcEvents);
        }
    }

    /** A noise NPCs can hear: those within their hearing range (times loudness) react. */
    void makeNoise(Vec3 position, double loudness, Entity source) {
        for (Entity entity : new ArrayList<>(scene.entities())) {
            if (entity == source || !entity.active) continue;
            NpcBrain brain = brain(entity);
            if (brain != null && entity.transform.position.distance(position) <= brain.npc.hearing * loudness) {
                brain.hear(position, source, time, npcEvents);
            }
        }
    }

    /** Calls a method (e.g. "onSee") on every behaviour of an entity; entities in the arguments become script entities. */
    private void notify(Entity self, String method, Object... args) {
        if (destroyQueue.contains(self)) return;
        Object[] values = args.clone();
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Entity e) values[i] = proxy(e);
        }
        for (BehaviourInstance instance : new ArrayList<>(behaviours.getOrDefault(self, List.of()))) {
            seven.callMethod(instance, method, values);
        }
    }

    /** entity.send(...): calls a function on every behaviour of an entity that has it. */
    void send(Entity target, String method, Object... args) {
        notify(target, method, args);
    }

    private void notifyCollision(Entity self, Entity other, String method) {
        if (destroyQueue.contains(self)) return;
        for (BehaviourInstance instance : new ArrayList<>(behaviours.getOrDefault(self, List.of()))) {
            seven.callMethod(instance, method, proxy(other));
        }
    }

    private void flushDestroyed() {
        for (Entity entity : destroyQueue) {
            scene.remove(entity);
            behaviours.remove(entity);
            proxies.remove(entity);
            collisions.forget(entity);
            tweens.forget(entity);
            brains.remove(entity);
            limp.remove(entity);
            for (NpcBrain brain : brains.values()) brain.forget(entity);
        }
        destroyQueue.clear();
    }

    /** Runs script work; a script error is logged and stops the game instead of crashing the editor. */
    private boolean guard(Runnable work) {
        try {
            work.run();
            return true;
        } catch (SevenError e) {
            log.error(e);
        } catch (StackOverflowError e) {
            log.error("Too much recursion: a function keeps calling itself forever");
        } catch (RuntimeException e) {
            log.error("Engine error: " + e);
        }
        failed = true;
        return false;
    }
}
