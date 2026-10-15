package sev7n.lang.runtime;

import sev7n.math.Vec3;

import java.awt.Color;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Helpers for Seven's dynamic values.
 *
 * Seven value  ->  Java type
 *   number     ->  Double
 *   string     ->  String
 *   bool       ->  Boolean
 *   none       ->  null
 *   color      ->  java.awt.Color
 *   vec3       ->  sev7n.math.Vec3
 *   list       ->  java.util.List
 *   map        ->  java.util.Map (String keys, in the order they were added)
 *   range      ->  SevenRange
 *   function   ->  SevenCallable
 *   objects    ->  SevenObject (styles, behaviours, engine entities...)
 */
public final class Values {

    private Values() { }

    /** none and false are falsy; everything else is truthy. */
    public static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        return true;
    }

    public static boolean isEqual(Object a, Object b) {
        return Objects.equals(a, b);
    }

    public static String typeName(Object value) {
        return switch (value) {
            case null -> "none";
            case Double d -> "number";
            case String s -> "string";
            case Boolean b -> "bool";
            case Color c -> "color";
            case Vec3 v -> "vec3";
            case List<?> l -> "list";
            case java.util.Map<?, ?> m -> "map";
            case SevenRange r -> "range";
            case SevenCallable f -> "function";
            case BehaviourClass b -> "behaviour";
            case SevenObject o -> o.typeName();
            default -> value.getClass().getSimpleName();
        };
    }

    public static String stringify(Object value) {
        return switch (value) {
            case null -> "none";
            case Double d -> formatNumber(d);
            case Color c -> c.getAlpha() == 255
                    ? String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue())
                    : String.format("#%02x%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
            case List<?> list -> list.stream().map(Values::stringify).collect(Collectors.joining(", ", "[", "]"));
            case java.util.Map<?, ?> map -> map.entrySet().stream()
                    .map(e -> e.getKey() + ": " + (e.getValue() instanceof String s ? '"' + s + '"' : stringify(e.getValue())))
                    .collect(Collectors.joining(", ", "{", "}"));
            case SevenCallable f -> "<func " + f.name() + ">";
            default -> value.toString();
        };
    }

    /** 3.0 prints as "3", 2.5 prints as "2.5". */
    private static String formatNumber(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }
}
