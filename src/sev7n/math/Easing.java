package sev7n.math;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/**
 * Easing curves: ways to go from 0 to 1 that start or stop gently, overshoot, or bounce.
 * Used by tweens and by scripts (ease("outBounce", t)).
 *
 *   in...     starts slowly        out...    ends slowly        inOut...  both
 *   Sine, Quad, Cubic, Quart, Expo: gentle to strong     Back: overshoots a little
 *   Elastic: wobbles like a spring                        Bounce: bounces like a ball
 */
public final class Easing {

    private Easing() { }

    private static final Map<String, DoubleUnaryOperator> CURVES = new LinkedHashMap<>();

    static {
        CURVES.put("linear", t -> t);
        add("Sine", t -> 1 - Math.cos(t * Math.PI / 2));
        add("Quad", t -> t * t);
        add("Cubic", t -> t * t * t);
        add("Quart", t -> t * t * t * t);
        add("Expo", t -> t == 0 ? 0 : Math.pow(2, 10 * t - 10));
        add("Back", t -> 2.70158 * t * t * t - 1.70158 * t * t);
        add("Elastic", t -> t == 0 || t == 1 ? t : -Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * (2 * Math.PI / 3)));
        add("Bounce", t -> 1 - bounceOut(1 - t));
    }

    /** Adds inX, outX (the mirror image) and inOutX (in for the first half, out for the second). */
    private static void add(String name, DoubleUnaryOperator in) {
        CURVES.put("in" + name, in);
        CURVES.put("out" + name, t -> 1 - in.applyAsDouble(1 - t));
        CURVES.put("inOut" + name, t -> t < 0.5 ? in.applyAsDouble(t * 2) / 2 : 1 - in.applyAsDouble(2 - t * 2) / 2);
    }

    private static double bounceOut(double t) {
        double n = 7.5625;
        double d = 2.75;
        if (t < 1 / d) return n * t * t;
        if (t < 2 / d) return n * (t -= 1.5 / d) * t + 0.75;
        if (t < 2.5 / d) return n * (t -= 2.25 / d) * t + 0.9375;
        return n * (t -= 2.625 / d) * t + 0.984375;
    }

    public static Set<String> names() {
        return CURVES.keySet();
    }

    public static boolean exists(String name) {
        return CURVES.containsKey(name);
    }

    /** The curve's value at t (0..1, clamped). Unknown names are linear. */
    public static double apply(String name, double t) {
        double clamped = Math.max(0, Math.min(1, t));
        return CURVES.getOrDefault(name, CURVES.get("linear")).applyAsDouble(clamped);
    }
}
