package sev7n.game;

import sev7n.scene.BoxCollider;
import sev7n.scene.Entity;
import sev7n.scene.Scene;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds colliders that started touching this frame (for onCollide).
 * Boxes resting against each other count as touching, so landing on a floor is reported once.
 */
final class Collisions {

    private static final double TOUCH_MARGIN = 0.002;

    record Contact(Entity a, Entity b) { }

    private record Collider(Entity entity, Box box) { }

    private final Set<String> touching = new HashSet<>();

    /** Contacts that are new since last frame. Contacts that continue aren't repeated. */
    List<Contact> newContacts(Scene scene) {
        List<Collider> colliders = new ArrayList<>();
        for (Entity entity : scene.entities()) {
            BoxCollider collider = entity.get(BoxCollider.class);
            if (entity.active && collider != null) colliders.add(new Collider(entity, Box.of(entity, collider)));
        }

        Set<String> nowTouching = new HashSet<>();
        List<Contact> started = new ArrayList<>();
        for (int i = 0; i < colliders.size(); i++) {
            for (int j = i + 1; j < colliders.size(); j++) {
                Collider a = colliders.get(i);
                Collider b = colliders.get(j);
                if (!a.box().touches(b.box(), TOUCH_MARGIN)) continue;
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
