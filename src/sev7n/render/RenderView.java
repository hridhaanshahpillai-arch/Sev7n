package sev7n.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import sev7n.scene.Camera;
import sev7n.scene.Entity;

/**
 * Where a picture is taken from: the view and projection matrices plus the eye position.
 * Orthographic views are flat (2D): things don't shrink with distance.
 */
public record RenderView(Matrix4f view, Matrix4f projection, Vector3f eye, boolean orthographic) {

    /** The view from a Camera component on an entity. */
    public static RenderView fromCamera(Entity entity, float aspect) {
        Camera camera = entity.get(Camera.class);
        Matrix4f world = entity.transform.matrixWithoutScale(new Matrix4f());
        Matrix4f view = world.invert(new Matrix4f());
        Matrix4f projection = camera.orthographic
                ? orthographic((float) camera.size, aspect, (float) camera.near, (float) camera.far)
                : new Matrix4f().perspective((float) Math.toRadians(camera.fieldOfView), aspect,
                        (float) camera.near, (float) camera.far);
        return new RenderView(view, projection, world.getTranslation(new Vector3f()), camera.orthographic);
    }

    /** A flat projection showing 'halfHeight' world units above and below the center. */
    public static Matrix4f orthographic(float halfHeight, float aspect, float near, float far) {
        float h = Math.max(0.01f, halfHeight);
        return new Matrix4f().ortho(-h * aspect, h * aspect, -h, h, near, far);
    }

    /** How far in front of the eye a point is, along the view direction. */
    public float depth(double x, double y, double z) {
        return -view.transformPosition(new Vector3f((float) x, (float) y, (float) z)).z;
    }
}
