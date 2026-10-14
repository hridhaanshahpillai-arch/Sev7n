package sev7n.game;

import sev7n.lang.runtime.SevenCallable;
import sev7n.math.Easing;
import sev7n.math.Vec3;
import sev7n.scene.Entity;
import sev7n.scene.MeshRenderer;
import sev7n.scene.SpriteRenderer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tweens: smooth changes over time, started from scripts with one line:
 *   tween(entity, "position", vec3(0, 3, 0), 1.5, "outBounce")
 * moves the entity there in 1.5 seconds, slowing down with a bounce at the end.
 *
 * Properties: position, rotation, scale (vec3s), color (Mesh Renderer), tint and size
 * (Sprite Renderer). A new tween of the same property on the same entity replaces the old one.
 */
final class Tweens {

    static final List<String> PROPERTIES = List.of("position", "rotation", "scale", "color", "tint", "size");

    /** One property changing: from where it was when the tween started to 'to'. */
    private static final class Tween {
        final Entity entity;
        final String property;
        final Object from;
        final Object to;
        final double duration;
        final String easing;
        final SevenCallable onDone;
        double elapsed;

        Tween(Entity entity, String property, Object from, Object to, double duration, String easing, SevenCallable onDone) {
            this.entity = entity;
            this.property = property;
            this.from = from;
            this.to = to;
            this.duration = duration;
            this.easing = easing;
            this.onDone = onDone;
        }
    }

    private final List<Tween> running = new ArrayList<>();

    /** Starts a tween. Throws IllegalArgumentException with a friendly message if something doesn't fit. */
    void start(Entity entity, String property, Object to, double seconds, String easing, SevenCallable onDone) {
        if (!PROPERTIES.contains(property)) {
            throw new IllegalArgumentException("can't tween '" + property + "'. Try one of " + PROPERTIES);
        }
        if (!Easing.exists(easing)) throw new IllegalArgumentException("no easing called '" + easing + "'. Try one of " + Easing.names());
        Object from = read(entity, property);
        if (from == null) throw new IllegalArgumentException("'" + entity.name + "' has no " + owner(property) + " to tween its " + property);
        if (from instanceof Vec3 && !(to instanceof Vec3) || from instanceof Color && !(to instanceof Color)
                || from instanceof Double && !(to instanceof Double)) {
            throw new IllegalArgumentException(property + " needs a " + (from instanceof Vec3 ? "vec3" : from instanceof Color ? "color" : "number"));
        }
        stop(entity, property);
        running.add(new Tween(entity, property, copy(from), copy(to), Math.max(0, seconds), easing, onDone));
    }

    /** Stops an entity's tweens (of one property, or all if 'property' is null), leaving things where they are. */
    void stop(Entity entity, String property) {
        running.removeIf(t -> t.entity == entity && (property == null || t.property.equals(property)));
    }

    /** Moves every tween forward. 'finished' is called with each callback whose tween just ended. */
    void update(double dt, Consumer<SevenCallable> finished) {
        List<SevenCallable> callbacks = new ArrayList<>();
        for (Iterator<Tween> it = running.iterator(); it.hasNext(); ) {
            Tween t = it.next();
            t.elapsed += dt;
            double progress = t.duration == 0 ? 1 : Math.min(1, t.elapsed / t.duration);
            write(t.entity, t.property, mix(t.from, t.to, Easing.apply(t.easing, progress)));
            if (progress >= 1) {
                it.remove();
                if (t.onDone != null) callbacks.add(t.onDone);
            }
        }
        callbacks.forEach(finished); // after the loop: a callback may start new tweens
    }

    void forget(Entity entity) {
        stop(entity, null);
    }

    // ---------------------------------------------------------------- properties

    private static Object read(Entity entity, String property) {
        MeshRenderer mesh = entity.get(MeshRenderer.class);
        SpriteRenderer sprite = entity.get(SpriteRenderer.class);
        return switch (property) {
            case "position" -> entity.transform.position;
            case "rotation" -> entity.transform.rotation;
            case "scale" -> entity.transform.scale;
            case "color" -> mesh == null ? null : mesh.color;
            case "tint" -> sprite == null ? null : sprite.tint;
            default -> sprite == null ? null : (Object) sprite.size; // size
        };
    }

    private static void write(Entity entity, String property, Object value) {
        switch (property) {
            case "position" -> entity.transform.position.set((Vec3) value);
            case "rotation" -> entity.transform.rotation.set((Vec3) value);
            case "scale" -> entity.transform.scale.set((Vec3) value);
            case "color" -> {
                MeshRenderer mesh = entity.get(MeshRenderer.class);
                if (mesh != null) mesh.color = (Color) value;
            }
            case "tint" -> {
                SpriteRenderer sprite = entity.get(SpriteRenderer.class);
                if (sprite != null) sprite.tint = (Color) value;
            }
            default -> {
                SpriteRenderer sprite = entity.get(SpriteRenderer.class);
                if (sprite != null) sprite.size = (Double) value;
            }
        }
    }

    private static String owner(String property) {
        return switch (property) {
            case "color" -> "Mesh Renderer";
            case "tint", "size" -> "Sprite Renderer";
            default -> "transform";
        };
    }

    private static Object copy(Object value) {
        return value instanceof Vec3 v ? v.copy() : value;
    }

    /** 't' of the way from a to b; easings like outBack go a little past 1 (overshoot), which is fine. */
    private static Object mix(Object a, Object b, double t) {
        if (a instanceof Vec3 v) return v.lerp((Vec3) b, t);
        if (a instanceof Color c) {
            Color d = (Color) b;
            return new Color(channel(c.getRed() + (d.getRed() - c.getRed()) * t), channel(c.getGreen() + (d.getGreen() - c.getGreen()) * t),
                    channel(c.getBlue() + (d.getBlue() - c.getBlue()) * t), channel(c.getAlpha() + (d.getAlpha() - c.getAlpha()) * t));
        }
        double x = (Double) a;
        return x + ((Double) b - x) * t;
    }

    private static int channel(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }
}
