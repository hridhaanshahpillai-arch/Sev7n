package sev7n.game;

import sev7n.eights.Compiler;
import sev7n.eights.GraphFile;
import sev7n.lang.Interpreter;
import sev7n.project.Project;
import sev7n.lang.SevenError;
import sev7n.lang.runtime.BehaviourClass;
import sev7n.lang.runtime.BehaviourInstance;
import sev7n.math.Vec3;
import sev7n.scene.Behaviour;
import sev7n.scene.BoxCollider;
import sev7n.scene.Entity;
import sev7n.scene.MeshRenderer;
import sev7n.scene.Scene;
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
 * Every frame (update):
 *   1. update(dt) on every behaviour
 *   2. physics moves rigidbodies
 *   3. sprite animators pick their frames
 *   4. onCollide(other) for colliders that started touching
 *   5. destroyed entities are removed
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

    private final Map<Entity, List<BehaviourInstance>> behaviours = new LinkedHashMap<>();
    private final Map<Entity, EntityProxy> proxies = new HashMap<>();
    private final Set<Entity> destroyQueue = new LinkedHashSet<>();
    private double time = 0;
    private boolean failed = false;

    public Game(Scene scene, Log log, Input input) {
        this.scene = scene;
        this.log = log;
        this.input = input;
        this.seven = new Interpreter(log::info);
        EngineApi.install(seven, this);
    }

    // ---------------------------------------------------------------- lifecycle

    /** Loads every script, attaches behaviours and calls start(). Returns false on errors. */
    public boolean start(Path scriptsFolder) {
        return guard(() -> {
            loadScripts(scriptsFolder);
            for (Entity entity : new ArrayList<>(scene.entities())) {
                for (Behaviour component : entity.getAll(Behaviour.class)) {
                    attach(entity, component.script, 0);
                }
            }
            for (Map.Entry<Entity, List<BehaviourInstance>> entry : new ArrayList<>(behaviours.entrySet())) {
                for (BehaviourInstance instance : new ArrayList<>(entry.getValue())) {
                    seven.callMethod(instance, "start");
                }
            }
            animate(0); // so sprites show their first frame straight away
        });
    }

    public void update(double dt) {
        if (failed) return;
        time += dt;
        guard(() -> {
            for (Map.Entry<Entity, List<BehaviourInstance>> entry : new ArrayList<>(behaviours.entrySet())) {
                if (!entry.getKey().active || destroyQueue.contains(entry.getKey())) continue;
                for (BehaviourInstance instance : new ArrayList<>(entry.getValue())) {
                    seven.callMethod(instance, "update", dt);
                }
            }
            physics.step(scene, dt);
            animate(dt);
            for (Collisions.Contact contact : collisions.newContacts(scene)) {
                notifyCollision(contact.a(), contact.b());
                notifyCollision(contact.b(), contact.a());
            }
        });
        flushDestroyed();
        input.endFrame();
    }

    public Scene scene() {
        return scene;
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

    /** True if a point is inside something solid: a non-trigger collider or a solid tile. */
    boolean solidAt(Vec3 point) {
        Box probe = new Box(point.x, point.y, point.z, point.x, point.y, point.z);
        for (Entity entity : scene.entities()) {
            BoxCollider collider = entity.get(BoxCollider.class);
            if (entity.active && collider != null && !collider.trigger && Box.of(entity, collider).touches(probe, 0)) return true;
        }
        return Physics.solidTileAt(scene, point);
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

    void destroy(Entity entity) {
        destroyQueue.add(entity);
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

    /** Moves every Sprite Animator along and shows the right frame. */
    private void animate(double dt) {
        for (Entity entity : scene.entities()) {
            SpriteAnimator animator = entity.get(SpriteAnimator.class);
            SpriteRenderer sprite = entity.get(SpriteRenderer.class);
            if (entity.active && animator != null && sprite != null) animator.update(dt, sprite);
        }
    }

    private void notifyCollision(Entity self, Entity other) {
        if (destroyQueue.contains(self)) return;
        for (BehaviourInstance instance : new ArrayList<>(behaviours.getOrDefault(self, List.of()))) {
            seven.callMethod(instance, "onCollide", proxy(other));
        }
    }

    private void flushDestroyed() {
        for (Entity entity : destroyQueue) {
            scene.remove(entity);
            behaviours.remove(entity);
            proxies.remove(entity);
            collisions.forget(entity);
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
