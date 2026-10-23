package sev7n.scene;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parents and children: a child follows its parent, keeping the same offset (a sword in a
 * hand, a turret on a tank, a lamp on a moving platform).
 *
 * Every entity's transform stays its place in the world, so physics, rendering and scripts
 * need nothing special. Hierarchy.update moves children after their parents have moved; a
 * child that was moved itself (by a script, physics or the gizmo) keeps its new offset.
 */
public final class Hierarchy {

    private static final double SAME = 1e-6;

    private Hierarchy() { }

    /** Moves every child to follow its parent. Call once a frame, after things have moved. */
    public static void update(Scene scene) {
        if (!hasChildren(scene)) return;
        for (Entity child : parentsFirst(scene)) {
            Entity parent = scene.findById(child.parent);
            follow(child, parent);
        }
    }

    private static boolean hasChildren(Scene scene) {
        for (Entity e : scene.entities()) if (e.parent >= 0) return true;
        return false;
    }

    /** Every child, ordered so a parent always comes before its children. Broken links (a missing parent, a loop) are cut. */
    static List<Entity> parentsFirst(Scene scene) {
        Map<Long, Entity> byId = new HashMap<>();
        for (Entity e : scene.entities()) byId.put(e.id(), e);
        List<Entity> order = new ArrayList<>();
        Set<Entity> done = new HashSet<>();
        for (Entity e : scene.entities()) visit(e, byId, done, new HashSet<>(), order);
        return order;
    }

    private static void visit(Entity e, Map<Long, Entity> byId, Set<Entity> done, Set<Entity> path, List<Entity> order) {
        if (e.parent < 0 || done.contains(e)) return;
        Entity parent = byId.get(e.parent);
        if (parent == null || parent == e || path.contains(e)) {
            e.parent = -1; // its parent is gone, or it would be its own grandparent
            e.local = null;
            return;
        }
        path.add(e);
        visit(parent, byId, done, path, order);
        path.remove(e);
        done.add(e);
        if (e.parent >= 0) order.add(e);
    }

    private static void follow(Entity child, Entity parent) {
        Transform self = child.transform;
        Transform p = parent.transform;
        if (child.local == null || !same(self, child.lastSelf)) {
            // new, or moved by itself: remember where it is now, relative to the parent
            remember(child, parent);
            return;
        }
        if (same(p, child.lastParent)) return; // nothing moved
        if (sameVec(p.rotation, child.lastParent.rotation) && sameVec(p.scale, child.lastParent.scale)) {
            // the parent only moved: slide along (keeps the child's angles exactly as they are)
            self.position.set(self.position.plus(p.position.minus(child.lastParent.position)));
        } else {
            Matrix4f world = p.matrix(new Matrix4f()).mul(child.local.matrix(new Matrix4f()));
            apply(world, self);
        }
        child.lastSelf.copyFrom(self);
        child.lastParent.copyFrom(p);
    }

    /** Works out (and keeps) the child's offset from its parent as things are now. */
    static void remember(Entity child, Entity parent) {
        Matrix4f toParent = parent.transform.matrix(new Matrix4f()).invert();
        Matrix4f local = toParent.mul(child.transform.matrix(new Matrix4f()));
        if (child.local == null) child.local = new Transform();
        apply(local, child.local);
        child.lastSelf.copyFrom(child.transform);
        child.lastParent.copyFrom(parent.transform);
    }

    private static void apply(Matrix4f m, Transform into) {
        Vector3f t = m.getTranslation(new Vector3f());
        Vector3f s = m.getScale(new Vector3f());
        into.position.set(t.x, t.y, t.z);
        into.scale.set(s.x, s.y, s.z);
        into.setRotationFromMatrix(m);
    }

    private static boolean same(Transform a, Transform b) {
        return sameVec(a.position, b.position) && sameVec(a.rotation, b.rotation) && sameVec(a.scale, b.scale);
    }

    private static boolean sameVec(sev7n.math.Vec3 a, sev7n.math.Vec3 b) {
        return Math.abs(a.x - b.x) < SAME && Math.abs(a.y - b.y) < SAME && Math.abs(a.z - b.z) < SAME;
    }

    /** Where the entity is relative to its parent (in the world if it has none), as a new Transform. */
    public static Transform localOf(Scene scene, Entity e) {
        Transform local = new Transform();
        Entity parent = e.parent >= 0 ? scene.findById(e.parent) : null;
        if (parent == null) {
            local.copyFrom(e.transform);
            return local;
        }
        Matrix4f toParent = parent.transform.matrix(new Matrix4f()).invert();
        apply(toParent.mul(e.transform.matrix(new Matrix4f())), local);
        return local;
    }

    /** Puts the entity at 'local' relative to its parent (in the world if it has none); it keeps following from there. */
    public static void setLocal(Scene scene, Entity e, Transform local) {
        Entity parent = e.parent >= 0 ? scene.findById(e.parent) : null;
        if (parent == null) {
            e.transform.copyFrom(local); // no matrix round trip: angles stay exactly as given (spinning past 360 keeps going)
            return;
        }
        apply(parent.transform.matrix(new Matrix4f()).mul(local.matrix(new Matrix4f())), e.transform);
        remember(e, parent);
    }

    // ---------------------------------------------------------------- changing the family tree

    /** Makes 'child' a child of 'parent' (null: no parent), staying where it is. False if that would make a loop. */
    public static boolean setParent(Scene scene, Entity child, Entity parent) {
        if (parent == null) {
            child.parent = -1;
            child.local = null;
            return true;
        }
        if (parent == child || isAncestor(scene, child, parent)) return false;
        child.parent = parent.id();
        remember(child, parent);
        return true;
    }

    /** True if 'ancestor' is the parent of 'e', or its parent's parent, and so on. */
    public static boolean isAncestor(Scene scene, Entity ancestor, Entity e) {
        Set<Long> seen = new HashSet<>();
        for (Entity at = e; at != null && at.parent >= 0 && seen.add(at.id()); ) {
            if (at.parent == ancestor.id()) return true;
            at = scene.findById(at.parent);
        }
        return false;
    }

    /** The entity's direct children, in scene order. */
    public static List<Entity> children(Scene scene, Entity e) {
        List<Entity> children = new ArrayList<>();
        for (Entity other : scene.entities()) if (other.parent == e.id()) children.add(other);
        return children;
    }

    /** The entity's children, their children, and so on (not the entity itself), parents before children. */
    public static List<Entity> descendants(Scene scene, Entity e) {
        List<Entity> all = new ArrayList<>();
        for (Entity child : children(scene, e)) {
            all.add(child);
            all.addAll(descendants(scene, child));
        }
        return all;
    }

    /** How many parents up the tree it is (0 = no parent). */
    public static int depth(Scene scene, Entity e) {
        int depth = 0;
        Set<Long> seen = new HashSet<>();
        for (Entity at = e; at.parent >= 0 && seen.add(at.id()); depth++) {
            at = scene.findById(at.parent);
            if (at == null) break;
        }
        return depth;
    }
}
