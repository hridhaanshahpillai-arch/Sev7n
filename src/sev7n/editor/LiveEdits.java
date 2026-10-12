package sev7n.editor;

import sev7n.scene.Entity;
import sev7n.scene.Json;
import sev7n.scene.Scene;
import sev7n.scene.SceneSerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Live Mode: while the game plays, changes made in the editor (Inspector, Hierarchy, gizmos,
 * Sprite Editor) are copied onto the edited scene too, so they're still there after Stop.
 *
 * The running scene is captured right after the game updates, and compared just before its
 * next update. Anything different was done in the editor, not by the game, and only those
 * values are copied: if a script is moving the player and you change its color, the saved
 * player gets the new color but stays where you put it.
 */
final class LiveEdits {

    private Map<Long, Map<String, Object>> captured = Map.of();
    private List<Long> capturedOrder = List.of();

    /** Remembers the running scene as the game left it. */
    void capture(Scene running) {
        Map<Long, Map<String, Object>> entities = new HashMap<>();
        List<Long> order = new ArrayList<>();
        for (Entity entity : running.entities()) {
            entities.put(entity.id(), SceneSerializer.entityToMap(entity));
            order.add(entity.id());
        }
        captured = entities;
        capturedOrder = order;
    }

    /** Copies what the editor changed since capture() onto the edited scene. Returns true if anything changed. */
    boolean apply(Scene running, Scene edited) {
        boolean changed = false;
        List<Long> order = new ArrayList<>();
        Set<Long> added = new LinkedHashSet<>();
        for (Entity entity : running.entities()) {
            long id = entity.id();
            order.add(id);
            Map<String, Object> before = captured.get(id);
            Map<String, Object> after = SceneSerializer.entityToMap(entity);
            if (before == null) {
                // made in the editor while playing (things the game spawns appear during its update, not here)
                if (edited.findById(id) == null) {
                    SceneSerializer.loadEntity(edited, after);
                    added.add(id);
                    changed = true;
                }
                continue;
            }
            if (before.equals(after)) continue;
            Entity target = edited.findById(id);
            if (target == null) continue; // something the game spawned: it isn't part of the saved scene
            SceneSerializer.loadInto(target, Json.object(patch(SceneSerializer.entityToMap(target), before, after)));
            changed = true;
        }
        for (long id : capturedOrder) {
            if (running.findById(id) != null) continue;
            Entity gone = edited.findById(id); // deleted in the editor
            if (gone != null) {
                edited.remove(gone);
                changed = true;
            }
        }
        if (!added.isEmpty() || !sameOrder(order)) {
            arrange(edited, order, added);
            changed = true;
        }
        return changed;
    }

    /** Whether the entities that were there before are still in the same order (ignoring new and removed ones). */
    private boolean sameOrder(List<Long> order) {
        List<Long> now = order.stream().filter(captured::containsKey).toList();
        List<Long> before = capturedOrder.stream().filter(order::contains).toList();
        return now.equals(before);
    }

    /** Puts the edited scene's entities in the running order; entities only it has stay where they are. */
    private static void arrange(Scene edited, List<Long> order, Set<Long> added) {
        Entity previous = null;
        for (long id : order) {
            Entity entity = edited.findById(id);
            if (entity == null) continue;
            int index = edited.entities().indexOf(entity);
            int previousIndex = previous == null ? -1 : edited.entities().indexOf(previous);
            if (added.contains(id)) edited.move(entity, previousIndex + (index > previousIndex ? 1 : 0));
            else if (index < previousIndex) edited.move(entity, previousIndex);
            previous = entity;
        }
    }

    // ---------------------------------------------------------------- copying only what changed

    /**
     * Applies the difference between 'before' and 'after' to 'target': unchanged values in
     * 'target' are left alone. Maps and same-length lists (positions, colors) are compared
     * piece by piece; anything else that changed is replaced.
     */
    @SuppressWarnings("unchecked")
    static Object patch(Object target, Object before, Object after) {
        if (Objects.equals(before, after)) return target;
        if (before instanceof Map<?, ?> b && after instanceof Map<?, ?> a && target instanceof Map<?, ?> t) {
            Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) t);
            for (Object key : b.keySet()) {
                if (!a.containsKey(key)) result.remove((String) key);
            }
            for (Map.Entry<?, ?> entry : a.entrySet()) {
                String key = (String) entry.getKey();
                Object value = key.equals("components")
                        ? patchComponents(listOrEmpty(t.get(key)), listOrEmpty(b.get(key)), listOrEmpty(entry.getValue()))
                        : b.containsKey(key) && t.containsKey(key) ? patch(t.get(key), b.get(key), entry.getValue()) : entry.getValue();
                result.put(key, value);
            }
            return result;
        }
        if (before instanceof List<?> b && after instanceof List<?> a && target instanceof List<?> t
                && a.size() == b.size() && t.size() == b.size()) {
            List<Object> result = new ArrayList<>(t);
            for (int i = 0; i < a.size(); i++) result.set(i, patch(t.get(i), b.get(i), a.get(i)));
            return result;
        }
        return after;
    }

    /**
     * Components are matched by type and how many of that type came before (so an entity's
     * second Behaviour matches its second Behaviour): changed ones are patched, ones removed in
     * the editor are dropped, and ones added in the editor are added.
     */
    private static List<Object> patchComponents(List<?> target, List<?> before, List<?> after) {
        Map<String, Object> t = keyed(target);
        Map<String, Object> b = keyed(before);
        Map<String, Object> a = keyed(after);
        List<Object> result = new ArrayList<>();
        t.forEach((key, component) -> {
            if (b.containsKey(key) && !a.containsKey(key)) return; // removed in the editor
            result.add(b.containsKey(key) && a.containsKey(key) ? patch(component, b.get(key), a.get(key)) : component);
        });
        a.forEach((key, component) -> {
            if (!b.containsKey(key) && !t.containsKey(key)) result.add(component); // added in the editor
        });
        return result;
    }

    private static Map<String, Object> keyed(List<?> components) {
        Map<String, Object> keyed = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Object component : components) {
            String type = component instanceof Map<?, ?> map ? String.valueOf(map.get("type")) : "?";
            int n = seen.merge(type, 1, Integer::sum) - 1;
            keyed.put(type + "#" + n, component);
        }
        return keyed;
    }

    private static List<?> listOrEmpty(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }
}
