package sev7n.lang.runtime;

import sev7n.lang.SevenError;
import sev7n.math.Vec3;

/**
 * Properties and methods of vec3 values in scripts:
 *   v.x  v.y  v.z  v.length
 *   v.normalized()  v.copy()  v.dot(w)  v.cross(w)  v.distance(w)
 *   v.lerp(w, t)  v.moveTowards(w, step)  v.angle(w)  v.project(w)  v.reflect(normal)
 *   v.rotate(axis, degrees)  v.clampLength(max)
 */
public final class Vectors {

    private Vectors() { }

    public static Object get(Vec3 v, String name, int line) {
        return switch (name) {
            case "x" -> v.x;
            case "y" -> v.y;
            case "z" -> v.z;
            case "length" -> v.length();
            case "normalized" -> new NativeFunction(name, 0, 0, args -> v.normalized());
            case "copy" -> new NativeFunction(name, 0, 0, args -> v.copy());
            case "dot" -> new NativeFunction(name, 1, 1, args -> v.dot(args.vec3(0)));
            case "cross" -> new NativeFunction(name, 1, 1, args -> v.cross(args.vec3(0)));
            case "distance" -> new NativeFunction(name, 1, 1, args -> v.distance(args.vec3(0)));
            case "lerp" -> new NativeFunction(name, 2, 2, args -> v.lerp(args.vec3(0), args.number(1)));
            case "moveTowards" -> new NativeFunction(name, 2, 2, args -> v.moveTowards(args.vec3(0), args.number(1)));
            case "angle" -> new NativeFunction(name, 1, 1, args -> v.angle(args.vec3(0)));
            case "project" -> new NativeFunction(name, 1, 1, args -> v.project(args.vec3(0)));
            case "reflect" -> new NativeFunction(name, 1, 1, args -> v.reflect(args.vec3(0)));
            case "rotate" -> new NativeFunction(name, 2, 2, args -> v.rotate(args.vec3(0), args.number(1)));
            case "clampLength" -> new NativeFunction(name, 1, 1, args -> v.clampLength(args.number(0)));
            default -> throw new SevenError("A vec3 has no property '" + name + "'", line);
        };
    }

    public static void set(Vec3 v, String name, Object value, int line) {
        if (!(value instanceof Double d)) {
            throw new SevenError("vec3." + name + " must be a number, not " + Values.typeName(value), line);
        }
        switch (name) {
            case "x" -> v.x = d;
            case "y" -> v.y = d;
            case "z" -> v.z = d;
            default -> throw new SevenError("A vec3 has no settable property '" + name + "'", line);
        }
    }
}
