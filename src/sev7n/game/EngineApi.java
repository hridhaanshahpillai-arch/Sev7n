package sev7n.game;

import sev7n.lang.Interpreter;
import sev7n.lang.runtime.SevenCallable;
import sev7n.math.Vec3;
import sev7n.lang.runtime.Values;
import sev7n.scene.Entity;
import sev7n.scene.Hud;
import sev7n.scene.MeshRenderer;
import sev7n.scene.ParticleEmitter;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Global functions scripts get from the engine (on top of the core library):
 *   input:   key, keyPressed, keyReleased, mouseX, mouseY, mouseDown, mouseClicked
 *   scene:   find(name), findAll(tag), spawn(mesh or model path, [position]), spawnEffect(preset, [position])
 *   time:    time()
 *   tweens:  tween(entity, property, to, seconds, [easing], [whenDone]), stopTweens(entity, [property])
 *   HUD:     text(value, x, y, [size], [color], [align]), rect(x, y, w, h, [color]), bar(x, y, w, h, amount, [color], [background]),
 *            image(path, x, y, w, h, [tint]), screenWidth(), screenHeight()
 *   UI:      button(label, x, y, w, h, [primary]), slider(label, x, y, w, value, [min], [max]), checkbox(label, x, y, value),
 *            panel(x, y, w, h, [color]), textWidth(text, [size]), uiMouseX(), uiMouseY(), mouseOverUi()
 *   in-world UI: beginWorld(position, [yaw], [scale]), endWorld(), setGlow(on), worldToScreen(point),
 *            line(...), circle(...), ring(...), frame(...), brackets(...), segmentBar(...), keyIcon(key, x, y, [size])
 *   game:    setTimeScale(s), timeScale(), restart(), quit()
 *   sound:   playSound(path, [volume]), playMusic(path, [volume]), stopMusic(), setVolume(v), volume()
 *   NPCs:    makeNoise(position, [loudness], [source]), hour(), setHour(h), dayLength(), setDayLength(seconds)
 *   physics: gravity(), setGravity(v), solidAt(point), raycast(from, direction, [max], [ignore]), overlapSphere(center, radius)
 */
final class EngineApi {

    private EngineApi() { }

    static void install(Interpreter seven, Game game) {
        installInput(seven, game.input());
        installScene(seven, game);
        seven.defineNative("time", 0, 0, args -> game.time());
        installTweens(seven, game);
        installPhysics(seven, game);
        installNpcs(seven, game);
        installHud(seven, game);
        installUi(seven, game);
        installWorldUi(seven, game);
        installSound(seven, game);
    }

