package sev7n.scene;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import sev7n.math.Vec3;

/**
 * Where an entity is, how it's rotated, and how big it is.
 *
 * Rotation is in degrees around X, Y and Z, applied in X, then Y, then Z order
 * (the same convention the editor's gizmo uses). Y is up; -Z is "forward".
 *
 * The Vec3 objects are never replaced, only changed in place, so scripts can keep
 * a reference to transform.position and always see the live value.
 */
public final class Transform {

    public final Vec3 position = new Vec3();
    public final Vec3 rotation = new Vec3();
    public final Vec3 scale = new Vec3(1, 1, 1);

    /** The matrix that takes the entity's local space to world space. */
    public Matrix4f matrix(Matrix4f dest) {
        return rotationMatrix(dest.identity().translate((float) position.x, (float) position.y, (float) position.z))
                .scale((float) scale.x, (float) scale.y, (float) scale.z);
    }

    /** Like matrix(), but without scale. Cameras use this so scaling never warps the view. */
    public Matrix4f matrixWithoutScale(Matrix4f dest) {
        return rotationMatrix(dest.identity().translate((float) position.x, (float) position.y, (float) position.z));
    }

    private Matrix4f rotationMatrix(Matrix4f m) {
        return m.rotateZ((float) Math.toRadians(rotation.z))
                .rotateY((float) Math.toRadians(rotation.y))
                .rotateX((float) Math.toRadians(rotation.x));
    }

    /**
     * Sets rotation (in degrees) from a matrix built the same way as matrix().
     * Handles the "gimbal lock" case (Y rotation of exactly ±90°) explicitly.
     */
    public void setRotationFromMatrix(Matrix4f m) {
        // remove scale so only rotation is left (JOML's mXY = column X, row Y)
        Vector3f scale = m.getScale(new Vector3f());
        Matrix4f r = new Matrix4f(m).scale(1 / scale.x, 1 / scale.y, 1 / scale.z);
        double sinY = Math.max(-1, Math.min(1, -r.m02()));
        double x;
        double y = Math.asin(sinY);
        double z;
        if (Math.abs(sinY) < 0.99999) {
            x = Math.atan2(r.m12(), r.m22());
            z = Math.atan2(r.m01(), r.m00());
        } else {
            // X and Z rotate around the same axis here; put it all in X
            z = 0;
            x = sinY > 0 ? Math.atan2(r.m10(), r.m11()) : Math.atan2(-r.m10(), r.m11());
        }
        rotation.set(Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z));
    }

    /** The direction this entity faces (-Z rotated by the entity's rotation). */
    public Vec3 forward() {
        return direction(0, 0, -1);
    }

    public Vec3 right() {
        return direction(1, 0, 0);
    }

    public Vec3 up() {
        return direction(0, 1, 0);
    }

    private Vec3 direction(float x, float y, float z) {
        Vector3f d = rotationMatrix(new Matrix4f()).transformDirection(new Vector3f(x, y, z));
        return new Vec3(d.x, d.y, d.z);
    }

    public void copyFrom(Transform other) {
        position.set(other.position);
        rotation.set(other.rotation);
        scale.set(other.scale);
    }
}
