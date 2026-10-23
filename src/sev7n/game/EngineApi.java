package sev7n.game;

import sev7n.lang.Interpreter;
import sev7n.lang.runtime.SevenCallable;
import sev7n.math.Vec3;
import sev7n.lang.runtime.Values;
import sev7n.scene.Entity;
import sev7n.scene.Hud;
import sev7n.scene.MeshRenderer;
import sev7n.scene.ParticleEmitter;
import sev7n.scene.Terrain;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Global functions scripts get from the engine (on top of the core library):
 *   input:   key, keyPressed, keyReleased, mouseX, mouseY, mouseDown([button]), mouseClicked([button]),
 *            lockMouse(on), mouseLocked(), mouseMoveX(), mouseMoveY()
 *   scene:   find(name), findAll(tag), spawn(mesh or model path, [position]), spawnEffect(preset, [position])
 *   time:    time()
 *   tweens:  tween(entity, property, to, seconds, [easing], [whenDone]), stopTweens(entity, [property])
 *   HUD:     text(value, x, y, [size], [color], [align]), rect(x, y, w, h, [color]), bar(x, y, w, h, amount, [color], [background]),
 *            image(path, x, y, w, h, [tint]), screenWidth(), screenHeight()
 *   UI:      button(label, x, y, w, h, [primary]), slider(label, x, y, w, value, [min], [max]), checkbox(label, x, y, value),
 *            panel(x, y, w, h, [color]), textWidth(text, [size]), uiMouseX(), uiMouseY(), mouseOverUi()
 *   in-world UI: beginWorld(position, [yaw], [scale]), endWorld(), setGlow(on), worldToScreen(point),
 *            line(...), circle(...), ring(...), frame(...), brackets(...), segmentBar(...), keyIcon(key, x, y, [size])
 *   scenes:  loadScene(name), sceneName(), setValue(key, v), getValue(key, [default]), saveGame([slot]), loadGame([slot]),
 *            hasSave([slot]), deleteSave([slot]), spawnPrefab(name, [position])
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
        // cameraForward() / cameraRight(): where the main camera looks, flat on the ground (move a character from the player's view)
        seven.defineNative("cameraForward", 0, 0, args -> CameraRig.flatForward(game.scene()));
        seven.defineNative("cameraRight", 0, 0, args -> CameraRig.flatRight(game.scene()));
        installTweens(seven, game);
        installPhysics(seven, game);
        installNpcs(seven, game);
        installHud(seven, game);
        installUi(seven, game);
        installWorldUi(seven, game);
        installSound(seven, game);
        installScenes(seven, game);
        installNetwork(seven, game);
    }

    /**
     * Multiplayer. connect() hosts, or joins if this PC already hosts: run the game twice (Play, then
     * File > Play in Window) and both windows play together. host([port]) / join([address], [port]) choose.
     * share(key, value) / shared(player, key) for things everyone sees; sendMessage(name, [value]) for events.
     * Scripts get onConnected(id), onPlayerJoined(id), onPlayerLeft(id), onMessage(name, value, from), onDisconnected().
     */
    private static void installNetwork(Interpreter seven, Game game) {
        seven.defineNative("connect", 0, 1, args -> game.network().connect(args.has(0) ? (int) args.number(0) : Network.DEFAULT_PORT));
        seven.defineNative("host", 0, 1, args -> game.network().host(args.has(0) ? (int) args.number(0) : Network.DEFAULT_PORT));
        seven.defineNative("join", 0, 2, args -> game.network().join(args.has(0) ? args.string(0) : "localhost", args.has(1) ? (int) args.number(1) : Network.DEFAULT_PORT));
        seven.defineNative("disconnect", 0, 0, args -> {
            game.network().close();
            return null;
        });
        seven.defineNative("isConnected", 0, 0, args -> game.network().connected());
        seven.defineNative("isHost", 0, 0, args -> game.network().isHost());
        seven.defineNative("playerId", 0, 0, args -> (double) game.network().playerId());
        seven.defineNative("players", 0, 0, args -> game.network().players());
        seven.defineNative("sendMessage", 1, 2, args -> {
            game.network().send(args.string(0), args.has(1) ? args.get(1) : null);
            return null;
        });
        seven.defineNative("share", 2, 2, args -> {
            game.network().share(args.string(0), args.get(1));
            return null;
        });
        seven.defineNative("shared", 2, 3, args -> {
            Object value = game.network().shared((int) args.number(0), args.string(1));
            return value != null ? value : (args.has(2) ? args.get(2) : null);
        });
    }

    /** Levels, saved games, values kept between them, and prefabs. */
    private static void installScenes(Interpreter seven, Game game) {
        // loadScene("Level2"): switches to another scene at the end of this frame (values from setValue come along);
        // loadScene("Level2", 0.5): fades to black over half a second first, and the new scene fades in
        seven.defineNative("loadScene", 1, 2, args -> {
            game.requestScene(args.string(0), args.has(1) ? args.number(1) : 0);
            return null;
        });
        // reloadScene([fade]): starts this scene again (values from setValue are kept, unlike restart())
        seven.defineNative("reloadScene", 0, 1, args -> {
            game.requestScene(game.sceneName(), args.has(0) ? args.number(0) : 0);
            return null;
        });
        seven.defineNative("sceneName", 0, 0, args -> game.sceneName());
        // sceneNames(): every scene in the project, "Main" first (for level select menus)
        seven.defineNative("sceneNames", 0, 0, args -> new ArrayList<Object>(game.sceneNames()));
        // setValue/getValue: remembered across scenes, and saved with saveGame (score, unlocked levels, settings)
        seven.defineNative("setValue", 2, 2, args -> {
            game.values().put(args.string(0), args.get(1));
            return null;
        });
        seven.defineNative("getValue", 1, 2, args -> {
            Object value = game.values().get(args.string(0));
            return value != null ? value : args.has(1) ? args.get(1) : null;
        });
        // saveGame([slot]): everything about the game right now (every object, every script's variables, values)
        seven.defineNative("saveGame", 0, 1, args -> {
            try {
                game.saveGame(args.has(0) ? args.string(0) : "save");
            } catch (IOException e) {
                throw args.error("couldn't save the game: " + e.getMessage());
            }
            return null;
        });
        // loadGame([slot]): puts it all back at the end of this frame; false if there's no such save
        seven.defineNative("loadGame", 0, 1, args -> {
            try {
                return game.loadGame(args.has(0) ? args.string(0) : "save");
            } catch (IOException | IllegalArgumentException e) {
                throw args.error("couldn't load the game: " + e.getMessage());
            }
        });
        seven.defineNative("hasSave", 0, 1, args -> game.hasSave(args.has(0) ? args.string(0) : "save"));
        seven.defineNative("deleteSave", 0, 1, args -> {
            try {
                return game.deleteSave(args.has(0) ? args.string(0) : "save");
            } catch (IOException e) {
                throw args.error("couldn't delete the save: " + e.getMessage());
            }
        });
        // spawnPrefab("Zombie", [position]): a copy of a saved object, behaviours and all
        seven.defineNative("spawnPrefab", 1, 2, args -> {
            try {
                return game.spawnPrefab(args.string(0), args.has(1) ? args.vec3(1) : new Vec3());
            } catch (IOException e) {
                throw args.error(e.getMessage());
            }
        });
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
        // worldToScreen(point): where something in the world shows on the screen (x, y, measured from the anchor), or none if it's behind the camera
        seven.defineNative("worldToScreen", 1, 1, args -> {
            Vec3 at = hud.worldToScreen(args.vec3(0));
            return at == null ? null : new Vec3(at.x - hud.originX(), at.y - hud.originY(), 0);
        });
        // anchor("bottomRight"): positions are measured from that corner (or edge, or the center) from now on, so a HUD fits any screen
        seven.defineNative("anchor", 1, 1, args -> {
            try {
                hud.anchor(args.string(0));
            } catch (IllegalArgumentException e) {
                throw args.error(e.getMessage());
            }
            return null;
        });
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
        seven.defineNative("uiMouseX", 0, 0, args -> ui.mouseX() - game.scene().hud.originX());
        seven.defineNative("uiMouseY", 0, 0, args -> ui.mouseY() - game.scene().hud.originY());
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

    /** Sound effects and music (.wav or .ogg files in the project, e.g. "assets/sounds/hit.wav"), through the audio engine. */
    private static void installSound(Interpreter seven, Game game) {
        // playSound(path, [volume], [pitch], [bus]): plays once; returns the sound, to change or stop it while it plays
        seven.defineNative("playSound", 1, 4, args -> {
            String bus = args.has(3) ? bus(args, 3, game) : "Effects";
            try {
                return new SoundProxy(game.sound().play(args.string(0), args.has(1) ? args.number(1) : 1, args.has(2) ? args.number(2) : 1, bus), null);
            } catch (IllegalArgumentException e) {
                throw args.error(e.getMessage());
            }
        });
        // playSoundAt(path, position, [volume], [range], [pitch]): a sound somewhere in the world, heard from the main
        // camera: quieter further away (silent past 'range', 30 if left out), from the left or right, muffled behind
        seven.defineNative("playSoundAt", 2, 5, args -> {
            Vec3 at = args.vec3(1);
            try {
                return new SoundProxy(game.sound().playAt(args.string(0), at, args.has(2) ? args.number(2) : 1,
                        args.has(3) ? args.number(3) : 30, args.has(4) ? args.number(4) : 1), at.copy());
            } catch (IllegalArgumentException e) {
                throw args.error(e.getMessage());
            }
        });
        // playMusic(path, [volume], [fade]): music on repeat; different music crossfades over 'fade' seconds
        seven.defineNative("playMusic", 1, 3, args -> {
            try {
                game.sound().playMusic(args.string(0), args.has(1) ? args.number(1) : 0.6, args.has(2) ? args.number(2) : 0);
            } catch (IllegalArgumentException e) {
                throw args.error(e.getMessage());
            }
            return null;
        });
        seven.defineNative("stopMusic", 0, 1, args -> {
            game.sound().stopMusic(args.has(0) ? args.number(0) : 0);
            return null;
        });
        seven.defineNative("setVolume", 1, 1, args -> {
            game.sound().setMasterVolume(args.number(0));
            return null;
        });
        seven.defineNative("volume", 0, 0, args -> game.sound().masterVolume());
        // setBusVolume("Music", 0.5) / busVolume("Music"): the Audio Mixer's groups (Music, Effects, Ambient, Voice, UI)
        seven.defineNative("setBusVolume", 2, 2, args -> {
            game.sound().mixer().setBusVolume(bus(args, 0, game), args.number(1));
            return null;
        });
        seven.defineNative("busVolume", 1, 1, args -> game.sound().mixer().bus(bus(args, 0, game)).volume());
        seven.defineNative("muteBus", 2, 2, args -> {
            game.sound().mixer().setBusMuted(bus(args, 0, game), Values.isTruthy(args.get(1)));
            return null;
        });
    }

    /** A bus name, checked (with the right names listed if it's wrong). */
    private static String bus(sev7n.lang.runtime.Args args, int index, Game game) {
        String name = args.string(index);
        for (String bus : sev7n.audio.AudioMixer.BUSES) if (bus.equalsIgnoreCase(name)) return bus;
        throw args.error("there's no bus '" + name + "'. The buses are " + String.join(", ", sev7n.audio.AudioMixer.BUSES));
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
        // groundHeight(x, z): how high the terrain is there (in the world), or none off every terrain
        seven.defineNative("groundHeight", 2, 2, args -> {
            double h = Ground.heightAt(Ground.terrains(game.scene()), args.number(0), args.number(1));
            return Double.isNaN(h) || Double.isInfinite(h) ? null : (Object) h;
        });
        // sculpt("raise" | "lower" | "smooth" | "flatten", position, radius, [amount]): changes the land while
        // the game runs (craters, digging, building up). Flatten levels to position.y. True if any land changed.
        seven.defineNative("sculpt", 3, 4, args -> {
            Terrain.Brush brush;
            try {
                brush = Terrain.Brush.valueOf(args.string(0).toUpperCase());
            } catch (IllegalArgumentException e) {
                throw args.error("unknown brush '" + args.string(0) + "'. Use \"raise\", \"lower\", \"smooth\" or \"flatten\"");
            }
            Vec3 at = args.vec3(1);
            double radius = args.number(2);
            double amount = args.has(3) ? args.number(3) : brush == Terrain.Brush.RAISE || brush == Terrain.Brush.LOWER ? 1 : 0.5;
            boolean changed = false;
            for (Entity e : Ground.terrains(game.scene())) {
                Vec3 p = e.transform.position;
                changed |= e.get(Terrain.class).sculpt(brush, at.x - p.x, at.z - p.z, radius, amount, at.y - p.y);
            }
            return changed;
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
        // mouseDown([button]) / mouseClicked([button]): 0 (or left out) = left button, 1 = right
        seven.defineNative("mouseDown", 0, 1, args -> args.has(0) && args.number(0) == 1 ? input.isRightMouseDown() : input.isMouseDown());
        seven.defineNative("mouseClicked", 0, 1, args -> args.has(0) && args.number(0) == 1 ? input.wasRightMouseClicked() : input.wasMouseClicked());
        // mouse-look: lockMouse(true) hides the cursor and keeps it in the game; mouseMoveX/Y say how far it moved this frame
        seven.defineNative("lockMouse", 1, 1, args -> {
            input.setMouseLocked(Values.isTruthy(args.get(0)));
            return null;
        });
        seven.defineNative("mouseLocked", 0, 0, args -> input.mouseLocked());
        seven.defineNative("mouseMoveX", 0, 0, args -> input.mouseMoveX());
        seven.defineNative("mouseMoveY", 0, 0, args -> input.mouseMoveY());
        // gamepads: padConnected(), padButton("A") held, padPressed("A") this frame, padAxis("LX") -1..1 / ("RT") 0..1
        seven.defineNative("padConnected", 0, 0, args -> input.padConnected());
        seven.defineNative("padButton", 1, 1, args -> input.padHeld(args.string(0)));
        seven.defineNative("padPressed", 1, 1, args -> input.padPressed(args.string(0)));
        seven.defineNative("padAxis", 1, 1, args -> input.padAxis(args.string(0)));
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
