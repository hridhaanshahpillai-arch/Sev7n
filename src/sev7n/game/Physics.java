package sev7n.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import sev7n.math.Vec3;
import sev7n.scene.BoxCollider;
import sev7n.scene.Entity;
import sev7n.scene.Rigidbody;
import sev7n.scene.Scene;
import sev7n.scene.Tilemap;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves rigidbodies: gravity and drag change their velocity, then they move one axis
 * at a time (X, then Y, then Z) and stop at anything solid in the way.
 *
 * Solid things are colliders that aren't triggers (with or without a Rigidbody) and the
 * tiles of solid tilemaps. Big frames are split into small steps so fast objects
 * don't skip through thin walls.
 */
final class Physics {

    private static final double MAX_STEP = 1.0 / 120;
    private static final double EPSILON = 1e-4; // boxes are worked out in float, so allow for rounding
    private static final double STOP_BOUNCING = 0.5; // slower bounces than this just stop

    /** Units per second, per second. Scripts can change it with setGravity(). */
    final Vec3 gravity = new Vec3(0, -9.81, 0);

    private record Solid(Entity entity, Box box) { }

    void step(Scene scene, double dt) {
        List<Entity> bodies = new ArrayList<>();
        for (Entity entity : scene.entities()) {
            Rigidbody body = entity.get(Rigidbody.class);
            if (entity.active && body != null) {
                bodies.add(entity);
                body.grounded = false;
            }
        }
        if (bodies.isEmpty()) return;

        int steps = (int) Math.ceil(dt / MAX_STEP);
        double h = dt / Math.max(1, steps);
        for (int i = 0; i < steps; i++) {
            List<Solid> colliders = solidColliders(scene);
            for (Entity entity : bodies) move(entity, entity.get(Rigidbody.class), colliders, scene, h);
        }
    }

    private void move(Entity entity, Rigidbody body, List<Solid> colliders, Scene scene, double h) {
        Vec3 v = body.velocity;
        v.x += gravity.x * body.gravityScale * h;
        v.y += gravity.y * body.gravityScale * h;
        v.z += gravity.z * body.gravityScale * h;
        double keep = Math.max(0, 1 - body.drag * h);
        v.x *= keep;
        v.y *= keep;
        v.z *= keep;

        BoxCollider collider = entity.get(BoxCollider.class);
        if (collider == null || collider.trigger) {
            // nothing to collide with: just fly
            entity.transform.position.set(entity.transform.position.x + v.x * h,
                    entity.transform.position.y + v.y * h, entity.transform.position.z + v.z * h);
            return;
        }

        Box box = Box.of(entity, collider);
        double reach = Math.abs(v.x * h) + Math.abs(v.y * h) + Math.abs(v.z * h) + 0.1;
        List<Box> solids = new ArrayList<>();
        Box area = box.grown(reach);
        for (Solid solid : colliders) {
            if (solid.entity() != entity && solid.box().touches(area, 0)) solids.add(solid.box());
        }
        addTiles(scene, area, solids);

        for (int axis = 0; axis < 3; axis++) {
            double wanted = component(v, axis) * h;
            if (wanted == 0) continue;
            double allowed = sweep(box, axis, wanted, solids);
            box = box.moved(axis == 0 ? allowed : 0, axis == 1 ? allowed : 0, axis == 2 ? allowed : 0);
            addToPosition(entity, axis, allowed);
            if (allowed != wanted) {
                if (axis == 1 && wanted < 0) body.grounded = true;
                double bounced = -component(v, axis) * body.bounciness;
                setComponent(v, axis, Math.abs(bounced) < STOP_BOUNCING ? 0 : bounced);
            }
        }
    }

    /** How far the box can move along one axis before it hits a solid (at most 'wanted'). */
    private static double sweep(Box box, int axis, double wanted, List<Box> solids) {
        double allowed = wanted;
        for (Box solid : solids) {
            if (!overlapsOnOtherAxes(box, solid, axis)) continue;
            if (wanted > 0 && box.max(axis) <= solid.min(axis) + EPSILON) {
                allowed = Math.min(allowed, solid.min(axis) - box.max(axis));
            } else if (wanted < 0 && box.min(axis) >= solid.max(axis) - EPSILON) {
                allowed = Math.max(allowed, solid.max(axis) - box.min(axis));
            }
            // already overlapping (e.g. spawned inside a wall): let it move out freely
        }
        return wanted > 0 ? Math.max(0, allowed) : Math.min(0, allowed);
    }

    private static boolean overlapsOnOtherAxes(Box a, Box b, int axis) {
        for (int other = 0; other < 3; other++) {
            if (other == axis) continue;
            if (a.min(other) >= b.max(other) - EPSILON || a.max(other) <= b.min(other) + EPSILON) return false;
        }
        return true;
    }

    private static List<Solid> solidColliders(Scene scene) {
        List<Solid> solids = new ArrayList<>();
        for (Entity entity : scene.entities()) {
            BoxCollider collider = entity.get(BoxCollider.class);
            if (entity.active && collider != null && !collider.trigger) solids.add(new Solid(entity, Box.of(entity, collider)));
        }
        return solids;
    }

    /** The boxes of solid tiles near 'area'. Each tile is as deep as it is wide, centered on the map's plane. */
    private static void addTiles(Scene scene, Box area, List<Box> solids) {
        for (Entity entity : scene.entities()) {
            Tilemap map = entity.get(Tilemap.class);
            if (!entity.active || map == null || !map.solid || map.isEmpty()) continue;
            Matrix4f matrix = entity.transform.matrix(new Matrix4f());
            Matrix4f inverse = matrix.invert(new Matrix4f());
            if (!inverse.isFinite()) continue;
            Box local = Box.around(inverse, area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ());
            double s = map.tileSize;
            if (local.minZ() > s / 2 || local.maxZ() < -s / 2) continue; // not level with the map
            int x0 = (int) Math.floor(local.minX() / s);
            int x1 = (int) Math.floor(local.maxX() / s);
            int y0 = (int) Math.floor(local.minY() / s);
            int y1 = (int) Math.floor(local.maxY() / s);
            if ((long) (x1 - x0) * (y1 - y0) > 10_000) continue; // something huge or far away
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    if (map.tile(x, y) == Tilemap.EMPTY) continue;
                    solids.add(Box.around(matrix, x * s, y * s, -s / 2, (x + 1) * s, (y + 1) * s, s / 2));
                }
            }
        }
    }

    /** True if a point is inside a solid tile of any solid tilemap (for checks like "is there ground ahead?"). */
    static boolean solidTileAt(Scene scene, Vec3 point) {
        for (Entity entity : scene.entities()) {
            Tilemap map = entity.get(Tilemap.class);
            if (!entity.active || map == null || !map.solid) continue;
            Matrix4f inverse = entity.transform.matrix(new Matrix4f()).invert();
            Vector3f local = inverse.transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z));
            int x = (int) Math.floor(local.x / map.tileSize);
            int y = (int) Math.floor(local.y / map.tileSize);
            if (Math.abs(local.z) <= map.tileSize / 2 && map.tile(x, y) != Tilemap.EMPTY) return true;
        }
        return false;
    }

    private static double component(Vec3 v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    private static void setComponent(Vec3 v, int axis, double value) {
        if (axis == 0) v.x = value;
        else if (axis == 1) v.y = value;
        else v.z = value;
    }

    private static void addToPosition(Entity entity, int axis, double amount) {
        Vec3 p = entity.transform.position;
        setComponent(p, axis, component(p, axis) + amount);
    }
}
