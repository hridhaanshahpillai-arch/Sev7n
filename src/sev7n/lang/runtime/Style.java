package sev7n.lang.runtime;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A CSS-like bag of properties, created with a 'style' block:
 *   style gold { color: #e7ee98; shine: 0.8 }
 *   entity.style = gold
 *
 * Properties are stored in kebab-case ("stroke-width"), but scripts can also
 * reach them in camelCase with dot syntax: gold.strokeWidth = 3
 */
public class Style implements SevenObject {

    private final String name;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public Style(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Object get(String property) {
        return properties.get(toKebabCase(property));
    }

    public void set(String property, Object value) {
        properties.put(toKebabCase(property), value);
    }

    /** True if the property was declared, even as 'none'. */
    public boolean has(String property) {
        return properties.containsKey(toKebabCase(property));
    }

    public Color color(String property, Color fallback) {
        return get(property) instanceof Color c ? c : fallback;
    }

    public double number(String property, double fallback) {
        return get(property) instanceof Double d ? d : fallback;
    }

    @Override
    public String typeName() {
        return "style";
    }

    @Override
    public Object getProperty(String name, int line) {
        return get(name);
    }

    @Override
    public void setProperty(String name, Object value, int line) {
        set(name, value);
    }

    /** strokeWidth -> stroke-width */
    static String toKebabCase(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) out.append('-').append(Character.toLowerCase(c));
            else out.append(c);
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return "style " + name + " " + properties;
    }
}
