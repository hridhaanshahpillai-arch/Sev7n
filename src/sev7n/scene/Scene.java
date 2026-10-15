package sev7n.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A level or screen of a game: an ordered list of entities. */
public final class Scene {

    public String name;
    /** Text, bars and pictures drawn over the game this frame (not saved). */
    public final Hud hud = new Hud();
    private final List<Entity> entities = new ArrayList<>();
    private long nextId = 1;

    public Scene(String name) {
        this.name = name;
    }

    public List<Entity> entities() {
        return Collections.unmodifiableList(entities);
    }

    public Entity create(String name) {
        Entity entity = new Entity(nextId++, name);
        entities.add(entity);
        return entity;
    }

    /** Used when loading a scene file, to keep each entity's saved id. */
    Entity createWithId(long id, String name) {
        Entity entity = new Entity(id, name);
        entities.add(entity);
        nextId = Math.max(nextId, id + 1);
        return entity;
    }

    public void remove(Entity entity) {
        entities.remove(entity);
    }

    public boolean contains(Entity entity) {
        return entities.contains(entity);
    }

    /** Moves an entity to a new position in the list (the Hierarchy order). */
    public void move(Entity entity, int newIndex) {
        if (!entities.remove(entity)) return;
        entities.add(Math.max(0, Math.min(newIndex, entities.size())), entity);
    }

    /** Copies an entity and its components, placing the copy right after the original. */
    public Entity duplicate(Entity original) {
        Entity copy = create(uniqueName(original.name));
        copy.active = original.active;
        copy.tags.addAll(original.tags);
        copy.transform.copyFrom(original.transform);
        copy.parent = original.parent; // a sibling of the original
        for (Component c : original.components()) copy.add(Components.copy(c));
        move(copy, entities.indexOf(original) + 1);
        return copy;
    }

    /** Copies an entity and all its children (and theirs), keeping the family tree; returns the copy of 'original'. */
    public Entity duplicateWithChildren(Entity original) {
        List<Entity> family = new ArrayList<>(List.of(original));
        family.addAll(Hierarchy.descendants(this, original));
        java.util.Map<Long, Entity> copies = new java.util.HashMap<>();
        Entity after = family.get(family.size() - 1);
        for (Entity e : family) {
            Entity copy = duplicate(e);
            move(copy, entities.indexOf(after) + 1);
            after = copy;
            copies.put(e.id(), copy);
        }
        for (Entity e : family) {
            Entity copy = copies.get(e.id());
            if (copies.containsKey(e.parent)) copy.parent = copies.get(e.parent).id();
        }
        return copies.get(original.id());
    }

    public Entity find(String name) {
        for (Entity e : entities) {
            if (e.name.equals(name)) return e;
        }
        return null;
    }

    public Entity findById(long id) {
        for (Entity e : entities) {
            if (e.id() == id) return e;
        }
        return null;
    }

    /** The first active entity with a camera, or null. */
    public Entity mainCamera() {
        for (Entity e : entities) {
            if (e.active && e.has(Camera.class)) return e;
        }
        return null;
    }

    /** "Cube" -> "Cube (1)" -> "Cube (2)", so new entities get distinct names. */
    public String uniqueName(String base) {
        String root = base.replaceAll(" \\(\\d+\\)$", "");
        if (find(root) == null) return root;
        for (int i = 1; ; i++) {
            String candidate = root + " (" + i + ")";
            if (find(candidate) == null) return candidate;
        }
    }
}
