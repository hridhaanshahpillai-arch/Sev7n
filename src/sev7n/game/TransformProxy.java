package sev7n.game;

import sev7n.lang.SevenError;
import sev7n.lang.runtime.NativeFunction;
import sev7n.lang.runtime.SevenObject;
import sev7n.lang.runtime.Values;
import sev7n.math.Vec3;
import sev7n.scene.Transform;

/**
 * 'transform' in scripts.
 *   transform.position / rotation / scale   live vec3s (rotation in degrees)
 *   transform.forward / right / up          direction vec3s (read-only)
 *   transform.translate(v)  transform.rotate(v)  transform.lookAt(target)
 */
final class TransformProxy implements SevenObject {

    private final Transform transform;

    TransformProxy(Transform transform) {
        this.transform = transform;
    }

    @Override
    public String typeName() {
        return "transform";
    }

    @Override
    public java.util.Collection<String> propertyNames() {
        return java.util.List.of("position", "rotation", "scale", "forward", "right", "up", "translate", "rotate", "lookAt");
    }

    @Override
    public Object getProperty(String name, int line) {
        return switch (name) {
            case "position" -> transform.position;
            case "rotation" -> transform.rotation;
            case "scale" -> transform.scale;
            case "forward" -> transform.forward();
            case "right" -> transform.right();
            case "up" -> transform.up();
            case "translate" -> new NativeFunction(name, 1, 1, args -> {
                transform.position.set(transform.position.plus(args.vec3(0)));
                return null;
            });
            case "rotate" -> new NativeFunction(name, 1, 1, args -> {
                transform.rotation.set(transform.rotation.plus(args.vec3(0)));
                return null;
            });
            case "lookAt" -> new NativeFunction(name, 1, 1, args -> {
                lookAt(args.vec3(0));
                return null;
            });
            default -> throw noSuchProperty(name, line);
        };
    }

    @Override
    public void setProperty(String name, Object value, int line) {
        Vec3 target = switch (name) {
            case "position" -> transform.position;
            case "rotation" -> transform.rotation;
            case "scale" -> transform.scale;
            default -> throw new SevenError("transform." + name + " can't be changed", line);
        };
        if (!(value instanceof Vec3 v)) {
            throw new SevenError("transform." + name + " must be a vec3, not " + Values.typeName(value), line);
        }
        target.set(v); // copy, so the transform keeps its own vector
    }

    /** Turns to face a point (forward is -Z, so we solve yaw and pitch for that). */
    private void lookAt(Vec3 target) {
        Vec3 d = target.minus(transform.position);
        double yaw = Math.atan2(-d.x, -d.z);
        double pitch = Math.atan2(d.y, Math.hypot(d.x, d.z));
        transform.rotation.set(Math.toDegrees(pitch), Math.toDegrees(yaw), 0);
    }

    @Override
    public String toString() {
        return "<transform>";
    }
}
