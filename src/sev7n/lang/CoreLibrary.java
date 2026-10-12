package sev7n.lang;

import sev7n.lang.runtime.Values;
import sev7n.math.Vec3;

import java.awt.Color;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Built-in functions that every Seven script gets, with or without the engine:
 * printing, math, lists, strings and colors.
 */
final class CoreLibrary {

    private static final Random RANDOM = new Random();

    private CoreLibrary() { }

    static void install(Interpreter seven) {
        installGeneral(seven);
        installMath(seven);
        installLists(seven);
        installColors(seven);
    }

    private static void installGeneral(Interpreter seven) {
        seven.defineNative("print", 0, -1, args -> {
            seven.print(args.values().stream().map(Values::stringify).collect(Collectors.joining(" ")));
            return null;
        });
        seven.defineNative("str", 1, 1, args -> Values.stringify(args.get(0)));
        seven.defineNative("type", 1, 1, args -> Values.typeName(args.get(0)));
        seven.defineNative("num", 1, 1, args -> {
            if (args.get(0) instanceof Double d) return d;
            try {
                return Double.parseDouble(args.string(0).trim());
            } catch (NumberFormatException e) {
                throw args.error("'" + args.string(0) + "' is not a number");
            }
        });
    }

    private static void installMath(Interpreter seven) {
        seven.defineGlobal("PI", Math.PI);

        seven.defineNative("abs", 1, 1, args -> Math.abs(args.number(0)));
        seven.defineNative("sqrt", 1, 1, args -> Math.sqrt(args.number(0)));
        seven.defineNative("sin", 1, 1, args -> Math.sin(args.number(0)));
        seven.defineNative("cos", 1, 1, args -> Math.cos(args.number(0)));
        seven.defineNative("atan2", 2, 2, args -> Math.atan2(args.number(0), args.number(1)));
        seven.defineNative("floor", 1, 1, args -> Math.floor(args.number(0)));
        seven.defineNative("ceil", 1, 1, args -> Math.ceil(args.number(0)));
        seven.defineNative("round", 1, 1, args -> (double) Math.round(args.number(0)));
        seven.defineNative("min", 2, 2, args -> Math.min(args.number(0), args.number(1)));
        seven.defineNative("max", 2, 2, args -> Math.max(args.number(0), args.number(1)));
        seven.defineNative("clamp", 3, 3, args ->
                Math.max(args.number(1), Math.min(args.number(2), args.number(0))));
        seven.defineNative("lerp", 3, 3, args ->
                args.number(0) + (args.number(1) - args.number(0)) * args.number(2));
        // distance(a, b) for two vec3s, or distance(x1, y1, x2, y2) for 2D points
        seven.defineNative("distance", 2, 4, args -> switch (args.size()) {
            case 2 -> args.vec3(0).distance(args.vec3(1));
            case 4 -> Math.hypot(args.number(2) - args.number(0), args.number(3) - args.number(1));
            default -> throw args.error("expects two vec3s, or x1, y1, x2, y2");
        });

        // vec3() -> 0,0,0   vec3(n) -> n,n,n   vec3(x, y, z)
        seven.defineNative("vec3", 0, 3, args -> switch (args.size()) {
            case 0 -> new Vec3();
            case 1 -> new Vec3(args.number(0), args.number(0), args.number(0));
            case 3 -> new Vec3(args.number(0), args.number(1), args.number(2));
            default -> throw args.error("expects 0, 1 or 3 numbers");
        });

        // random() -> 0..1,  random(max) -> 0..max,  random(min, max) -> min..max
        seven.defineNative("random", 0, 2, args -> switch (args.size()) {
            case 0 -> RANDOM.nextDouble();
            case 1 -> RANDOM.nextDouble() * args.number(0);
            default -> args.number(0) + RANDOM.nextDouble() * (args.number(1) - args.number(0));
        });
        // randint(min, max) -> whole number, both ends included
        seven.defineNative("randint", 2, 2, args -> {
            int min = args.integer(0);
            int max = args.integer(1);
            if (max < min) throw args.error("max must be at least min");
            return (double) (min + RANDOM.nextInt(max - min + 1));
        });
    }

    private static void installLists(Interpreter seven) {
        seven.defineNative("len", 1, 1, args -> switch (args.get(0)) {
            case List<?> list -> (double) list.size();
            case String s -> (double) s.length();
            case null, default -> throw args.error("expects a list or string");
        });
        seven.defineNative("push", 2, 2, args -> {
            args.list(0).add(args.get(1));
            return null;
        });
        seven.defineNative("pop", 1, 1, args -> {
            List<Object> list = args.list(0);
            if (list.isEmpty()) throw args.error("the list is empty");
            return list.remove(list.size() - 1);
        });
        seven.defineNative("remove", 2, 2, args -> {
            List<Object> list = args.list(0);
            int i = args.integer(1);
            if (i < 0 || i >= list.size()) throw args.error("index " + i + " is out of range");
            return list.remove(i);
        });
        seven.defineNative("contains", 2, 2, args -> args.list(0).contains(args.get(1)));
    }

    private static void installColors(Interpreter seven) {
        // rgb(255, 128, 0) or rgb(255, 128, 0, 100) for transparency
        seven.defineNative("rgb", 3, 4, args -> new Color(
                channel(args.number(0)), channel(args.number(1)), channel(args.number(2)),
                args.has(3) ? channel(args.number(3)) : 255));
    }

    private static int channel(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }
}
