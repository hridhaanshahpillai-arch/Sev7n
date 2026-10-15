package sev7n.game;

import sev7n.lang.runtime.BehaviourInstance;
import sev7n.math.Vec3;
import sev7n.scene.Entity;
import sev7n.scene.Json;
import sev7n.scene.Scene;
import sev7n.scene.SceneSerializer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything about a running game, as plain data (maps, lists, numbers, strings): every object
 * and its components, every behaviour's variables, the values kept with setValue, the clock.
 * Saved games are snapshots written to a file; loading one builds the game back from it.
 *
 * Multiplayer (1.0) builds on the same thing: a server sends snapshots (and later, just what
 * changed between two) so every player's game shows the same world. Entities keep their ids
 * in a snapshot, so they can be matched up on both sides.
 *
 * Values that can't be written down (functions, other entities held in variables) are left out,
 * and keep whatever their behaviour's start() gives them.
 */
final class Snapshot {

    private Snapshot() { }

    /** A snapshot of a game right now. */
    static Map<String, Object> capture(Game game) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1.0);
        data.put("scene", game.sceneName());
        data.put("world", Json.parse(SceneSerializer.toJson(game.scene())));
        Map<String, Object> scripts = new LinkedHashMap<>();
        for (Map.Entry<Entity, List<BehaviourInstance>> entry : game.behaviourInstances().entrySet()) {
            Map<String, Object> behaviours = new LinkedHashMap<>();
            for (BehaviourInstance instance : entry.getValue()) {
                Map<String, Object> fields = new LinkedHashMap<>();
                instance.fields().forEach((name, value) -> {
                    Object saved = toData(value);
                    if (saved != UNSAVABLE) fields.put(name, saved);
                });
                behaviours.put(instance.type().name(), fields);
            }
            scripts.put(String.valueOf(entry.getKey().id()), behaviours);
        }
        data.put("scripts", scripts);
        data.put("values", toData(game.values()));
        data.put("timeScale", game.timeScale());
        data.put("time", game.time());
        data.put("hour", game.hour());
        return data;
    }

    /** The scene a snapshot was taken in, as it was. */
    static Scene world(Map<String, Object> data) {
        return SceneSerializer.fromJson(Json.write(Json.child(data, "world")));
    }

    /** After the game made from world(data) has started: puts the behaviours' variables, values and clock back. */
    @SuppressWarnings("unchecked")
    static void restore(Game game, Map<String, Object> data) {
        Map<String, Object> scripts = Json.child(data, "scripts");
        for (Map.Entry<Entity, List<BehaviourInstance>> entry : game.behaviourInstances().entrySet()) {
            Map<String, Object> behaviours = Json.child(scripts, String.valueOf(entry.getKey().id()));
            for (BehaviourInstance instance : entry.getValue()) {
                Map<String, Object> fields = Json.child(behaviours, instance.type().name());
                fields.forEach((name, value) -> instance.setField(name, fromData(value)));
            }
        }
        if (fromData(data.get("values")) instanceof Map<?, ?> values) {
            game.values().clear();
            game.values().putAll((Map<String, Object>) values);
        }
        game.setTimeScale(Json.number(data, "timeScale", 1));
        game.setHour(Json.number(data, "hour", game.hour()));
    }

    // ---------------------------------------------------------------- values <-> data

    private static final Object UNSAVABLE = new Object();

    /** A script value as plain data (vectors and colors tagged so they come back as themselves), or UNSAVABLE. */
    static Object toData(Object value) {
        if (value == null || value instanceof Double || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Vec3 v) return Map.of("vec3", Json.vec3(v));
        if (value instanceof Color c) return Map.of("color", Json.color(c));
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                Object saved = toData(item);
                if (saved == UNSAVABLE) return UNSAVABLE;
                out.add(saved);
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object saved = toData(entry.getValue());
                if (saved != UNSAVABLE) out.put(String.valueOf(entry.getKey()), saved);
            }
            // a map whose only key is a tag word would come back as that type, so it's wrapped
            if (out.size() == 1 && (out.containsKey("vec3") || out.containsKey("color") || out.containsKey("map"))) return Map.of("map", out);
            return out;
        }
        return UNSAVABLE;
    }

    /** The other way: plain data back into script values. */
    static Object fromData(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> m = Json.object(map);
            if (map.size() == 1 && map.containsKey("vec3")) {
                Vec3 v = new Vec3();
                Json.vec3(m, "vec3", v);
                return v;
            }
            if (map.size() == 1 && map.containsKey("color")) return Json.color(m, "color", Color.WHITE);
            if (map.size() == 1 && map.get("map") instanceof Map<?, ?> wrapped) {
                Map<String, Object> out = new LinkedHashMap<>();
                Json.object(wrapped).forEach((k, v) -> out.put(k, fromData(v)));
                return out;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(k, fromData(v)));
            return out;
        }
        if (data instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) out.add(fromData(item));
            return out;
        }
        return data;
    }
}
