package sev7n.game;

import sev7n.scene.AnimationClip;
import sev7n.scene.AnimationPlayer;
import sev7n.scene.Entity;
import sev7n.scene.Scene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Plays every Animation Player's keyframe clip in a running game: moves the time on, poses the
 * object, and calls the clip's events as their times go by. Clip files are read once and kept.
 */
final class Clips {

    private final Map<String, AnimationClip> loaded = new HashMap<>();
    private Path projectFolder = Path.of(".");

    void setProjectFolder(Path folder) {
        projectFolder = folder;
    }

    /** "door" -> "assets/animations/door.anim"; a path is kept as it is. */
    static String path(String name) {
        if (name.contains("/") || name.endsWith(".anim")) return name;
        return "assets/animations/" + name + ".anim";
    }

    /** A clip file, read once. Throws IllegalArgumentException with a readable message. */
    AnimationClip clip(String name) {
        String path = path(name);
        AnimationClip clip = loaded.get(path);
        if (clip != null) return clip;
        Path file = projectFolder.resolve(path);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("there's no animation '" + path + "' in the project (make one in Window > Animation)");
        try {
            clip = AnimationClip.load(file);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("can't read the animation '" + path + "': " + e.getMessage());
        }
        loaded.put(path, clip);
        return clip;
    }

    /** Plays an object's Animation Player from the start (another clip if 'name' isn't null). */
    void play(Scene scene, Entity entity, String name) {
        AnimationPlayer player = entity.get(AnimationPlayer.class);
        if (name != null) player.clip = path(name);
        player.loaded = clip(player.clip);
        player.time = player.speed < 0 ? player.loaded.length : 0;
        player.playing = true;
        player.loaded.apply(scene, entity, player.loaded.wrap(player.time));
    }

    void stop(Entity entity) {
        AnimationPlayer player = entity.get(AnimationPlayer.class);
        if (player != null) player.playing = false;
    }

    /**
     * Moves every playing clip on by dt seconds, poses its object, and calls events passed on the way
     * ('event' gets the object and the function name). A clip that doesn't loop stops at its end.
     */
    void update(Scene scene, double dt, BiConsumer<Entity, String> event) {
        for (Entity entity : scene.entities()) {
            AnimationPlayer player = entity.get(AnimationPlayer.class);
            if (player == null || !player.playing || player.loaded == null || !entity.active && !player.loaded.tracks.containsKey(AnimationClip.Property.ACTIVE)) continue;
            AnimationClip clip = player.loaded;
            double from = player.time, to = from + dt * player.speed;
            if (!clip.loop && (to >= clip.length || to <= 0) && dt * player.speed != 0) {
                to = Math.max(0, Math.min(clip.length, to));
                player.playing = false;
            }
            player.time = to;
            clip.apply(scene, entity, clip.wrap(to));
            if (!clip.events.isEmpty()) fireEvents(clip, entity, from, to, event);
        }
    }

    /** Calls every event whose time lies between 'from' and 'to' (going round the loop as many times as it went). */
    private static void fireEvents(AnimationClip clip, Entity entity, double from, double to, BiConsumer<Entity, String> event) {
        if (from == to || clip.length <= 0) return;
        double low = Math.min(from, to), high = Math.max(from, to);
        for (AnimationClip.Event e : clip.events) {
            if (clip.loop) {
                // every loop's copy of the event: e.time + n * length
                long first = (long) Math.ceil((low - e.time) / clip.length);
                for (long n = first; e.time + n * clip.length <= high && n - first < 100; n++) {
                    double at = e.time + n * clip.length;
                    if (at > low || (at == low && from > to)) event.accept(entity, e.function);
                }
            } else if (e.time > low && e.time <= high || (e.time == 0 && low == 0 && from < to)) {
                event.accept(entity, e.function);
            }
        }
    }
}