    /**
     * UI in the world and sci-fi shapes: holograms floating beside the player, prompts pinned
     * to things, lock-on brackets, radars, segmented energy bars.
     */
    private static void installWorldUi(Interpreter seven, Game game) {
        Hud hud = game.scene().hud;
        // beginWorld(position, [yaw], [scale]): until endWorld(), draw on a canvas in the world with its top left at
        // position, turned yaw degrees (left out: always facing the camera), scale world units per screen unit
        seven.defineNative("beginWorld", 1, 3, args -> {
            double yaw = args.has(1) && args.get(1) != null ? args.number(1) : Double.NaN;
            hud.setCanvas(new Hud.Canvas(args.vec3(0).copy(), yaw, args.has(2) ? args.number(2) : 0.0025));
            return null;
        });
        seven.defineNative("endWorld", 0, 0, args -> {
            hud.setCanvas(null);
            return null;
        });
        seven.defineNative("setGlow", 1, 1, args -> {
            hud.setGlow(Values.isTruthy(args.get(0)));
            return null;
        });
        // worldToScreen(point): where something in the world shows on the screen (x, y), or none if it's behind the camera
        seven.defineNative("worldToScreen", 1, 1, args -> hud.worldToScreen(args.vec3(0)));
        seven.defineNative("line", 4, 6, args -> {
            hud.add(new Hud.Line(args.number(0), args.number(1), args.number(2), args.number(3),
                    args.has(4) ? args.number(4) : 2, color(args, 5, TEXT)));
            return null;
        });
        seven.defineNative("circle", 3, 4, args -> {
            hud.add(new Hud.Circle(args.number(0), args.number(1), args.number(2), 0, color(args, 3, TEXT)));
            return null;
        });
        seven.defineNative("ring", 3, 5, args -> {
            hud.add(new Hud.Circle(args.number(0), args.number(1), args.number(2), args.has(3) ? args.number(3) : 2, color(args, 4, TEXT)));
            return null;
        });
        // frame(x, y, w, h, [color], [thickness]): just the outline of a box
        seven.defineNative("frame", 4, 6, args -> {
            double x = args.number(0), y = args.number(1), w = args.number(2), h = args.number(3);
            double t = args.has(5) ? args.number(5) : 2;
            Color color = color(args, 4, TEXT);
            hud.add(new Hud.Box(x, y, w, t, color));
            hud.add(new Hud.Box(x, y + h - t, w, t, color));
            hud.add(new Hud.Box(x, y + t, t, h - t * 2, color));
            hud.add(new Hud.Box(x + w - t, y + t, t, h - t * 2, color));
            return null;
        });
        // brackets(x, y, w, h, [color], [length], [thickness]): only the corners of a box (lock-on, targets, selections)
        seven.defineNative("brackets", 4, 7, args -> {
            double x = args.number(0), y = args.number(1), w = args.number(2), h = args.number(3);
            double length = args.has(5) ? args.number(5) : Math.min(w, h) * 0.25;
            double t = args.has(6) ? args.number(6) : 3;
            Color color = color(args, 4, TEXT);
            for (int corner = 0; corner < 4; corner++) {
                double cx = corner % 2 == 0 ? x : x + w - length;
                double cy = corner < 2 ? y : y + h - t;
                double vx = corner % 2 == 0 ? x : x + w - t;
                double vy = corner < 2 ? y : y + h - length;
                hud.add(new Hud.Box(cx, cy, length, t, color));
                hud.add(new Hud.Box(vx, vy, t, length, color));
            }
            return null;
        });
        // segmentBar(x, y, w, h, amount, segments, [color], [background]): a bar in separate blocks (energy, ammo)
        seven.defineNative("segmentBar", 6, 8, args -> {
            double x = args.number(0), y = args.number(1), w = args.number(2), h = args.number(3);
            double amount = Math.max(0, Math.min(1, args.number(4)));
            int segments = Math.max(1, Math.min(200, args.integer(5)));
            double gap = Math.max(1, w / segments * 0.18);
            double each = (w - gap * (segments - 1)) / segments;
            Color on = color(args, 6, BAR), off = color(args, 7, BAR_BACKGROUND);
            double filled = amount * segments;
            for (int i = 0; i < segments; i++) {
                double sx = x + i * (each + gap);
                hud.add(new Hud.Box(sx, y, each, h, off));
                double part = Math.max(0, Math.min(1, filled - i));
                if (part > 0) hud.add(new Hud.Box(sx, y, each * part, h, on));
            }
            return null;
        });
        // keyIcon(key, x, y, [size]): a keyboard key with a letter on it, for prompts like "[F] Punch"
        seven.defineNative("keyIcon", 3, 4, args -> {
            String key = Values.stringify(args.get(0)).toUpperCase();
            double size = args.has(3) ? args.number(3) : 36;
            double textSize = size * (key.length() > 1 ? 0.42 : 0.58);
            double w = Math.max(size, Hud.textWidth(key, textSize) + size * 0.35);
            double x = args.number(1), y = args.number(2);
            hud.add(new Hud.Box(x, y + size * 0.08, w, size, new Color(0, 0, 0, 120), size * 0.2));
            hud.add(new Hud.Box(x, y, w, size, new Color(0xF8F8F2), size * 0.2));
            hud.add(new Hud.Box(x + 2, y + 2, w - 4, size - 6, new Color(0x2B2D3A), size * 0.17));
            hud.add(new Hud.Text(key, x + w / 2, y + (size - 4 - Hud.textHeight(textSize)) / 2, textSize, new Color(0xF8F8F2), "center", false));
            return w;
        });
    }

