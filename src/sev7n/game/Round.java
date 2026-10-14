package sev7n.game;

import sev7n.math.Vec3;

/**
 * A round collider in world space: every point within 'radius' of the line from a to b.
 * A sphere is a Round whose ends are the same point; a capsule is a stretched one.
 */
record Round(Vec3 a, Vec3 b, double radius) {

    Box bounds() {
        return new Box(Math.min(a.x, b.x) - radius, Math.min(a.y, b.y) - radius, Math.min(a.z, b.z) - radius,
                Math.max(a.x, b.x) + radius, Math.max(a.y, b.y) + radius, Math.max(a.z, b.z) + radius);
    }

    Round moved(Vec3 by) {
        return new Round(a.plus(by), b.plus(by), radius);
    }

    /** The point on the a-b line nearest to p. */
    Vec3 closestTo(Vec3 p) {
        return Geometry.closestOnSegment(a, b, p);
    }
}
