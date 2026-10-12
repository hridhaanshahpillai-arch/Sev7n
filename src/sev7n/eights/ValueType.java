package sev7n.eights;

import sev7n.math.Vec3;

import java.awt.Color;

/** The kinds of values that flow along Eights wires. Each has its own pin color. */
public enum ValueType {

    EXEC("Exec", new Color(0xF6F6F4)),      // not a value: "then do this"
    NUMBER("Number", new Color(0x62E884)),
    BOOL("Bool", new Color(0xEE6666)),
    STRING("Text", new Color(0xF286C4)),
    VEC3("Vec3", new Color(0xE7EE98)),
    COLOR("Color", new Color(0xD0B8F4)),
    ENTITY("Entity", new Color(0x97E1F1)),
    ANY("Any", new Color(0xA9ABB6));

    /** Types a graph variable can have. */
    public static final ValueType[] VARIABLE_TYPES = {NUMBER, BOOL, STRING, VEC3, COLOR};

    public final String label;
    public final Color color;

    ValueType(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    /** A sensible starting value for an unconnected input or a new variable. */
    public Object defaultValue() {
        return switch (this) {
            case NUMBER, ANY -> 0.0;
            case BOOL -> false;
            case STRING -> "";
            case VEC3 -> new Vec3();
            case COLOR -> new Color(0xBF9EEE);
            case EXEC, ENTITY -> null; // an unconnected entity input means "this object"
        };
    }

    /** Can a wire go from an output of type 'from' to an input of type 'to'? */
    public static boolean compatible(ValueType from, ValueType to) {
        if (from == EXEC || to == EXEC) return from == to;
        return from == to || from == ANY || to == ANY;
    }
}
