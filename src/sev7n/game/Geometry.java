package sev7n.game;

import sev7n.math.Vec3;

/**
 * The geometry physics needs: nearest points between lines and boxes, how far two shapes
 * overlap, and where a ray first hits a box or a round shape.
 */
final class Geometry {

    private Geometry() { }

    /** Two shapes overlap by 'depth' along 'normal' (pointing from the second shape toward the first). */
    record Overlap(Vec3 normal, double depth) { }

    /** Where a ray hit: how far along it, and the surface direction there. */
    record Hit(double distance, Vec3 normal) { }

    // ---------------------------------------------------------------- nearest points

    static Vec3 closestOnSegment(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.minus(a);
        double lengthSquared = ab.dot(ab);
        if (lengthSquared == 0) return a.copy();
        double t = Math.max(0, Math.min(1, p.minus(a).dot(ab) / lengthSquared));
        return a.plus(ab.times(t));
    }

    static Vec3 closestOnBox(Box box, Vec3 p) {
        return new Vec3(clamp(p.x, box.minX(), box.maxX()), clamp(p.y, box.minY(), box.maxY()), clamp(p.z, box.minZ(), box.maxZ()));
    }

    /** The nearest points between two line segments: {on the first, on the second}. */
    static Vec3[] closestBetweenSegments(Vec3 p1, Vec3 q1, Vec3 p2, Vec3 q2) {
        Vec3 d1 = q1.minus(p1);
        Vec3 d2 = q2.minus(p2);
        Vec3 r = p1.minus(p2);
        double a = d1.dot(d1);
        double e = d2.dot(d2);
        double f = d2.dot(r);
        double s;
        double t;
        if (a <= 1e-12 && e <= 1e-12) return new Vec3[] {p1.copy(), p2.copy()};
        if (a <= 1e-12) {
            s = 0;
            t = clamp(f / e, 0, 1);
        } else {
            double c = d1.dot(r);
            if (e <= 1e-12) {
                t = 0;
                s = clamp(-c / a, 0, 1);
            } else {
                double b = d1.dot(d2);
                double denominator = a * e - b * b;
                s = denominator != 0 ? clamp((b * f - c * e) / denominator, 0, 1) : 0;
                t = (b * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = clamp(-c / a, 0, 1);
                } else if (t > 1) {
                    t = 1;
                    s = clamp((b - c) / a, 0, 1);
                }
            }
        }
        return new Vec3[] {p1.plus(d1.times(s)), p2.plus(d2.times(t))};
    }

    /** The point on a segment nearest to a box (found by going back and forth a few times; exact for spheres). */
    static Vec3 closestOnSegmentToBox(Vec3 a, Vec3 b, Box box) {
        Vec3 center = new Vec3((box.minX() + box.maxX()) / 2, (box.minY() + box.maxY()) / 2, (box.minZ() + box.maxZ()) / 2);
        Vec3 p = closestOnSegment(a, b, center);
        for (int i = 0; i < 4; i++) p = closestOnSegment(a, b, closestOnBox(box, p));
        return p;
    }

    // ---------------------------------------------------------------- overlaps

    /** How a round shape overlaps a box, or null if they don't touch (within 'margin'). */
    static Overlap overlap(Round round, Box box, double margin) {
        Vec3 p = closestOnSegmentToBox(round.a(), round.b(), box);
        Vec3 q = closestOnBox(box, p);
        Vec3 delta = p.minus(q);
        double distance = delta.length();
        if (distance > round.radius() + margin) return null;
        if (distance > 1e-9) return new Overlap(delta.times(1 / distance), round.radius() - distance);
        // the line runs inside the box: push out the shortest way
        double[] push = {box.maxX() - p.x, p.x - box.minX(), box.maxY() - p.y, p.y - box.minY(), box.maxZ() - p.z, p.z - box.minZ()};
        int best = 0;
        for (int i = 1; i < 6; i++) if (push[i] < push[best]) best = i;
        Vec3 normal = switch (best) {
            case 0 -> new Vec3(1, 0, 0);
            case 1 -> new Vec3(-1, 0, 0);
            case 2 -> new Vec3(0, 1, 0);
            case 3 -> new Vec3(0, -1, 0);
            case 4 -> new Vec3(0, 0, 1);
            default -> new Vec3(0, 0, -1);
        };
        return new Overlap(normal, push[best] + round.radius());
    }

