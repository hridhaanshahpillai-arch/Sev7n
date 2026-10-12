package sev7n.scene;

import java.util.Map;

/**
 * A piece of an entity: how it looks, what it does, how it collides...
 * Entities are just a name and a transform plus a list of components (like Unity).
 *
 * To add a new component type: subclass this, then register it in Components.
 */
public abstract class Component {

    /** Stable id used in scene files, e.g. "MeshRenderer". */
    public abstract String type();

    /** Name shown in the editor, e.g. "Mesh Renderer". */
    public abstract String displayName();

    /** Writes this component's settings for the scene file. */
    public abstract void save(Map<String, Object> out);

    /** Reads settings written by save(). Missing keys keep their defaults. */
    public abstract void load(Map<String, Object> in);
}
