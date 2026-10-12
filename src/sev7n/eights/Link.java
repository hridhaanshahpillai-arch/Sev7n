package sev7n.eights;

/** A wire from one node's output to another node's input. */
public record Link(int id, Node from, int output, Node to, int input) {

    public ValueType type() {
        return from.type.outputs.get(output).type();
    }
}
