package sev7n.game;

import sev7n.lang.SevenError;
import sev7n.lang.runtime.SevenObject;
import sev7n.lang.runtime.Values;
import sev7n.scene.Camera;

import java.awt.Color;

/**
 * 'entity.camera' in scripts, for entities with a Camera.
 *   camera.orthographic   true = flat 2D view, false = 3D perspective (switch any time)
 *   camera.size           how much a 2D camera sees: half the view's height, in units
 *   camera.fieldOfView    how wide a 3D camera sees, in degrees
 *   camera.background     the color behind everything
 */
final class CameraProxy implements SevenObject {

    private final Camera camera;

    CameraProxy(Camera camera) {
        this.camera = camera;
    }

    @Override
    public String typeName() {
        return "camera";
    }

    @Override
    public java.util.Collection<String> propertyNames() {
        return java.util.List.of("orthographic", "size", "fieldOfView", "background");
    }

    @Override
    public Object getProperty(String name, int line) {
        return switch (name) {
            case "orthographic" -> camera.orthographic;
            case "size" -> camera.size;
            case "fieldOfView" -> camera.fieldOfView;
            case "background" -> camera.background;
            default -> throw noSuchProperty(name, line);
        };
    }

    @Override
    public void setProperty(String name, Object value, int line) {
        switch (name) {
            case "orthographic" -> camera.orthographic = Values.isTruthy(value);
            case "size" -> camera.size = Math.max(0.01, number(name, value, line));
            case "fieldOfView" -> camera.fieldOfView = Math.max(1, Math.min(179, number(name, value, line)));
            case "background" -> {
                if (!(value instanceof Color color)) throw new SevenError("camera.background must be a color", line);
                camera.background = color;
            }
            default -> throw new SevenError("camera." + name + " can't be changed", line);
        }
    }

    private static double number(String name, Object value, int line) {
        if (value instanceof Double d) return d;
        throw new SevenError("camera." + name + " must be a number, not " + Values.typeName(value), line);
    }

    @Override
    public String toString() {
        return "<camera>";
    }
}
