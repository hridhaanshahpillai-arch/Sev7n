package sev7n.scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Every component type, by its scene-file id. Also drives the editor's "Add Component" menu. */
public final class Components {

    private static final Map<String, Supplier<Component>> TYPES = new LinkedHashMap<>();

    static {
        register("MeshRenderer", MeshRenderer::new);
        register("Animator", Animator::new);
        register("Ragdoll", Ragdoll::new);
        register("SpriteRenderer", SpriteRenderer::new);
        register("SpriteAnimator", SpriteAnimator::new);
        register("Tilemap", Tilemap::new);
        register("Camera", Camera::new);
        register("Light", Light::new);
        register("BoxCollider", BoxCollider::new);
        register("SphereCollider", SphereCollider::new);
        register("CapsuleCollider", CapsuleCollider::new);
        register("Rigidbody", Rigidbody::new);
        register("ParticleEmitter", ParticleEmitter::new);
        register("Npc", Npc::new);
        register("Behaviour", Behaviour::new);
    }

    private Components() { }

    private static void register(String type, Supplier<Component> factory) {
        TYPES.put(type, factory);
    }

    public static Set<String> types() {
        return TYPES.keySet();
    }

    /** Returns null for unknown types (e.g. from a newer scene file). */
    public static Component create(String type) {
        Supplier<Component> factory = TYPES.get(type);
        return factory == null ? null : factory.get();
    }

    public static String displayName(String type) {
        return create(type).displayName();
    }

    public static Component copy(Component original) {
        Map<String, Object> data = new LinkedHashMap<>();
        original.save(data);
        Component copy = create(original.type());
        copy.load(data);
        return copy;
    }
}
