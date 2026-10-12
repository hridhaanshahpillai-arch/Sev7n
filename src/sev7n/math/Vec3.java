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
