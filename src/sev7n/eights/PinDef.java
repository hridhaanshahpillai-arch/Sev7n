package sev7n.eights;

/**
 * One input or output on a node type.
 *
 * @param name         label shown next to the pin
 * @param type         what flows through it
 * @param defaultValue used when an input isn't connected (null for exec pins and "this object")
 */
public record PinDef(String name, ValueType type, Object defaultValue) {

    public boolean isExec() {
        return type == ValueType.EXEC;
    }
}
