package sev7n.lang;

import sev7n.lang.runtime.SevenCallable;
import sev7n.lang.runtime.Values;
import sev7n.math.Easing;
import sev7n.math.Noise;
import sev7n.math.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        installText(seven);
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
        seven.defineGlobal("TAU", Math.PI * 2);
        seven.defineGlobal("E", Math.E);
        seven.defineGlobal("INFINITY", Double.POSITIVE_INFINITY);

        seven.defineNative("abs", 1, 1, args -> Math.abs(args.number(0)));
        seven.defineNative("sqrt", 1, 1, args -> Math.sqrt(args.number(0)));
        seven.defineNative("pow", 2, 2, args -> Math.pow(args.number(0), args.number(1)));
        seven.defineNative("exp", 1, 1, args -> Math.exp(args.number(0)));
        seven.defineNative("log", 1, 2, args -> args.has(1)
                ? Math.log(args.number(0)) / Math.log(args.number(1)) // log(x, base)
                : Math.log(args.number(0)));
        seven.defineNative("sign", 1, 1, args -> Math.signum(args.number(0)));
        seven.defineNative("sin", 1, 1, args -> Math.sin(args.number(0)));
        seven.defineNative("cos", 1, 1, args -> Math.cos(args.number(0)));
        seven.defineNative("tan", 1, 1, args -> Math.tan(args.number(0)));
        seven.defineNative("asin", 1, 1, args -> Math.asin(args.number(0)));
        seven.defineNative("acos", 1, 1, args -> Math.acos(args.number(0)));
        seven.defineNative("atan", 1, 1, args -> Math.atan(args.number(0)));
        seven.defineNative("atan2", 2, 2, args -> Math.atan2(args.number(0), args.number(1)));
        seven.defineNative("deg", 1, 1, args -> Math.toDegrees(args.number(0)));
        seven.defineNative("rad", 1, 1, args -> Math.toRadians(args.number(0)));
        seven.defineNative("floor", 1, 1, args -> Math.floor(args.number(0)));
        seven.defineNative("ceil", 1, 1, args -> Math.ceil(args.number(0)));
        // round(x) or round(x, digits): round(3.14159, 2) -> 3.14
        seven.defineNative("round", 1, 2, args -> {
            double scale = Math.pow(10, args.has(1) ? args.integer(1) : 0);
            return Math.round(args.number(0) * scale) / scale;
        });
        // min(a, b, ...) or min(list)
        seven.defineNative("min", 1, -1, args -> numbers(args).stream().mapToDouble(Double::doubleValue).min()
                .orElseThrow(() -> args.error("needs at least one number")));
        seven.defineNative("max", 1, -1, args -> numbers(args).stream().mapToDouble(Double::doubleValue).max()
                .orElseThrow(() -> args.error("needs at least one number")));
        seven.defineNative("sum", 1, -1, args -> numbers(args).stream().mapToDouble(Double::doubleValue).sum());
        seven.defineNative("average", 1, -1, args -> numbers(args).stream().mapToDouble(Double::doubleValue).average()
                .orElseThrow(() -> args.error("needs at least one number")));
        seven.defineNative("clamp", 3, 3, args ->
                Math.max(args.number(1), Math.min(args.number(2), args.number(0))));
        // lerp(a, b, t): numbers, vec3s or colors, 't' of the way from a to b
        seven.defineNative("lerp", 3, 3, args -> lerp(args.get(0), args.get(1), args.number(2), args));
        seven.defineNative("inverseLerp", 3, 3, args -> {
            double a = args.number(0);
            double b = args.number(1);
            return a == b ? 0.0 : (args.number(2) - a) / (b - a);
        });
        // remap(value, fromMin, fromMax, toMin, toMax): e.g. health 0..100 to a bar 0..1
        seven.defineNative("remap", 5, 5, args -> {
            double from = args.number(2) - args.number(1);
            double t = from == 0 ? 0 : (args.number(0) - args.number(1)) / from;
            return args.number(3) + (args.number(4) - args.number(3)) * t;
        });
        seven.defineNative("smoothstep", 3, 3, args -> {
            double a = args.number(0);
            double b = args.number(1);
            double t = a == b ? 0 : Math.max(0, Math.min(1, (args.number(2) - a) / (b - a)));
            return t * t * (3 - 2 * t);
        });
        // moveTowards(current, target, step): numbers or vec3s, never overshooting
        seven.defineNative("moveTowards", 3, 3, args -> {
            if (args.get(0) instanceof Vec3 v) return v.moveTowards(args.vec3(1), args.number(2));
            double current = args.number(0);
            double target = args.number(1);
            double step = args.number(2);
            return Math.abs(target - current) <= step ? target : current + Math.signum(target - current) * step;
        });
        // angles in degrees: the shortest turn from a to b (-180..180), and lerping the short way round
        seven.defineNative("deltaAngle", 2, 2, args -> deltaAngle(args.number(0), args.number(1)));
        seven.defineNative("lerpAngle", 3, 3, args -> args.number(0) + deltaAngle(args.number(0), args.number(1)) * args.number(2));
        // wrap(x, min, max): goes round like a clock;  pingPong(t, length): back and forth 0..length
        seven.defineNative("wrap", 3, 3, args -> {
            double min = args.number(1);
            double range = args.number(2) - min;
            return range == 0 ? min : min + ((args.number(0) - min) % range + range) % range;
        });
        seven.defineNative("pingPong", 2, 2, args -> {
            double length = args.number(1);
            if (length == 0) return 0.0;
            double t = ((args.number(0) % (length * 2)) + length * 2) % (length * 2);
            return length - Math.abs(t - length);
        });
        // ease("outBounce", t): curves for smooth movement (see Easing for every name)
        seven.defineNative("ease", 2, 2, args -> {
            String name = args.string(0);
            if (!Easing.exists(name)) throw args.error("no easing called '" + name + "'. Try one of " + Easing.names());
            return Easing.apply(name, args.number(1));
        });
        // noise(x), noise(x, y), noise(x, y, z): smooth randomness 0..1
        seven.defineNative("noise", 1, 3, args -> Noise.at(args.number(0),
                args.has(1) ? args.number(1) : 0, args.has(2) ? args.number(2) : 0));
        // distance(a, b) for two vec3s, or distance(x1, y1, x2, y2) for 2D points
        seven.defineNative("distance", 2, 4, args -> switch (args.size()) {
            case 2 -> args.vec3(0).distance(args.vec3(1));
            case 4 -> Math.hypot(args.number(2) - args.number(0), args.number(3) - args.number(1));
            default -> throw args.error("expects two vec3s, or x1, y1, x2, y2");
        });

        // vec3() -> 0,0,0   vec3(n) -> n,n,n   vec3(x, y, z);  vec2(x, y) -> x,y,0 for 2D games
        seven.defineNative("vec3", 0, 3, args -> switch (args.size()) {
            case 0 -> new Vec3();
            case 1 -> new Vec3(args.number(0), args.number(0), args.number(0));
            case 3 -> new Vec3(args.number(0), args.number(1), args.number(2));
            default -> throw args.error("expects 0, 1 or 3 numbers");
        });
        seven.defineNative("vec2", 2, 2, args -> new Vec3(args.number(0), args.number(1), 0));
        // handy directions (new each time, so changing one never changes another)
        seven.defineNative("up", 0, 0, args -> new Vec3(0, 1, 0));
        seven.defineNative("down", 0, 0, args -> new Vec3(0, -1, 0));
        seven.defineNative("left", 0, 0, args -> new Vec3(-1, 0, 0));
        seven.defineNative("right", 0, 0, args -> new Vec3(1, 0, 0));
        seven.defineNative("forward", 0, 0, args -> new Vec3(0, 0, -1));
        seven.defineNative("back", 0, 0, args -> new Vec3(0, 0, 1));

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
        seven.defineNative("chance", 1, 1, args -> RANDOM.nextDouble() < args.number(0)); // chance(0.25): true a quarter of the time
        seven.defineNative("choose", 1, 1, args -> {
            List<Object> list = args.list(0);
            if (list.isEmpty()) throw args.error("the list is empty");
            return list.get(RANDOM.nextInt(list.size()));
        });
        seven.defineNative("shuffle", 1, 1, args -> {
            java.util.Collections.shuffle(args.list(0), RANDOM);
            return args.list(0);
        });
        // a random direction (length 1): randomDirection() in 3D, randomDirection(true) flat on X/Y for 2D
        seven.defineNative("randomDirection", 0, 1, args -> {
            if (args.has(0) && Values.isTruthy(args.get(0))) {
                double a = RANDOM.nextDouble() * Math.PI * 2;
                return new Vec3(Math.cos(a), Math.sin(a), 0);
            }
            double z = RANDOM.nextDouble() * 2 - 1;
            double a = RANDOM.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(1 - z * z);
            return new Vec3(r * Math.cos(a), r * Math.sin(a), z);
        });
        // seed(n): makes random() and noise() repeat the same numbers every run (for levels made from a seed)
        seven.defineNative("seed", 1, 1, args -> {
            RANDOM.setSeed((long) args.number(0));
            Noise.reseed((long) args.number(0));
            return null;
        });
    }

    /** The numbers given as separate arguments, or in one list. */
    private static List<Double> numbers(sev7n.lang.runtime.Args args) {
        List<?> values = args.size() == 1 && args.get(0) instanceof List<?> list ? list : args.values();
        List<Double> numbers = new java.util.ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Double d)) throw args.error("expects numbers, but got " + Values.typeName(value));
            numbers.add(d);
        }
        return numbers;
    }

    private static Object lerp(Object a, Object b, double t, sev7n.lang.runtime.Args args) {
        if (a instanceof Double x && b instanceof Double y) return x + (y - x) * t;
        if (a instanceof Vec3 v && b instanceof Vec3 w) return v.lerp(w, t);
        if (a instanceof Color c && b instanceof Color d) {
            return new Color(channel(c.getRed() + (d.getRed() - c.getRed()) * t), channel(c.getGreen() + (d.getGreen() - c.getGreen()) * t),
                    channel(c.getBlue() + (d.getBlue() - c.getBlue()) * t), channel(c.getAlpha() + (d.getAlpha() - c.getAlpha()) * t));
        }
        throw args.error("expects two numbers, two vec3s or two colors, then t");
    }

    private static double deltaAngle(double from, double to) {
        double delta = ((to - from) % 360 + 540) % 360 - 180;
        return delta == -180 ? 180 : delta;
    }

    private static void installLists(Interpreter seven) {
        seven.defineNative("len", 1, 1, args -> switch (args.get(0)) {
            case List<?> list -> (double) list.size();
            case String s -> (double) s.length();
            case Map<?, ?> map -> (double) map.size();
            case null, default -> throw args.error("expects a list, string or map");
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
        // insert(list, index, item): puts it before the item at index (len(list) adds it at the end)
        seven.defineNative("insert", 3, 3, args -> {
            List<Object> list = args.list(0);
            int i = args.integer(1);
            if (i < 0) i += list.size();
            if (i < 0 || i > list.size()) throw args.error("index " + args.integer(1) + " is out of range (size is " + list.size() + ")");
            list.add(i, args.get(2));
            return null;
        });
        // remove(list, index) takes out the item at that index; remove(map, key) takes out that key. Both return what was removed.
        seven.defineNative("remove", 2, 2, args -> {
            if (args.get(0) instanceof Map<?, ?>) return args.map(0).remove(Values.stringify(args.get(1)));
            List<Object> list = args.list(0);
            int i = args.integer(1);
            if (i < 0) i += list.size();
            if (i < 0 || i >= list.size()) throw args.error("index " + args.integer(1) + " is out of range (size is " + list.size() + ")");
            return list.remove(i);
        });
        // removeItem(list, item): takes out the first item equal to it; true if there was one
        seven.defineNative("removeItem", 2, 2, args -> {
            List<Object> list = args.list(0);
            for (int i = 0; i < list.size(); i++) {
                if (Values.isEqual(list.get(i), args.get(1))) {
                    list.remove(i);
                    return true;
                }
            }
            return false;
        });
        seven.defineNative("clear", 1, 1, args -> {
            if (args.get(0) instanceof Map<?, ?>) args.map(0).clear();
            else args.list(0).clear();
            return null;
        });
        seven.defineNative("contains", 2, 2, args -> switch (args.get(0)) {
            case Map<?, ?> map -> map.containsKey(Values.stringify(args.get(1)));
            case String s -> s.contains(Values.stringify(args.get(1)));
            default -> args.list(0).stream().anyMatch(x -> Values.isEqual(x, args.get(1)));
        });
        // indexOf(list, item) or indexOf(text, piece): where it first is, or -1
        seven.defineNative("indexOf", 2, 2, args -> {
            if (args.get(0) instanceof String s) return (double) s.indexOf(Values.stringify(args.get(1)));
            List<Object> list = args.list(0);
            for (int i = 0; i < list.size(); i++) if (Values.isEqual(list.get(i), args.get(1))) return (double) i;
            return -1.0;
        });
        // slice(list or text, from, [to]): a copy of part of it; negative numbers count from the end
        seven.defineNative("slice", 2, 3, args -> {
            int size = args.get(0) instanceof String s ? s.length() : args.list(0).size();
            int from = clampIndex(args.integer(1), size);
            int to = args.has(2) ? clampIndex(args.integer(2), size) : size;
            if (to < from) to = from;
            if (args.get(0) instanceof String s) return s.substring(from, to);
            return new ArrayList<>(args.list(0).subList(from, to));
        });
        seven.defineNative("copy", 1, 1, args -> switch (args.get(0)) {
            case Map<?, ?> map -> new LinkedHashMap<>(args.map(0));
            case Vec3 v -> v.copy();
            default -> new ArrayList<>(args.list(0));
        });
        seven.defineNative("reverse", 1, 1, args -> {
            Collections.reverse(args.list(0));
            return args.list(0);
        });
        // sort(list) sorts numbers or text; sort(list, func(item) { return ... }) sorts by what the function gives
        seven.defineNative("sort", 1, 2, args -> {
            List<Object> list = args.list(0);
            if (args.has(1)) {
                SevenCallable key = args.function(1);
                Map<Object, Object> keys = new java.util.IdentityHashMap<>();
                for (Object item : list) keys.put(item, seven.callNested(key, item));
                list.sort((a, b) -> compareValues(keys.get(a), keys.get(b), args));
            } else {
                list.sort((a, b) -> compareValues(a, b, args));
            }
            return list;
        });
        // with a function: filter keeps the items it says true for, map makes a list of what it gives back
        seven.defineNative("filter", 2, 2, args -> {
            List<Object> kept = new ArrayList<>();
            for (Object item : new ArrayList<>(args.list(0))) if (Values.isTruthy(seven.callNested(args.function(1), item))) kept.add(item);
            return kept;
        });
        seven.defineNative("map", 2, 2, args -> {
            List<Object> out = new ArrayList<>();
            for (Object item : new ArrayList<>(args.list(0))) out.add(seven.callNested(args.function(1), item));
            return out;
        });
        // first(list, test): the first item the function says true for, or none
        seven.defineNative("first", 2, 2, args -> {
            for (Object item : new ArrayList<>(args.list(0))) if (Values.isTruthy(seven.callNested(args.function(1), item))) return item;
            return null;
        });
        seven.defineNative("any", 2, 2, args -> {
            for (Object item : new ArrayList<>(args.list(0))) if (Values.isTruthy(seven.callNested(args.function(1), item))) return true;
            return false;
        });
        seven.defineNative("all", 2, 2, args -> {
            for (Object item : new ArrayList<>(args.list(0))) if (!Values.isTruthy(seven.callNested(args.function(1), item))) return false;
            return true;
        });

        // maps: {name: "Bob", hp: 100}
        seven.defineNative("keys", 1, 1, args -> new ArrayList<Object>(args.map(0).keySet()));
        seven.defineNative("values", 1, 1, args -> new ArrayList<>(args.map(0).values()));
        seven.defineNative("has", 2, 2, args -> args.map(0).containsKey(Values.stringify(args.get(1))));
        // get(map, key, [fallback]): the value, or the fallback (none if left out) when the key isn't there
        seven.defineNative("get", 2, 3, args -> {
            Map<String, Object> map = args.map(0);
            String key = Values.stringify(args.get(1));
            return map.containsKey(key) ? map.get(key) : args.has(2) ? args.get(2) : null;
        });
    }

    private static int clampIndex(int index, int size) {
        if (index < 0) index += size;
        return Math.max(0, Math.min(size, index));
    }

    private static int compareValues(Object a, Object b, sev7n.lang.runtime.Args args) {
        if (a instanceof Double x && b instanceof Double y) return Double.compare(x, y);
        if (a instanceof String x && b instanceof String y) return x.compareToIgnoreCase(y);
        throw args.error("can only sort numbers or text, not a " + Values.typeName(a) + " and a " + Values.typeName(b));
    }

    /** Text: upper("hi"), split("a,b", ","), join(list, ", "), fixed(3.14159, 2)... */
    private static void installText(Interpreter seven) {
        seven.defineNative("upper", 1, 1, args -> args.string(0).toUpperCase());
        seven.defineNative("lower", 1, 1, args -> args.string(0).toLowerCase());
        seven.defineNative("trim", 1, 1, args -> args.string(0).strip());
        seven.defineNative("replace", 3, 3, args -> args.string(0).replace(Values.stringify(args.get(1)), Values.stringify(args.get(2))));
        seven.defineNative("startsWith", 2, 2, args -> args.string(0).startsWith(Values.stringify(args.get(1))));
        seven.defineNative("endsWith", 2, 2, args -> args.string(0).endsWith(Values.stringify(args.get(1))));
        seven.defineNative("repeat", 2, 2, args -> args.string(0).repeat(Math.max(0, args.integer(1))));
        // split("a,b,c", ",") -> ["a", "b", "c"];  split("abc") -> ["a", "b", "c"]
        seven.defineNative("split", 1, 2, args -> {
            String text = args.string(0);
            String separator = args.has(1) ? Values.stringify(args.get(1)) : "";
            List<Object> parts = new ArrayList<>();
            if (separator.isEmpty()) {
                for (char c : text.toCharArray()) parts.add(String.valueOf(c));
            } else {
                int start = 0;
                for (int at = text.indexOf(separator); at >= 0; at = text.indexOf(separator, start)) {
                    parts.add(text.substring(start, at));
                    start = at + separator.length();
                }
                parts.add(text.substring(start));
            }
            return parts;
        });
        seven.defineNative("join", 1, 2, args -> args.list(0).stream().map(Values::stringify)
                .collect(Collectors.joining(args.has(1) ? Values.stringify(args.get(1)) : "")));
        // fixed(3.14159, 2) -> "3.14" (always that many decimals);  fixed(7, 0, 2) -> "07" (at least 2 digits before the point)
        seven.defineNative("fixed", 2, 3, args -> {
            int decimals = Math.max(0, Math.min(10, args.integer(1)));
            String text = String.format(java.util.Locale.ROOT, "%." + decimals + "f", args.number(0));
            if (args.has(2)) {
                boolean negative = text.startsWith("-");
                String digits = negative ? text.substring(1) : text;
                int whole = digits.indexOf('.') < 0 ? digits.length() : digits.indexOf('.');
                digits = "0".repeat(Math.max(0, args.integer(2) - whole)) + digits;
                text = (negative ? "-" : "") + digits;
            }
            return text;
        });
    }

    private static void installColors(Interpreter seven) {
        // rgb(255, 128, 0) or rgb(255, 128, 0, 100) for transparency
        seven.defineNative("rgb", 3, 4, args -> new Color(
                channel(args.number(0)), channel(args.number(1)), channel(args.number(2)),
                args.has(3) ? channel(args.number(3)) : 255));
        // hsv(hue 0..360, saturation 0..1, brightness 0..1): rainbows are easy, hsv(time() * 90 % 360, 1, 1)
        seven.defineNative("hsv", 3, 4, args -> {
            Color c = Color.getHSBColor((float) (args.number(0) / 360), (float) Math.max(0, Math.min(1, args.number(1))),
                    (float) Math.max(0, Math.min(1, args.number(2))));
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), args.has(3) ? channel(args.number(3)) : 255);
        });
    }

    private static int channel(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }
}
