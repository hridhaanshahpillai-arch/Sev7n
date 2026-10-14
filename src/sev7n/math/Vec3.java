package sev7n.math;

import java.util.Locale;

/**
 * A mutable 3D vector, shared by the scene (positions, rotations, scales)
 * and by Seven scripts (the "vec3" value type).
 *
 * Mutating methods (set, add...) change this vector. Math methods that
 * return a vector (plus, minus, times...) create a new one.
 */
public final class Vec3 {

    public double x;
    public double y;
    public double z;

    public Vec3() {
        this(0, 0, 0);
    }

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3 copy() {
        return new Vec3(x, y, z);
    }

    public Vec3 set(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public Vec3 set(Vec3 other) {
        return set(other.x, other.y, other.z);
    }

    // ---- math that returns a new vector ----

    public Vec3 plus(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 minus(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 times(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public Vec3 negated() {
        return new Vec3(-x, -y, -z);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(dot(this));
    }

    public double distance(Vec3 o) {
        return minus(o).length();
    }

    /** Same direction, length 1. The zero vector stays zero. */
    public Vec3 normalized() {
        double length = length();
        return length == 0 ? new Vec3() : times(1 / length);
    }

    /** The point 't' of the way from this to 'o' (0 = this, 1 = o). */
    public Vec3 lerp(Vec3 o, double t) {
        return new Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Moves toward 'target' by at most 'maxDistance', without overshooting. */
    public Vec3 moveTowards(Vec3 target, double maxDistance) {
        Vec3 delta = target.minus(this);
        double distance = delta.length();
        return distance <= maxDistance || distance == 0 ? target.copy() : plus(delta.times(maxDistance / distance));
    }

    /** The angle between two directions, in degrees (0..180). */
    public double angle(Vec3 o) {
        double lengths = length() * o.length();
        return lengths == 0 ? 0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(o) / lengths))));
    }

    /** The part of this vector that points along 'onto'. */
    public Vec3 project(Vec3 onto) {
        double lengthSquared = onto.dot(onto);
        return lengthSquared == 0 ? new Vec3() : onto.times(dot(onto) / lengthSquared);
    }

    /** Bounces this direction off a surface facing 'normal' (like a ball off a wall). */
    public Vec3 reflect(Vec3 normal) {
        Vec3 n = normal.normalized();
        return minus(n.times(2 * dot(n)));
    }

    /** Turned around 'axis' by 'degrees' (right-hand rule: positive turns counter-clockwise looking down the axis). */
    public Vec3 rotate(Vec3 axis, double degrees) {
        Vec3 k = axis.normalized();
        double a = Math.toRadians(degrees);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        // Rodrigues' rotation formula
        return times(cos).plus(k.cross(this).times(sin)).plus(k.times(k.dot(this) * (1 - cos)));
    }

    /** Same direction, at most 'max' long. */
    public Vec3 clampLength(double max) {
        double length = length();
        return length <= max ? copy() : times(max / length);
    }

    public double[] toArray() {
        return new double[] {x, y, z};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Vec3 v && v.x == x && v.y == y && v.z == z;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(x) * 31 * 31 + Double.hashCode(y) * 31 + Double.hashCode(z);
    }

    @Override
    public String toString() {
        return "vec3(" + format(x) + ", " + format(y) + ", " + format(z) + ")";
    }

    private static String format(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return String.format(Locale.ROOT, "%.3f", d).replaceAll("0+$", "");
    }
}
