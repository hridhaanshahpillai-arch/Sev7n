package sev7n.eights;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An Eights graph: nodes, the wires between them, and variables.
 *
 * Wiring rules (like Unreal Blueprints):
 *   - wires go from an output to an input of a compatible type
 *   - an exec output leads to one place; a data input takes one value
 *     (connecting again replaces the old wire)
 */
public final class Graph {

    private final List<Node> nodes = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    public final List<Variable> variables = new ArrayList<>();
    private int nextId = 1;
    private int version = 0;

    public List<Node> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<Link> links() {
        return Collections.unmodifiableList(links);
    }

    /** Bumps on every change, so editors know to save and recompile. */
    public int version() {
        return version;
    }

    public void touch() {
        version++;
    }

    public Node add(NodeType type, float x, float y) {
        Node node = new Node(nextId++, type, x, y);
        nodes.add(node);
        touch();
        return node;
    }

    /** Used when loading, to keep saved ids. */
    Node addWithId(int id, NodeType type, float x, float y) {
        Node node = new Node(id, type, x, y);
        nodes.add(node);
        nextId = Math.max(nextId, id + 1);
        return node;
    }

    public void remove(Node node) {
        links.removeIf(l -> l.from() == node || l.to() == node);
        nodes.remove(node);
        touch();
    }

    public void remove(Link link) {
        links.remove(link);
        touch();
    }

    public Node node(int id) {
        for (Node n : nodes) {
            if (n.id == id) return n;
        }
        return null;
    }

    public Link link(int id) {
        for (Link l : links) {
            if (l.id() == id) return l;
        }
        return null;
    }

    /** The wire leaving an output (exec outputs have at most one), or null. */
    public Link linkFrom(Node node, int output) {
        for (Link l : links) {
            if (l.from() == node && l.output() == output) return l;
        }
        return null;
    }

    /** The wire arriving at a data input (at most one), or null. */
    public Link linkTo(Node node, int input) {
        for (Link l : links) {
            if (l.to() == node && l.input() == input) return l;
        }
        return null;
    }

    public boolean canConnect(Node from, int output, Node to, int input) {
        if (from == to) return false;
        return ValueType.compatible(from.type.outputs.get(output).type(), to.type.inputs.get(input).type());
    }

    /** Connects an output to an input, replacing wires that would break the rules. Returns false if incompatible. */
    public boolean connect(Node from, int output, Node to, int input) {
        if (!canConnect(from, output, to, input)) return false;
        boolean exec = from.type.outputs.get(output).isExec();
        if (exec) links.removeIf(l -> l.from() == from && l.output() == output);
        else links.removeIf(l -> l.to() == to && l.input() == input);
        links.add(new Link(nextId++, from, output, to, input));
        touch();
        return true;
    }

    /** Used when loading, to keep saved ids. */
    void addLinkWithId(int id, Node from, int output, Node to, int input) {
        links.add(new Link(id, from, output, to, input));
        nextId = Math.max(nextId, id + 1);
    }

    public Variable variable(String name) {
        for (Variable v : variables) {
            if (v.name.equals(name)) return v;
        }
        return null;
    }
}
