package sev7n.lang.runtime;

import sev7n.lang.SevenError;
import sev7n.math.Vec3;

import java.awt.Color;
import java.util.List;

/**
 * The arguments passed to a built-in function, with typed getters that
 * produce friendly errors like "rect() expects a number for argument 2".
 */
public record Args(String function, List<Object> values, int line) {

    public int size() {
        return values.size();
    }

    public boolean has(int index) {
        return index < values.size();
    }

    public Object get(int index) {
        return values.get(index);
    }

    public double number(int index) {
        if (get(index) instanceof Double d) return d;
        throw wrongType(index, "a number");
    }

    public int integer(int index) {
        return (int) Math.floor(number(index));
    }

    public String string(int index) {
        if (get(index) instanceof String s) return s;
        throw wrongType(index, "a string");
    }

    public Color color(int index) {
        if (get(index) instanceof Color c) return c;
        throw wrongType(index, "a color like #ff8800");
    }

    public Vec3 vec3(int index) {
        if (get(index) instanceof Vec3 v) return v;
        throw wrongType(index, "a vec3, like vec3(1, 2, 3)");
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(int index) {
        if (get(index) instanceof List<?> list) return (List<Object>) list;
        throw wrongType(index, "a list");
    }

    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> map(int index) {
        if (get(index) instanceof java.util.Map<?, ?> map) return (java.util.Map<String, Object>) map;
        throw wrongType(index, "a map, like {hp: 100}");
    }

    public SevenCallable function(int index) {
        if (get(index) instanceof SevenCallable f) return f;
        throw wrongType(index, "a function, like func(x) { return x * 2 }");
    }

    public SevenError error(String message) {
        return new SevenError(function + "(): " + message, line);
    }

    private SevenError wrongType(int index, String expected) {
        return error("argument " + (index + 1) + " should be " + expected
                + ", but got " + Values.typeName(get(index)));
    }
}
