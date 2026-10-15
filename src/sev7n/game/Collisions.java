package sev7n.game;

import sev7n.scene.Entity;
import sev7n.scene.Scene;

import java.util.List;

/**
 * Finds colliders (boxes, spheres, capsules) that started touching this frame (for onCollide)
 * and ones that stopped (for onCollideEnd). Things resting against each other count as
 * touching, so landing on a floor is reported once.
 */
final class Collisions {

    private static final double TOUCH_MARGIN = 0.002;

    record Contact(Entity a, Entity b) { }

    /** What changed this frame: pairs that started touching, and pairs that stopped. */
    record Changes(List<Contact> started, List<Contact> ended) { }

    private final java.util.Map<String, Contact> touching = new java.util.HashMap<>();

    /** Contacts that are new since last frame. Contacts that continue aren't repeated. */
    List<Contact> newContacts(Scene scene) {
        return update(scene).started();
    }

    /** Works out what's touching now, compared with last frame. */
    Changes update(Scene scene) {
        List<WorldCollider> colliders = new java.util.ArrayList<>(WorldCollider.all(scene, true));
        // sorted left to right, each only needs checking against the ones that start before it ends ("sweep and prune")
        colliders.sort(java.util.Comparator.comparingDouble(w -> w.bounds().minX()));
        java.util.Map<String, Contact> nowTouching = new java.util.HashMap<>();
        List<Contact> started = new java.util.ArrayList<>();
        for (int i = 0; i < colliders.size(); i++) {
            WorldCollider a = colliders.get(i);
            double reach = a.bounds().maxX() + TOUCH_MARGIN;
            for (int j = i + 1; j < colliders.size() && colliders.get(j).bounds().minX() <= reach; j++) {
                WorldCollider b = colliders.get(j);
                if (!a.touches(b, TOUCH_MARGIN)) continue;
                String key = key(a.entity(), b.entity());
                Contact contact = new Contact(a.entity(), b.entity());
                nowTouching.put(key, contact);
                if (!touching.containsKey(key)) started.add(contact);
            }
        }
        List<Contact> ended = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Contact> was : touching.entrySet()) {
            if (!nowTouching.containsKey(was.getKey())) ended.add(was.getValue());
        }
        touching.clear();
        touching.putAll(nowTouching);
        return new Changes(started, ended);
    }

    void forget(Entity entity) {
        touching.keySet().removeIf(key -> key.startsWith(entity.id() + ":") || key.endsWith(":" + entity.id()));
    }

    private static String key(Entity a, Entity b) {
        return Math.min(a.id(), b.id()) + ":" + Math.max(a.id(), b.id());
    }
}
