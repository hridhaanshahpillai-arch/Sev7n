package sev7n.eights;

/** A graph variable: becomes a field of the behaviour, e.g. 'var health = 100'. */
public final class Variable {

    public String name;
    public ValueType type;
    public Object value;

    public Variable(String name, ValueType type, Object value) {
        this.name = name;
        this.type = type;
        this.value = value;
    }
}
