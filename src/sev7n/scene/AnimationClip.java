package sev7n.scene;

import sev7n.math.Easing;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A keyframe animation (an .anim file, made in the Animation window): how an object's position,
 * rotation, scale, colour, light and visibility change over time. Doors that swing, platforms that
 * glide, lights that pulse, coins that bob, cutscenes.
 *
 * Each property has a track of keys (a time and a value); in between, values glide from one key to
 * the next along the first key's easing curve ("linear", "inOutSine", "outBounce"... or "step" to
 * jump). Events call a script function on the object when the animation passes their time.
 *
 * Position, rotation and scale are relative to the object's parent if it has one (so an animated
 * part of a moving thing moves with it).
 */
public final class AnimationClip {

    /** What a track can animate, by its name in the file. */
    public enum Property {
        POSITION_X("position.x", "Position X"), POSITION_Y("position.y", "Position Y"), POSITION_Z("position.z", "Position Z"),
        ROTATION_X("rotation.x", "Rotation X"), ROTATION_Y("rotation.y", "Rotation Y"), ROTATION_Z("rotation.z", "Rotation Z"),
        SCALE_X("scale.x", "Scale X"), SCALE_Y("scale.y", "Scale Y"), SCALE_Z("scale.z", "Scale Z"),
        COLOR("color", "Color"),
        LIGHT("light.intensity", "Light brightness"),
        ACTIVE("active", "Active");

        public final String key;
        public final String label;

        Property(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public boolean transform() {
            return ordinal() <= SCALE_Z.ordinal();
        }

        /** Colors and on/off don't glide by numbers like the rest. */
        public boolean numeric() {
            return this != COLOR && this != ACTIVE;
        }

        public static Property of(String key) {
            for (Property p : values()) if (p.key.equals(key)) return p;
            return null;
        }
    }

    /** One keyframe: at 'time' seconds the property is 'value' (a Double, a Color, or a Boolean for Active). */
    public static final class Key {
        public double time;
        public Object value;
        /** How it gets from here to the next key. */
        public String easing;

        public Key(double time, Object value, String easing) {
            this.time = time;
            this.value = value;
            this.easing = easing == null ? "linear" : easing;
        }
    }

    /** "At this time, call this function on the object's behaviours" (footsteps, a door's creak, the end of a cutscene). */
    public static final class Event {
        public double time;
        public String function;

        public Event(double time, String function) {
            this.time = time;
            this.function = function;
        }
    }

    public double length = 1;
    public boolean loop = true;
    public final Map<Property, List<Key>> tracks = new LinkedHashMap<>();
    public final List<Event> events = new ArrayList<>();

    // ---------------------------------------------------------------- editing

    /** Sets the property's value at a time: changes the key there if there is one, otherwise adds one. */
    public Key setKey(Property property, double time, Object value) {
        List<Key> keys = tracks.computeIfAbsent(property, p -> new ArrayList<>());
        for (Key k : keys) {
            if (Math.abs(k.time - time) < 1e-4) {
                k.value = value;
                return k;
            }
        }
        String easing = property == Property.ACTIVE ? "step" : keys.isEmpty() ? "inOutSine" : keyBefore(keys, time) != null ? keyBefore(keys, time).easing : "inOutSine";
        Key key = new Key(time, value, easing);
        keys.add(key);
        sort(keys);
        if (time > length) length = time;
        return key;
    }

    public void removeKey(Property property, Key key) {
        List<Key> keys = tracks.get(property);
        if (keys != null) keys.remove(key);
    }

    /** Keeps a track's keys in time order (after moving one). */
    public static void sort(List<Key> keys) {
        keys.sort(Comparator.comparingDouble(k -> k.time));
    }

    private static Key keyBefore(List<Key> keys, double time) {
        Key before = null;
        for (Key k : keys) if (k.time <= time) before = k;
        return before;
    }

    // ---------------------------------------------------------------- playing

    /** The property's value at a time (seconds into the clip), or null if it has no keys. */
    public Object sample(Property property, double time) {
        List<Key> keys = tracks.get(property);
        if (keys == null || keys.isEmpty()) return null;
        Key first = keys.get(0);
        if (time <= first.time) return first.value;
        for (int i = 0; i < keys.size() - 1; i++) {
            Key a = keys.get(i), b = keys.get(i + 1);
            if (time >= b.time) continue;
            if (a.easing.equals("step") || b.time - a.time < 1e-9) return a.value;
            double t = Easing.apply(a.easing, (time - a.time) / (b.time - a.time));
            return blend(a.value, b.value, t);
        }
        return keys.get(keys.size() - 1).value;
    }

    private static Object blend(Object a, Object b, double t) {
        if (a instanceof Double x && b instanceof Double y) return x + (y - x) * t;
        if (a instanceof Color x && b instanceof Color y) {
            return new Color(channel(x.getRed(), y.getRed(), t), channel(x.getGreen(), y.getGreen(), t),
                    channel(x.getBlue(), y.getBlue(), t), channel(x.getAlpha(), y.getAlpha(), t));
        }
        return t < 1 ? a : b; // on/off: switches at the next key
    }

    private static int channel(int a, int b, double t) {
        return (int) Math.max(0, Math.min(255, Math.round(a + (b - a) * t)));
    }

    /** Where a time lands in the clip: wrapped round if it loops, held at the end if it doesn't. */
    public double wrap(double time) {
        if (length <= 0) return 0;
        if (loop) return ((time % length) + length) % length;
        return Math.max(0, Math.min(length, time));
    }