    /** Controls you click: they draw themselves on the HUD and say what happened. Call every frame from update(). */
    private static void installUi(Interpreter seven, Game game) {
        Ui ui = game.ui();
        seven.defineNative("button", 5, 6, args -> ui.button(Values.stringify(args.get(0)), args.number(1), args.number(2),
                args.number(3), args.number(4), args.has(5) && Values.isTruthy(args.get(5))));
        seven.defineNative("slider", 5, 7, args -> ui.slider(Values.stringify(args.get(0)), args.number(1), args.number(2),
                args.number(3), args.number(4), args.has(5) ? args.number(5) : 0, args.has(6) ? args.number(6) : 1));
        seven.defineNative("checkbox", 4, 4, args -> ui.checkbox(Values.stringify(args.get(0)), args.number(1), args.number(2), Values.isTruthy(args.get(3))));
        seven.defineNative("panel", 4, 5, args -> {
            ui.panel(args.number(0), args.number(1), args.number(2), args.number(3), color(args, 4, Ui.PANEL));
            return null;
        });
        seven.defineNative("textWidth", 1, 2, args -> Hud.textWidth(Values.stringify(args.get(0)), args.has(1) ? args.number(1) : 32));
        seven.defineNative("uiMouseX", 0, 0, args -> ui.mouseX());
        seven.defineNative("uiMouseY", 0, 0, args -> ui.mouseY());
        seven.defineNative("mouseOverUi", 0, 0, args -> ui.mouseOverUi());
        // the world's clock: 0 freezes everything but scripts (pause menus), 0.3 is slow motion
        seven.defineNative("setTimeScale", 1, 1, args -> {
            game.setTimeScale(args.number(0));
            return null;
        });
        seven.defineNative("timeScale", 0, 0, args -> game.timeScale());
        seven.defineNative("restart", 0, 0, args -> {
            game.restart();
            return null;
        });
        seven.defineNative("quit", 0, 0, args -> {
            game.quit();
            return null;
        });
    }

    private static final Color TEXT = new Color(0xF8F8F2);
    private static final Color BAR = new Color(0x50FA7B);
    private static final Color BAR_BACKGROUND = new Color(0x44475A);

    /** Drawing over the game, in screen units (top left is 0, 0; the screen is 720 tall). Call every frame from update(). */
    private static void installHud(Interpreter seven, Game game) {
        Hud hud = game.scene().hud;
        seven.defineNative("text", 3, 6, args -> {
            String align = args.has(5) ? args.string(5) : "left";
            if (!align.equals("left") && !align.equals("center") && !align.equals("right")) throw args.error("align should be \"left\", \"center\" or \"right\"");
            hud.add(new Hud.Text(Values.stringify(args.get(0)), args.number(1), args.number(2),
                    args.has(3) ? args.number(3) : 32, color(args, 4, TEXT), align));
            return null;
        });
        seven.defineNative("rect", 4, 5, args -> {
            hud.add(new Hud.Box(args.number(0), args.number(1), args.number(2), args.number(3), color(args, 4, TEXT)));
            return null;
        });
        // bar(x, y, width, height, amount 0..1, [color], [background]): health, stamina, loading
        seven.defineNative("bar", 5, 7, args -> {
            double amount = Math.max(0, Math.min(1, args.number(4)));
            hud.add(new Hud.Box(args.number(0), args.number(1), args.number(2), args.number(3), color(args, 6, BAR_BACKGROUND)));
            hud.add(new Hud.Box(args.number(0), args.number(1), args.number(2) * amount, args.number(3), color(args, 5, BAR)));
            return null;
        });
        seven.defineNative("image", 5, 6, args -> {
            hud.add(new Hud.Picture(args.string(0), args.number(1), args.number(2), args.number(3), args.number(4), color(args, 5, Color.WHITE)));
            return null;
        });
        seven.defineNative("screenWidth", 0, 0, args -> hud.width());
        seven.defineNative("screenHeight", 0, 0, args -> Hud.HEIGHT);
    }

    private static Color color(sev7n.lang.runtime.Args args, int index, Color fallback) {
        if (!args.has(index)) return fallback;
        if (args.get(index) instanceof Color c) return c;
        throw args.error("expected a color like #ff5555, not " + Values.typeName(args.get(index)));
    }