    /** How two round shapes overlap (normal from b toward a), or null if they don't touch. */
    static Overlap overlap(Round a, Round b, double margin) {
        Vec3[] points = closestBetweenSegments(a.a(), a.b(), b.a(), b.b());
        Vec3 delta = points[0].minus(points[1]);
        double distance = delta.length();
        double reach = a.radius() + b.radius();
        if (distance > reach + margin) return null;
        Vec3 normal = distance > 1e-9 ? delta.times(1 / distance) : new Vec3(0, 1, 0);
        return new Overlap(normal, reach - distance);
    }

    // ---------------------------------------------------------------- rays

    /** Where a ray (direction length 1) first enters a box, or null. A ray starting inside hits at distance 0. */
    static Hit ray(Vec3 origin, Vec3 direction, Box box, double maxDistance) {
        double near = 0;
        double far = maxDistance;
        int axisHit = -1;
        double[] o = origin.toArray();
        double[] d = direction.toArray();
        for (int axis = 0; axis < 3; axis++) {
            double min = box.min(axis);
            double max = box.max(axis);
            if (Math.abs(d[axis]) < 1e-12) {
                if (o[axis] < min || o[axis] > max) return null;
                continue;
            }
            double t1 = (min - o[axis]) / d[axis];
            double t2 = (max - o[axis]) / d[axis];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > near) {
                near = t1;
                axisHit = axis;
            }
            far = Math.min(far, t2);
            if (near > far) return null;
        }
        Vec3 normal = new Vec3();
        if (axisHit == 0) normal.x = -Math.signum(d[0]);
        if (axisHit == 1) normal.y = -Math.signum(d[1]);
        if (axisHit == 2) normal.z = -Math.signum(d[2]);
        if (axisHit < 0) normal = direction.negated(); // started inside
        return new Hit(near, normal);
    }

    /** Where a ray (direction length 1) first hits a round shape, or null. */
    static Hit ray(Vec3 origin, Vec3 direction, Round round, double maxDistance) {
        Hit best = sphere(origin, direction, round.a(), round.radius(), maxDistance);
        Hit other = sphere(origin, direction, round.b(), round.radius(), maxDistance);
        if (other != null && (best == null || other.distance() < best.distance())) best = other;
        Hit side = cylinder(origin, direction, round, maxDistance);
        if (side != null && (best == null || side.distance() < best.distance())) best = side;
        return best;
    }

    private static Hit sphere(Vec3 origin, Vec3 direction, Vec3 center, double radius, double maxDistance) {
        Vec3 m = origin.minus(center);
        double b = m.dot(direction);
        double c = m.dot(m) - radius * radius;
        if (c > 0 && b > 0) return null;
        double discriminant = b * b - c;
        if (discriminant < 0) return null;
        double t = Math.max(0, -b - Math.sqrt(discriminant));
        if (t > maxDistance) return null;
        Vec3 point = origin.plus(direction.times(t));
        Vec3 normal = point.minus(center).normalized();
        return new Hit(t, normal.length() == 0 ? direction.negated() : normal);
    }

    /** The side of a capsule (between its end spheres). */
    private static Hit cylinder(Vec3 origin, Vec3 direction, Round round, double maxDistance) {
        Vec3 axis = round.b().minus(round.a());
        double height = axis.length();
        if (height < 1e-9) return null;
        Vec3 w = axis.times(1 / height);
        Vec3 oc = origin.minus(round.a());
        Vec3 dPerp = direction.minus(w.times(direction.dot(w)));
        Vec3 ocPerp = oc.minus(w.times(oc.dot(w)));
        double a = dPerp.dot(dPerp);
        if (a < 1e-12) return null;
        double b = 2 * dPerp.dot(ocPerp);
        double c = ocPerp.dot(ocPerp) - round.radius() * round.radius();
        double discriminant = b * b - 4 * a * c;
        if (discriminant < 0) return null;
        double t = (-b - Math.sqrt(discriminant)) / (2 * a);
        if (t < 0 || t > maxDistance) return null;
        double along = oc.plus(direction.times(t)).dot(w);
        if (along < 0 || along > height) return null;
        Vec3 point = origin.plus(direction.times(t));
        return new Hit(t, point.minus(closestOnSegment(round.a(), round.b(), point)).normalized());
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
