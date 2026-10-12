package sev7n.lang.runtime;

import sev7n.lang.SevenError;

/**
 * Anything with properties that scripts can reach with a dot:
 *   player.fill, transform.position, other.hasTag("coin")
 *
 * Methods are just properties whose value is a function.
 * The engine implements this for entities and transforms.
 */
public interface SevenObject {

    /** Shown in error messages, e.g. "entity". */
    String typeName();

    Object getProperty(String name, int line);

    default void setProperty(String name, Object value, int line) {
        throw new SevenError("'" + name + "' on a " + typeName() + " can't be changed", line);
    }

    default SevenError noSuchProperty(String name, int line) {
        return new SevenError("A " + typeName() + " has no property '" + name + "'", line);
    }
}
