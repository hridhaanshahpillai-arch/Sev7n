package sev7n.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import sev7n.math.Vec3;
import sev7n.scene.BoxCollider;
import sev7n.scene.CapsuleCollider;
import sev7n.scene.Entity;
import sev7n.scene.Ragdoll;
import sev7n.scene.Rigidbody;
import sev7n.scene.Scene;
import sev7n.scene.SphereCollider;

import java.util.ArrayList;
import java.util.List;

/**
 * An entity's collider in world space: a box (lined up with the axes) or a round shape
 * (sphere or capsule). 'bounds' is the box around it either way; 'round' is null for boxes.
 */
record WorldCollider(Entity entity, Box bounds, Round round, boolean trigger) {

    /** The entity's collider (the first box, sphere or capsule it has), or null (also while it's a limp ragdoll). */
    static WorldCollider of(Entity entity) {
        if (Ragdoll.limp(entity)) return null; // a limp body's balls collide instead
        BoxCollider box = entity.get(BoxCollider.class);
        if (box != null) return new WorldCollider(entity, Box.of(entity, box), null, box.trigger);
        SphereCollider sphere = entity.get(SphereCollider.class);
        if (sphere != null) {
            Vec3 center = point(entity, sphere.offset.x, sphere.offset.y, sphere.offset.z);
            Round round = new Round(center, center.copy(), sphere.radius * biggestScale(entity));
            return new WorldCollider(entity, round.bounds(), round, sphere.trigger);
        }
        CapsuleCollider capsule = entity.get(CapsuleCollider.class);
        if (capsule != null) {
            double radius = capsule.radius * Math.max(Math.abs(entity.transform.scale.x), Math.abs(entity.transform.scale.z));
            double half = Math.max(0, capsule.height * Math.abs(entity.transform.scale.y) / 2 - radius);
            Vec3 center = point(entity, capsule.offset.x, capsule.offset.y, capsule.offset.z);
            Vec3 up = entity.transform.up().times(half);
            Round round = new Round(center.minus(up), center.plus(up), radius);
            return new WorldCollider(entity, round.bounds(), round, capsule.trigger);
        }
        return null;
    }

    /** Every active collider in the scene, optionally leaving out triggers. */
    static List<WorldCollider> all(Scene scene, boolean includeTriggers) {
        List<WorldCollider> colliders = new ArrayList<>();
        for (Entity entity : scene.entities()) {
            if (!entity.active) continue;
            WorldCollider collider = of(entity);
            if (collider != null && (includeTriggers || !collider.trigger)) colliders.add(collider);
        }
        return colliders;
    }

    /** How this collider overlaps another (normal pointing from the other toward this one), or null. */
    Geometry.Overlap overlap(WorldCollider other, double margin) {
        if (round != null && other.round != null) return Geometry.overlap(round, other.round, margin);
        if (round != null) return Geometry.overlap(round, other.bounds, margin);
        if (other.round != null) {
            Geometry.Overlap reversed = Geometry.overlap(other.round, bounds, margin);
            return reversed == null ? null : new Geometry.Overlap(reversed.normal().negated(), reversed.depth());
        }
        return bounds.touches(other.bounds, margin) ? new Geometry.Overlap(new Vec3(0, 1, 0), 0) : null;
    }

    boolean touches(WorldCollider other, double margin) {
        return bounds.touches(other.bounds, margin) && overlap(other, margin) != null;
    }

    /** Where a ray first hits this collider, or null. */
    Geometry.Hit ray(Vec3 origin, Vec3 direction, double maxDistance) {
        return round != null ? Geometry.ray(origin, direction, round, maxDistance) : Geometry.ray(origin, direction, bounds, maxDistance);
    }

    /** The entity's Rigidbody if it's one that moves by physics (not kinematic), else null. */
    Rigidbody dynamicBody() {
        Rigidbody body = entity.get(Rigidbody.class);
        return body != null && !body.kinematic ? body : null;
    }

    private static Vec3 point(Entity entity, double x, double y, double z) {
        Vector3f p = entity.transform.matrix(new Matrix4f()).transformPosition(new Vector3f((float) x, (float) y, (float) z));
        return new Vec3(p.x, p.y, p.z);
    }

    private static double biggestScale(Entity entity) {
        Vec3 s = entity.transform.scale;
        return Math.max(Math.abs(s.x), Math.max(Math.abs(s.y), Math.abs(s.z)));
    }
}
