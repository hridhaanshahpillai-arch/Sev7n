package sev7n.scene;

import sev7n.math.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny JSON reader/writer for scene files.
 *
 * Objects become Map<String, Object> (keeping key order), arrays become List<Object>,
 * numbers become Double. The typed getters return a fallback when a key is missing,
 * so older scene files keep loading as components gain new settings.
 */
public final class Json {

    private Json() { }

    // ---------------------------------------------------------------- reading

    public static Object parse(String text) {
        Reader reader = new Reader(text);
        Object value = reader.value();
        reader.skipWhitespace();
        if (!reader.atEnd()) throw reader.error("Unexpected text after the JSON value");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("Expected a JSON object");
    }

    public static double number(Map<String, Object> obj, String key, double fallback) {
        return obj.get(key) instanceof Double d ? d : fallback;
    }

    public static String string(Map<String, Object> obj, String key, String fallback) {
        return obj.get(key) instanceof String s ? s : fallback;
    }

    public static boolean bool(Map<String, Object> obj, String key, boolean fallback) {
        return obj.get(key) instanceof Boolean b ? b : fallback;
    }

    public static List<?> list(Map<String, Object> obj, String key) {
        return obj.get(key) instanceof List<?> list ? list : List.of();
    }

    public static Map<String, Object> child(Map<String, Object> obj, String key) {
        return obj.get(key) instanceof Map<?, ?> ? object(obj.get(key)) : Map.of();
    }

    /** Reads [x, y, z] into 'target', leaving it unchanged if the key is missing. */
    public static void vec3(Map<String, Object> obj, String key, Vec3 target) {
        if (obj.get(key) instanceof List<?> l && l.size() == 3
                && l.get(0) instanceof Double x && l.get(1) instanceof Double y && l.get(2) instanceof Double z) {
            target.set(x, y, z);
        }
    }

    /** Reads "#rrggbb" or "#rrggbbaa". */
    public static Color color(Map<String, Object> obj, String key, Color fallback) {
        if (!(obj.get(key) instanceof String s) || !s.startsWith("#")) return fallback;
        try {
            String hex = s.substring(1);
            if (hex.length() == 6) return new Color(Integer.parseInt(hex, 16));
            if (hex.length() == 8) {
                long v = Long.parseLong(hex, 16);
                return new Color((int) (v >> 24) & 255, (int) (v >> 16) & 255, (int) (v >> 8) & 255, (int) v & 255);
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return fallback;
    }

    // ---------------------------------------------------------------- writing helpers

    public static List<Object> vec3(Vec3 v) {
        return List.of(v.x, v.y, v.z);
    }

    public static String color(Color c) {
        return c.getAlpha() == 255
                ? String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue())
                : String.format("#%02x%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    /** Pretty-prints with 2-space indents, so scene files are readable and diff nicely. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.append('\n').toString();
    }

    private static void write(Object value, StringBuilder out, int indent) {
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(s, out);
            case Boolean b -> out.append(b);
            case Number n -> out.append(formatNumber(n.doubleValue()));
            case Map<?, ?> map -> {
                if (map.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append("{\n");
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    pad(out, indent + 1);
                    writeString(String.valueOf(entry.getKey()), out);
                    out.append(": ");
                    write(entry.getValue(), out, indent + 1);
                    out.append(++i < map.size() ? ",\n" : "\n");
                }
                pad(out, indent);
                out.append('}');
            }
            case List<?> list -> {
                boolean simple = list.stream().allMatch(v -> v instanceof Number || v instanceof String);
                if (simple) {
                    // short lists like [1, 2, 3] stay on one line
                    out.append('[');
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) out.append(", ");
                        write(list.get(i), out, indent);
                    }
                    out.append(']');
                    return;
                }
                out.append("[\n");
                for (int i = 0; i < list.size(); i++) {
                    pad(out, indent + 1);
                    write(list.get(i), out, indent + 1);
                    out.append(i + 1 < list.size() ? ",\n" : "\n");
                }
                pad(out, indent);
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Can't write " + value.getClass() + " as JSON");
        }
    }

    private static String formatNumber(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return String.format(Locale.ROOT, "%.6f", d).replaceAll("0+$", "");
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static void pad(StringBuilder out, int indent) {
        out.append("  ".repeat(indent));
    }

    // ---------------------------------------------------------------- parser

    private static final class Reader {
        private final String text;
        private int pos = 0;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            skipWhitespace();
            if (atEnd()) throw error("Unexpected end of file");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f', 'n' -> keyword();
                default -> {
                    if (c == '-' || Character.isDigit(c)) yield number();
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                map.put(key, value());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!atEnd() && peek() != '"') {
                char c = text.charAt(pos++);
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) break;
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw error("Bad \\u escape");
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> out.append(escaped); // \" \\ \/
                }
            }
            expect('"');
            return out.toString();
        }

        private Double number() {
            int start = pos;
            while (!atEnd() && "+-0123456789.eE".indexOf(peek()) >= 0) pos++;
            try {
                return Double.parseDouble(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("Bad number '" + text.substring(start, pos) + "'");
            }
        }

        private Object keyword() {
            for (String word : new String[] {"true", "false", "null"}) {
                if (text.startsWith(word, pos)) {
                    pos += word.length();
                    return switch (word) {
                        case "true" -> Boolean.TRUE;
                        case "false" -> Boolean.FALSE;
                        default -> null;
                    };
                }
            }
            throw error("Unexpected word");
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(peek())) pos++;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        private char peek() {
            return atEnd() ? '\0' : text.charAt(pos);
        }

        private void expect(char c) {
            if (peek() != c) throw error("Expected '" + c + "'");
            pos++;
        }

        IllegalArgumentException error(String message) {
            int line = 1;
            for (int i = 0; i < Math.min(pos, text.length()); i++) {
                if (text.charAt(i) == '\n') line++;
            }
            return new IllegalArgumentException("Scene file line " + line + ": " + message);
        }
    }
}
