package sev7n.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Anything in a scene: a player, a coin, the camera, the sun. */
public final class Entity {

    private final long id;
    public String name;
    public boolean active = true;
    public final List<String> tags = new ArrayList<>();
    public final Transform transform = new Transform();
    private final List<Component> components = new ArrayList<>();

    Entity(long id, String name) {
        this.id = id;
        this.name = name;
    }

    /** Unique within its scene; stays the same when the scene is saved and loaded. */
    public long id() {
        return id;
    }

    public List<Component> components() {
        return Collections.unmodifiableList(components);
    }

    public <T extends Component> T add(T component) {
        components.add(component);
        return component;
    }

    public void remove(Component component) {
        components.remove(component);
    }

    /** The first component of this type, or null. */
    public <T extends Component> T get(Class<T> type) {
        for (Component c : components) {
            if (type.isInstance(c)) return type.cast(c);
        }
        return null;
    }

    public <T extends Component> List<T> getAll(Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component c : components) {
            if (type.isInstance(c)) found.add(type.cast(c));
        }
        return found;
    }

    public boolean has(Class<? extends Component> type) {
        return get(type) != null;
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    @Override
    public String toString() {
        return name;
    }
}
