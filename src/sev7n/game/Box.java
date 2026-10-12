package sev7n.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import sev7n.scene.BoxCollider;
import sev7n.scene.Entity;

/**
 * A world-space box lined up with the X, Y and Z axes. Rotated colliders become the
 * box around them (a little bigger than the real shape): simple, and plenty for games
 * like platformers, top-down games and pickups.
 */
record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    /** The world box of an entity's collider. */
    static Box of(Entity entity, BoxCollider collider) {
        Matrix4f matrix = entity.transform.matrix(new Matrix4f());
        return around(matrix,
                collider.offset.x - collider.size.x / 2, collider.offset.y - collider.size.y / 2, collider.offset.z - collider.size.z / 2,
                collider.offset.x + collider.size.x / 2, collider.offset.y + collider.size.y / 2, collider.offset.z + collider.size.z / 2);
    }

    /** The world box around a local box after it's transformed by a matrix. */
    static Box around(Matrix4f matrix, double x0, double y0, double z0, double x1, double y1, double z1) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f corner = new Vector3f();
        for (int i = 0; i < 8; i++) {
            corner.set((float) ((i & 1) == 0 ? x0 : x1), (float) ((i & 2) == 0 ? y0 : y1), (float) ((i & 4) == 0 ? z0 : z1));
            matrix.transformPosition(corner);
            min.min(corner);
            max.max(corner);
        }
        return new Box(min.x, min.y, min.z, max.x, max.y, max.z);
    }

    /** True if the boxes overlap or touch (within 'margin'). */
    boolean touches(Box o, double margin) {
        return minX <= o.maxX + margin && maxX >= o.minX - margin
                && minY <= o.maxY + margin && maxY >= o.minY - margin
                && minZ <= o.maxZ + margin && maxZ >= o.minZ - margin;
    }

    Box moved(double dx, double dy, double dz) {
        return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    Box grown(double amount) {
        return new Box(minX - amount, minY - amount, minZ - amount, maxX + amount, maxY + amount, maxZ + amount);
    }

    double min(int axis) {
        return axis == 0 ? minX : axis == 1 ? minY : minZ;
    }

    double max(int axis) {
        return axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
    }
}
