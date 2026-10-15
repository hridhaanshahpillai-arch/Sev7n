package sev7n.scene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves scenes as readable JSON (.scene files) and loads them back.
 * Also used to copy a scene: Play runs on a copy, so Stop restores the original.
 */
public final class SceneSerializer {

    private SceneSerializer() { }

    public static String toJson(Scene scene) {
        List<Object> entities = new ArrayList<>();
        for (Entity e : scene.entities()) entities.add(entityToMap(e));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", scene.name);
        root.put("entities", entities);
        return Json.write(root);
    }

    public static Scene fromJson(String json) {
        Map<String, Object> root = Json.object(Json.parse(json));
        Scene scene = new Scene(Json.string(root, "name", "Scene"));
        for (Object item : Json.list(root, "entities")) {
            loadEntity(scene, Json.object(item));
        }
        return scene;
    }

    public static Scene copy(Scene scene) {
        return fromJson(toJson(scene));
    }

    public static void save(Scene scene, Path file) throws IOException {
        Files.writeString(file, toJson(scene), StandardCharsets.UTF_8);
    }

    public static Scene load(Path file) throws IOException {
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- entities

    /** An entity as JSON-style data (maps, lists, numbers, strings), exactly as it's saved. */
    public static Map<String, Object> entityToMap(Entity e) {
        Map<String, Object> transform = new LinkedHashMap<>();
        transform.put("position", Json.vec3(e.transform.position));
        transform.put("rotation", Json.vec3(e.transform.rotation));
        transform.put("scale", Json.vec3(e.transform.scale));

        List<Object> components = new ArrayList<>();
        for (Component c : e.components()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", c.type());
            c.save(data);
            components.add(data);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", (double) e.id());
        map.put("name", e.name);
        map.put("active", e.active);
        map.put("tags", new ArrayList<Object>(e.tags));
        if (e.parent >= 0) map.put("parent", (double) e.parent);
        map.put("transform", transform);
        map.put("components", components);
        return map;
    }

    // ---------------------------------------------------------------- families (prefabs with children)

    /** An entity and all its children (and theirs) as data: the entity's map, with its family under "children". */
    public static Map<String, Object> familyToMap(Scene scene, Entity root) {
        Map<String, Object> map = entityToMap(root);
        map.remove("parent"); // a prefab doesn't belong to anything
        List<Object> children = new ArrayList<>();
        for (Entity child : Hierarchy.descendants(scene, root)) children.add(entityToMap(child));
        if (!children.isEmpty()) map.put("children", children);
        return map;
    }

    /**
     * Places a saved family (from familyToMap, or a single entity's map) in a scene with new ids,
     * the root at 'position' and the children where they were around it. Returns the root.
     */
    public static Entity placeFamily(Scene scene, Map<String, Object> data, String name, sev7n.math.Vec3 position) {
        String rootName = scene.uniqueName(name); // worked out before it's in the scene, so it isn't counted against itself
        Entity root = scene.create(rootName);
        loadInto(root, data);
        root.name = rootName;
        root.parent = -1;
        sev7n.math.Vec3 moved = position.minus(root.transform.position);
        root.transform.position.set(position);
        Map<Long, Entity> byOldId = new java.util.HashMap<>();
        byOldId.put((long) Json.number(data, "id", -1), root);
        List<Entity> placed = new ArrayList<>();
        for (Object item : Json.list(data, "children")) {
            Map<String, Object> childData = Json.object(item);
            String childName = scene.uniqueName(Json.string(childData, "name", "Entity"));
            Entity child = scene.create(childName);
            loadInto(child, childData);
            child.name = childName;
            child.transform.position.set(child.transform.position.plus(moved));
            byOldId.put((long) Json.number(childData, "id", -1), child);
            placed.add(child);
        }
        for (Entity child : placed) {
            Entity parent = byOldId.get(child.parent);
            child.parent = parent == null ? root.id() : parent.id();
        }
        return root;
    }

    /** Adds an entity saved by entityToMap to a scene, keeping its id. */
    public static Entity loadEntity(Scene scene, Map<String, Object> map) {
        Entity e = scene.createWithId((long) Json.number(map, "id", 0), Json.string(map, "name", "Entity"));
        loadInto(e, map);
        return e;
    }

    /** Replaces everything about an existing entity (except its id) with saved data. */
    public static void loadInto(Entity e, Map<String, Object> map) {
        e.name = Json.string(map, "name", e.name);
        e.active = Json.bool(map, "active", true);
        e.parent = (long) Json.number(map, "parent", -1);
        e.tags.clear();
        for (Object tag : Json.list(map, "tags")) {
            if (tag instanceof String s) e.tags.add(s);
        }

        Map<String, Object> transform = Json.child(map, "transform");
        Json.vec3(transform, "position", e.transform.position);
        Json.vec3(transform, "rotation", e.transform.rotation);
        Json.vec3(transform, "scale", e.transform.scale);

        for (Component old : List.copyOf(e.components())) e.remove(old);
        for (Object item : Json.list(map, "components")) {
            Map<String, Object> data = Json.object(item);
            Component component = Components.create(Json.string(data, "type", ""));
            if (component == null) continue; // unknown type: skip rather than refuse to load
            component.load(data);
            e.add(component);
        }
    }
}
