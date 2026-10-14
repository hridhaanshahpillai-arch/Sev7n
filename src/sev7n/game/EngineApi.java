package sev7n.game;

import sev7n.lang.Interpreter;
import sev7n.math.Vec3;
import sev7n.scene.Entity;
import sev7n.scene.MeshRenderer;
import sev7n.scene.ParticleEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * Global functions scripts get from the engine (on top of the core library):
 *   input:   key, keyPressed, keyReleased, mouseX, mouseY, mouseDown, mouseClicked
 *   scene:   find(name), findAll(tag), spawn(mesh or model path, [position]), spawnEffect(preset, [position])
 *   time:    time()
 *   physics: gravity(), setGravity(v), solidAt(point)
 */
final class EngineApi {

    private EngineApi() { }

    static void install(Interpreter seven, Game game) {
        installInput(seven, game.input());
        installScene(seven, game);
        seven.defineNative("time", 0, 0, args -> game.time());
        installPhysics(seven, game);
    }

    private static void installPhysics(Interpreter seven, Game game) {
        seven.defineNative("gravity", 0, 0, args -> game.physics().gravity);
        seven.defineNative("setGravity", 1, 1, args -> {
            game.physics().gravity.set(args.vec3(0));
            return null;
        });
        seven.defineNative("solidAt", 1, 1, args -> game.solidAt(args.vec3(0)));
    }

    private static void installInput(Interpreter seven, Input input) {
        seven.defineNative("key", 1, 1, args -> input.isHeld(args.string(0)));
        seven.defineNative("keyPressed", 1, 1, args -> input.wasPressed(args.string(0)));
        seven.defineNative("keyReleased", 1, 1, args -> input.wasReleased(args.string(0)));
        seven.defineNative("mouseX", 0, 0, args -> input.mouseX());
        seven.defineNative("mouseY", 0, 0, args -> input.mouseY());
        seven.defineNative("mouseDown", 0, 0, args -> input.isMouseDown());
        seven.defineNative("mouseClicked", 0, 0, args -> input.wasMouseClicked());
    }

    private static void installScene(Interpreter seven, Game game) {
        seven.defineNative("find", 1, 1, args -> {
            Entity found = game.scene().find(args.string(0));
            return found == null ? null : game.proxy(found);
        });
        seven.defineNative("findAll", 1, 1, args -> {
            List<Object> found = new ArrayList<>();
            for (Entity e : game.scene().entities()) {
                if (e.hasTag(args.string(0))) found.add(game.proxy(e));
            }
            return found;
        });
        seven.defineNative("spawn", 1, 2, args -> {
            String mesh = args.string(0);
            if (!MeshRenderer.isBuiltIn(mesh) && !mesh.contains("/")) {
                throw args.error("unknown mesh '" + mesh + "'. Use one of " + MeshRenderer.MESHES + " or a model path like \"assets/models/tree/tree.glb\"");
            }
            Vec3 position = args.has(1) ? args.vec3(1) : new Vec3();
            return game.spawn(mesh, position);
        });
        seven.defineNative("spawnEffect", 1, 2, args -> {
            String preset = args.string(0);
            if (!ParticleEmitter.PRESETS.contains(preset)) {
                throw args.error("unknown effect '" + preset + "'. Use one of " + ParticleEmitter.PRESETS);
            }
            return game.spawnEffect(preset, args.has(1) ? args.vec3(1) : new Vec3());
        });
    }
}
