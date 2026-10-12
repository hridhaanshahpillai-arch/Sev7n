package sev7n.eights;

import sev7n.math.Vec3;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns an Eights graph into Seven code, so graphs run on the same engine as scripts:
 *
 *   behaviour Name {
 *       var health = 100            <- graph variables
 *       func start()  { ... }       <- chains from On Start
 *       func update(dt) { ... }     <- chains from On Update and key events
 *       func onCollide(other) { }   <- chains from On Collide
 *   }
 *
 * It also records which node wrote each line, so a script error can point at the node.
 */
public final class Compiler {

    private static final Set<String> RESERVED = Set.of("var", "func", "return", "if", "else", "while", "for", "in",
            "break", "continue", "true", "false", "none", "and", "or", "not", "style", "behaviour",
            "entity", "transform", "other", "dt");

    /** @param lineToNode for each line of 'source' written by a node, that node's id */
    public record Result(String source, Map<Integer, Integer> lineToNode) {
        /** The node behind a line of generated code, or -1. */
        public int nodeAtLine(int line) {
            return lineToNode.getOrDefault(line, -1);
        }
    }

    private Compiler() { }

    public static Result compile(Graph graph, String behaviourName) {
        Context code = new Context(graph);
        code.line("// Made with Eights from " + behaviourName + GraphFile.EXTENSION + ". Edit the graph, not this text.", null);
        code.line("behaviour " + behaviourName + " {", null);
        code.indent();

        for (Variable v : graph.variables) {
            code.line("var " + safeName(v.name) + " = " + literal(v.value, v.type), null);
        }
        code.line("var _dt = 0", null);

        code.line("", null);
        code.line("func start() {", null);
        code.indent();
        for (Node event : events(graph, NodeLibrary.ON_START)) code.follow(event, 0);
        code.dedent();
        code.line("}", null);

        code.line("", null);
        code.line("func update(dt) {", null);
        code.indent();
        code.line("_dt = dt", null);
        for (Node event : events(graph, NodeLibrary.ON_UPDATE)) code.follow(event, 0);
        for (Node event : events(graph, NodeLibrary.KEY_PRESSED)) code.block("if keyPressed(" + code.input(event, 0) + ") {", event, 0);
        for (Node event : events(graph, NodeLibrary.KEY_HELD)) code.block("if key(" + code.input(event, 0) + ") {", event, 0);
        code.dedent();
        code.line("}", null);

        if (!events(graph, NodeLibrary.ON_COLLIDE).isEmpty()) {
            code.line("", null);
            code.line("func onCollide(other) {", null);
            code.indent();
            for (Node event : events(graph, NodeLibrary.ON_COLLIDE)) code.follow(event, 0);
            code.dedent();
            code.line("}", null);
        }

        code.dedent();
        code.line("}", null);
        return new Result(code.out.toString(), Map.copyOf(code.lines));
    }

    private static List<Node> events(Graph graph, String typeId) {
        return graph.nodes().stream().filter(n -> n.type.id.equals(typeId)).toList();
    }

    // ---------------------------------------------------------------- values as Seven code

    /** A value written as Seven source, e.g. 5, "hi", vec3(0, 1, 0), #bf9eee. */
    static String literal(Object value, ValueType type) {
        return switch (value) {
            case null -> type == ValueType.ENTITY ? "entity" : "none";
            case Double d -> number(d);
            case Boolean b -> b.toString();
            case String s -> quote(s);
            case Vec3 v -> "vec3(" + number(v.x) + ", " + number(v.y) + ", " + number(v.z) + ")";
            case Color c -> String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
            default -> "none";
        };
    }

    private static String number(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return String.format(Locale.ROOT, "%.4f", d).replaceAll("0+$", "");
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    /** Turns any variable name into a valid Seven identifier that can't clash with built-in names. */
    static String safeName(String name) {
        String cleaned = name.trim().replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0)) || RESERVED.contains(cleaned)) cleaned = "var_" + cleaned;
        return cleaned;
    }

    // ---------------------------------------------------------------- code writer

    /** What node types use to write their code. */
    public static final class Context {
        private final Graph graph;
        private final StringBuilder out = new StringBuilder();
        private final Map<Integer, Integer> lines = new HashMap<>();
        private final Deque<Node> chain = new ArrayDeque<>();
        private int indent = 0;
        private int lineCount = 0;

        private Context(Graph graph) {
            this.graph = graph;
        }

        /** Writes one line of Seven, remembering which node it came from. */
        public void line(String text, Node source) {
            lineCount++;
            out.append("    ".repeat(indent)).append(text).append('\n');
            if (source != null) lines.put(lineCount, source.id);
        }

        public void indent() {
            indent++;
        }

        public void dedent() {
            indent--;
        }

        /** Writes "header { <chain from exec output> }". */
        public void block(String header, Node node, int execOutput) {
            line(header, node);
            indent();
            follow(node, execOutput);
            dedent();
            line("}", node);
        }

        /** True if something is wired to this exec output. */
        public boolean has(Node node, int execOutput) {
            return graph.linkFrom(node, execOutput) != null;
        }

        /** Writes the code for whatever this exec output leads to. */
        public void follow(Node node, int execOutput) {
            Link link = graph.linkFrom(node, execOutput);
            if (link == null) return;
            Node next = link.to();
            if (chain.contains(next)) {
                line("// (the graph loops back here, so it stops)", next);
                return;
            }
            if (next.type.statement == null) return;
            chain.push(next);
            try {
                next.type.statement.emit(this, next);
            } finally {
                chain.pop();
            }
        }

        /** The Seven expression for a data input: the wired value, or the typed-in one. */
        public String input(Node node, int input) {
            Link link = graph.linkTo(node, input);
            if (link != null) return output(link.from(), link.output());
            return literal(node.value(input), node.type.inputs.get(input).type());
        }

        /** The Seven expression for a node's data output. */
        public String output(Node node, int output) {
            return node.type.expression == null ? "none" : node.type.expression.of(this, node, output);
        }

        /** A per-node name for remembered results, e.g. what Spawn created. */
        public String temp(Node node) {
            return "_n" + node.id;
        }

        /** The variable a Get / Set node uses, as a valid name, or null if none is chosen. */
        public String variable(Node node) {
            if (node.variable.isEmpty() || graph.variable(node.variable) == null) return null;
            return safeName(node.variable);
        }
    }
}
