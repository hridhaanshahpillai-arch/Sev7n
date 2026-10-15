package sev7n.eights;

import java.util.HashMap;
import java.util.Map;

/** One node placed in a graph. */
public final class Node {

    public final int id;
    public final NodeType type;
    public float x;
    public float y;
    /** For Get / Set Variable nodes: which variable. */
    public String variable = "";
    /** Values typed into unconnected inputs, by input index. */
    private final Map<Integer, Object> values = new HashMap<>();

    Node(int id, NodeType type, float x, float y) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
    }

    /** The value for an unconnected input: what the user typed, or the pin's default. */
    public Object value(int input) {
        return values.containsKey(input) ? values.get(input) : type.inputs.get(input).defaultValue();
    }

    public void setValue(int input, Object value) {
        values.put(input, value);
    }

    Map<Integer, Object> values() {
        return values;
    }

    /** Title shown on the node, e.g. "Set health". */
    public String displayTitle() {
        if (!type.usesVariable) return type.title;
        String name = variable.isEmpty() ? "(variable)" : variable;
        if (type.id.equals(NodeLibrary.GET_VARIABLE)) return name;
        return (type.id.equals(NodeLibrary.SET_VARIABLE) ? "Set " : "Add to ") + name;
    }
}