    /** Sound effects and music (.wav files in the project, e.g. "assets/sounds/hit.wav"). */
    private static void installSound(Interpreter seven, Game game) {
        seven.defineNative("playSound", 1, 2, args -> {
            try {
                game.sound().play(args.string(0), args.has(1) ? args.number(1) : 1);
            } catch (IllegalArgumentException e) {
                throw args.error(e.getMessage());
            }
            return null;
        });
        seven.defineNative("playMusic", 1, 2, args -> {
            try {
                game.sound().playMusic(args.string(0), args.has(1) ? args.number(1) : 0.6);
            } catch (IllegalArgumentException e) {
                throw args.error(e.getMessage());
            }
            return null;
        });
        seven.defineNative("stopMusic", 0, 0, args -> {
            game.sound().stopMusic();
            return null;
        });
        seven.defineNative("setVolume", 1, 1, args -> {
            game.sound().setMasterVolume(args.number(0));
            return null;
        });
        seven.defineNative("volume", 0, 0, args -> game.sound().masterVolume());
    }

    private static void installNpcs(Interpreter seven, Game game) {
        // makeNoise(position, [loudness], [source]): NPCs that hear it turn to look, come to see, or run
        seven.defineNative("makeNoise", 1, 3, args -> {
            Entity source = args.has(2) && args.get(2) instanceof EntityProxy proxy ? proxy.entity() : null;
            game.makeNoise(args.vec3(0), args.has(1) ? args.number(1) : 1, source);
            return null;
        });
        seven.defineNative("hour", 0, 0, args -> game.hour());
        seven.defineNative("setHour", 1, 1, args -> {
            game.setHour(args.number(0));
            return null;
        });
        seven.defineNative("dayLength", 0, 0, args -> game.dayLength());
        seven.defineNative("setDayLength", 1, 1, args -> {
            game.setDayLength(args.number(0));
            return null;
        });
    }

    private static void installPhysics(Interpreter seven, Game game) {
        seven.defineNative("gravity", 0, 0, args -> game.physics().gravity);
        seven.defineNative("setGravity", 1, 1, args -> {
            game.physics().gravity.set(args.vec3(0));
            return null;
        });
        seven.defineNative("solidAt", 1, 1, args -> game.solidAt(args.vec3(0)));
        // raycast(from, direction, [maxDistance], [ignore]): the first solid thing along a line, or none
        seven.defineNative("raycast", 2, 4, args -> {
            double maxDistance = args.has(2) ? args.number(2) : 1000;
            Entity ignore = args.has(3) && args.get(3) instanceof EntityProxy proxy ? proxy.entity() : null;
            Physics.RayHit hit = Physics.raycast(game.scene(), args.vec3(0), args.vec3(1), maxDistance, ignore);
            if (hit == null) return null;
            return new RayHitProxy(hit.entity() == null ? null : game.proxy(hit.entity()), hit.point(), hit.normal(), hit.distance());
        });
        // overlapSphere(center, radius): every entity whose collider (triggers too) is within the ball
        seven.defineNative("overlapSphere", 2, 2, args -> {
            List<Object> found = new ArrayList<>();
            for (Entity e : Physics.overlapSphere(game.scene(), args.vec3(0), args.number(1))) found.add(game.proxy(e));
            return found;
        });
    }

    /** tween(entity, "position", vec3(0, 3, 0), 1.5, ["outBounce"], [whenDone]) and stopTweens(entity, [property]). */
    private static void installTweens(Interpreter seven, Game game) {
        seven.defineNative("tween", 4, 6, args -> {
            if (!(args.get(0) instanceof EntityProxy target)) throw args.error("the first argument should be an entity");
            SevenCallable whenDone = null;
            if (args.has(5)) {
                if (!(args.get(5) instanceof SevenCallable callback)) throw args.error("the last argument should be a function to call when it's done");
                whenDone = callback;
            }
            try {
                game.tweens().start(target.entity(), args.string(1), args.get(2), args.number(3),
                        args.has(4) ? args.string(4) : "inOutQuad", whenDone);
            } catch (IllegalArgumentException e) {
                throw args.error(e.getMessage());
            }
            return null;
        });
        seven.defineNative("stopTweens", 1, 2, args -> {
            if (!(args.get(0) instanceof EntityProxy target)) throw args.error("the first argument should be an entity");
            game.tweens().stop(target.entity(), args.has(1) ? args.string(1) : null);
            return null;
        });
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
