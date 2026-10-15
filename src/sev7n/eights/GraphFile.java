package sev7n.eights;

import sev7n.math.Vec3;
import sev7n.scene.Json;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Saves Eights graphs as readable JSON (.8s files) and loads them back. */
public final class GraphFile {

    public static final String EXTENSION = ".8s";

    private GraphFile() { }

    public static void save(Graph graph, Path file) throws IOException {
        Files.writeString(file, toJson(graph), StandardCharsets.UTF_8);
    }

    public static Graph load(Path file) throws IOException {
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** A new graph with the two events most behaviours need. */
    public static Graph starter() {
        Graph graph = new Graph();
        graph.add(NodeLibrary.get(NodeLibrary.ON_START), 40, 40);
        graph.add(NodeLibrary.get(NodeLibrary.ON_UPDATE), 40, 220);
        return graph;
    }

    // ---------------------------------------------------------------- writing

    public static String toJson(Graph graph) {
        List<Object> variables = new ArrayList<>();
        for (Variable v : graph.variables) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", v.name);
            map.put("type", v.type.name());
            map.put("value", writeValue(v.value));
            variables.add(map);
        }

        List<Object> nodes = new ArrayList<>();
        for (Node n : graph.nodes()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", (double) n.id);
            map.put("type", n.type.id);
            map.put("x", (double) n.x);
            map.put("y", (double) n.y);
            if (n.type.usesVariable) map.put("variable", n.variable);
            Map<String, Object> values = new LinkedHashMap<>();
            n.values().forEach((input, value) -> values.put(String.valueOf(input), writeValue(value)));
            if (!values.isEmpty()) map.put("values", values);
            nodes.add(map);
        }

        List<Object> links = new ArrayList<>();
        for (Link l : graph.links()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", (double) l.id());
            map.put("from", List.of((double) l.from().id, (double) l.output()));
            map.put("to", List.of((double) l.to().id, (double) l.input()));
            links.add(map);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("eights", 1.0); // format version
        root.put("variables", variables);
        root.put("nodes", nodes);
        root.put("links", links);
        return Json.write(root);
    }

    private static Object writeValue(Object value) {
        return switch (value) {
            case Vec3 v -> Json.vec3(v);
            case Color c -> Json.color(c);
            case List<?> list -> new ArrayList<>(); // lists always start empty
            case null -> null;
            default -> value; // numbers, strings, booleans
        };
    }

    // ---------------------------------------------------------------- reading

    public static Graph fromJson(String json) {
        Map<String, Object> root = Json.object(Json.parse(json));
        Graph graph = new Graph();

        for (Object item : Json.list(root, "variables")) {
            Map<String, Object> map = Json.object(item);
            ValueType type = parseType(Json.string(map, "type", "NUMBER"));
            graph.variables.add(new Variable(Json.string(map, "name", "variable"), type, readValue(map.get("value"), type)));
        }

        for (Object item : Json.list(root, "nodes")) {
            Map<String, Object> map = Json.object(item);
            NodeType type = NodeLibrary.get(Json.string(map, "type", ""));
            if (type == null) continue; // a node type this version doesn't know
            Node node = graph.addWithId((int) Json.number(map, "id", 0), type,
                    (float) Json.number(map, "x", 0), (float) Json.number(map, "y", 0));
            node.variable = Json.string(map, "variable", "");
            Json.child(map, "values").forEach((key, value) -> {
                int input = Integer.parseInt(key);
                if (input < type.inputs.size()) node.setValue(input, readValue(value, type.inputs.get(input).type()));
            });
        }

        for (Object item : Json.list(root, "links")) {
            Map<String, Object> map = Json.object(item);
            List<?> from = Json.list(map, "from");
            List<?> to = Json.list(map, "to");
            if (from.size() != 2 || to.size() != 2) continue;
            Node source = graph.node(((Double) from.get(0)).intValue());
            Node target = graph.node(((Double) to.get(0)).intValue());
            int output = ((Double) from.get(1)).intValue();
            int input = ((Double) to.get(1)).intValue();
            if (source == null || target == null) continue;
            if (output >= source.type.outputs.size() || input >= target.type.inputs.size()) continue;
            graph.addLinkWithId((int) Json.number(map, "id", 0), source, output, target, input);
        }
        return graph;
    }

    private static ValueType parseType(String name) {
        try {
            return ValueType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ValueType.ANY;
        }
    }

    private static Object readValue(Object raw, ValueType type) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("v", raw);
        return switch (type) {
            case VEC3 -> {
                Vec3 v = new Vec3();
                Json.vec3(wrapper, "v", v);
                yield v;
            }
            case COLOR -> Json.color(wrapper, "v", new Color(0xBF9EEE));
            case NUMBER -> raw instanceof Double d ? d : 0.0;
            case BOOL -> raw instanceof Boolean b ? b : false;
            case STRING -> raw instanceof String s ? s : "";
            case LIST -> new ArrayList<>();
            case ENTITY -> null;
            default -> raw; // ANY keeps whatever was saved (number or text)
        };
    }
}
