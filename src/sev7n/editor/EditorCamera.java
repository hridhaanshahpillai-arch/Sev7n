package sev7n.editor;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import sev7n.render.RenderView;

/**
 * The Scene view's camera.
 *
 * In 3D it orbits around a target point:
 *   right-drag (or Alt + left-drag) orbit · middle-drag pan · wheel zoom · F focus
 * In 2D it looks straight at the X/Y plane (down -Z), flat:
 *   right- or middle-drag pan · wheel zoom · F focus
 */
final class EditorCamera {

    private static final float FIELD_OF_VIEW = 60;
    private static final float FLAT_DISTANCE = 500; // how far back the 2D camera sits

    private final Vector3f target = new Vector3f(0, 0.5f, 0);
    private float yaw = 35;       // degrees around the target
    private float pitch = 25;     // degrees above the horizon
    private float distance = 13;

    private boolean flat = false;
    private final Vector3f flatTarget = new Vector3f();
    private float flatSize = 6;   // half the view's height, in world units

    boolean flat() {
        return flat;
    }

    void setFlat(boolean flat) {
        this.flat = flat;
    }

    void orbit(float dx, float dy) {
        if (flat) return;
        yaw -= dx * 0.3f;
        pitch = Math.max(-89, Math.min(89, pitch + dy * 0.3f));
    }

    void pan(float dx, float dy, float viewportHeight) {
        if (flat) {
            float unitsPerPixel = 2 * flatSize / viewportHeight;
            flatTarget.add(-dx * unitsPerPixel, dy * unitsPerPixel, 0);
            return;
        }
        // move the target in the camera's own right/up directions, scaled so the scene tracks the mouse
        float unitsPerPixel = 2 * distance * (float) Math.tan(Math.toRadians(FIELD_OF_VIEW / 2)) / viewportHeight;
        Vector3f forward = new Vector3f(target).sub(eye()).normalize();
        Vector3f right = forward.cross(0, 1, 0, new Vector3f()).normalize();
        Vector3f up = new Vector3f(right).cross(forward).normalize();
        target.add(right.mul(-dx * unitsPerPixel)).add(up.mul(dy * unitsPerPixel));
    }

    void zoom(float wheel) {
        if (flat) flatSize = Math.max(0.25f, Math.min(500, flatSize * (float) Math.pow(0.88, wheel)));
        else distance = Math.max(0.5f, Math.min(500, distance * (float) Math.pow(0.88, wheel)));
    }

    void focus(Vector3f point) {
        if (flat) {
            flatTarget.set(point.x, point.y, 0);
            flatSize = Math.min(flatSize, 6);
            return;
        }
        target.set(point);
        distance = Math.min(distance, 8);
    }

    Vector3f eye() {
        if (flat) return new Vector3f(flatTarget.x, flatTarget.y, FLAT_DISTANCE);
        double p = Math.toRadians(pitch);
        double y = Math.toRadians(yaw);
        return new Vector3f(
                (float) (Math.cos(p) * Math.sin(y)),
                (float) Math.sin(p),
                (float) (Math.cos(p) * Math.cos(y))).mul(distance).add(target);
    }

    RenderView view(float aspect) {
        Vector3f eye = eye();
        if (flat) {
            Matrix4f view = new Matrix4f().lookAt(eye, new Vector3f(flatTarget.x, flatTarget.y, 0), new Vector3f(0, 1, 0));
            Matrix4f projection = RenderView.orthographic(flatSize, aspect, 0.1f, FLAT_DISTANCE * 2);
            return new RenderView(view, projection, eye, true);
        }
        Matrix4f view = new Matrix4f().lookAt(eye, target, new Vector3f(0, 1, 0));
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(FIELD_OF_VIEW), aspect, 0.05f, 2000f);
        return new RenderView(view, projection, eye, false);
    }
}