    /** Poses the entity as the clip is at 'time' (already wrapped). Only animated properties change. */
    public void apply(Scene scene, Entity entity, double time) {
        boolean anyTransform = false;
        for (Property p : tracks.keySet()) if (p.transform() && !tracks.get(p).isEmpty()) anyTransform = true;
        if (anyTransform) {
            Transform local = Hierarchy.localOf(scene, entity);
            for (Property p : tracks.keySet()) {
                if (!p.transform() || !(sample(p, time) instanceof Double v)) continue;
                switch (p) {
                    case POSITION_X -> local.position.x = v;
                    case POSITION_Y -> local.position.y = v;
                    case POSITION_Z -> local.position.z = v;
                    case ROTATION_X -> local.rotation.x = v;
                    case ROTATION_Y -> local.rotation.y = v;
                    case ROTATION_Z -> local.rotation.z = v;
                    case SCALE_X -> local.scale.x = v;
                    case SCALE_Y -> local.scale.y = v;
                    case SCALE_Z -> local.scale.z = v;
                    default -> { }
                }
            }
            Hierarchy.setLocal(scene, entity, local);
        }
        if (sample(Property.COLOR, time) instanceof Color c) setColor(entity, c);
        if (sample(Property.LIGHT, time) instanceof Double v && entity.get(Light.class) != null) entity.get(Light.class).intensity = Math.max(0, v);
        if (sample(Property.ACTIVE, time) instanceof Boolean on) entity.active = on;
    }

    /** The entity's current value of a property (for recording keys), or null if it has nothing to animate there. */
    public static Object current(Scene scene, Entity entity, Property property) {
        if (property.transform()) {
            Transform local = Hierarchy.localOf(scene, entity);
            return switch (property) {
                case POSITION_X -> local.position.x;
                case POSITION_Y -> local.position.y;
                case POSITION_Z -> local.position.z;
                case ROTATION_X -> local.rotation.x;
                case ROTATION_Y -> local.rotation.y;
                case ROTATION_Z -> local.rotation.z;
                case SCALE_X -> local.scale.x;
                case SCALE_Y -> local.scale.y;
                default -> local.scale.z;
            };
        }
        return switch (property) {
            case COLOR -> color(entity);
            case LIGHT -> entity.get(Light.class) == null ? null : entity.get(Light.class).intensity;
            case ACTIVE -> entity.active;
            default -> null;
        };
    }

    /** The color a clip animates: the Mesh Renderer's, or the Sprite Renderer's tint. */
    public static Color color(Entity entity) {
        MeshRenderer mesh = entity.get(MeshRenderer.class);
        if (mesh != null) return mesh.color;
        SpriteRenderer sprite = entity.get(SpriteRenderer.class);
        return sprite != null ? sprite.tint : null;
    }

    private static void setColor(Entity entity, Color c) {
        MeshRenderer mesh = entity.get(MeshRenderer.class);
        if (mesh != null) mesh.color = c;
        SpriteRenderer sprite = entity.get(SpriteRenderer.class);
        if (sprite != null) sprite.tint = c;
    }

    // ---------------------------------------------------------------- files

    public static AnimationClip load(Path file) throws IOException {
        Map<String, Object> in = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        AnimationClip clip = new AnimationClip();
        clip.length = Math.max(0.01, Json.number(in, "length", 1));
        clip.loop = Json.bool(in, "loop", true);
        if (in.get("tracks") instanceof Map<?, ?> tracks) {
            for (Map.Entry<?, ?> entry : tracks.entrySet()) {
                Property property = Property.of(String.valueOf(entry.getKey()));
                if (property == null || !(entry.getValue() instanceof List<?> keys)) continue; // from a newer version
                List<Key> list = new ArrayList<>();
                for (Object k : keys) {
                    if (!(k instanceof List<?> parts) || parts.size() < 2 || !(parts.get(0) instanceof Double time)) continue;
                    Object value = parts.get(1);
                    if (property == Property.COLOR && value instanceof String hex) value = Json.color(Map.of("c", hex), "c", Color.WHITE);
                    else if (property == Property.ACTIVE) value = Boolean.TRUE.equals(value) || Double.valueOf(1).equals(value);
                    else if (!(value instanceof Double)) continue;
                    list.add(new Key(time, value, parts.size() > 2 ? String.valueOf(parts.get(2)) : "linear"));
                }
                sort(list);
                clip.tracks.put(property, list);
            }
        }
        if (in.get("events") instanceof List<?> events) {
            for (Object e : events) {
                if (e instanceof List<?> parts && parts.size() >= 2 && parts.get(0) instanceof Double time) {
                    clip.events.add(new Event(time, String.valueOf(parts.get(1))));
                }
            }
        }
        return clip;
    }

    public void save(Path file) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("length", length);
        out.put("loop", loop);
        Map<String, Object> tracksOut = new LinkedHashMap<>();
        for (Map.Entry<Property, List<Key>> entry : tracks.entrySet()) {
            List<Object> keys = new ArrayList<>();
            for (Key k : entry.getValue()) {
                Object value = k.value instanceof Color c ? Json.color(c) : k.value;
                keys.add(List.of(k.time, value, k.easing));
            }
            tracksOut.put(entry.getKey().key, keys);
        }
        out.put("tracks", tracksOut);
        List<Object> eventsOut = new ArrayList<>();
        for (Event e : events) eventsOut.add(List.of(e.time, e.function));
        out.put("events", eventsOut);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, Json.write(out), StandardCharsets.UTF_8);
    }
}
