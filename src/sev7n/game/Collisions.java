package sev7n.game;

import sev7n.scene.Entity;
import sev7n.scene.Scene;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds colliders (boxes, spheres, capsules) that started touching this frame (for onCollide).
 * Things resting against each other count as touching, so landing on a floor is reported once.
 */
final class Collisions {

    private static final double TOUCH_MARGIN = 0.002;

    record Contact(Entity a, Entity b) { }

    private final Set<String> touching = new HashSet<>();

    /** Contacts that are new since last frame. Contacts that continue aren't repeated. */
    List<Contact> newContacts(Scene scene) {
        List<WorldCollider> colliders = new java.util.ArrayList<>(WorldCollider.all(scene, true));
        // sorted left to right, each only needs checking against the ones that start before it ends ("sweep and prune")
        colliders.sort(java.util.Comparator.comparingDouble(w -> w.bounds().minX()));
        Set<String> nowTouching = new HashSet<>();
        List<Contact> started = new java.util.ArrayList<>();
        for (int i = 0; i < colliders.size(); i++) {
            WorldCollider a = colliders.get(i);
            double reach = a.bounds().maxX() + TOUCH_MARGIN;
            for (int j = i + 1; j < colliders.size() && colliders.get(j).bounds().minX() <= reach; j++) {
                WorldCollider b = colliders.get(j);
                if (!a.touches(b, TOUCH_MARGIN)) continue;
                String key = key(a.entity(), b.entity());
                nowTouching.add(key);
                if (!touching.contains(key)) started.add(new Contact(a.entity(), b.entity()));
            }
        }
        touching.clear();
        touching.addAll(nowTouching);
        return started;
    }

    void forget(Entity entity) {
        touching.removeIf(key -> key.startsWith(entity.id() + ":") || key.endsWith(":" + entity.id()));
    }

    private static String key(Entity a, Entity b) {
        return Math.min(a.id(), b.id()) + ":" + Math.max(a.id(), b.id());
    }
}
