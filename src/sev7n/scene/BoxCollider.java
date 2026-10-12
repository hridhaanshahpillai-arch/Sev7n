package sev7n.scene;

import sev7n.math.Vec3;

import java.util.Map;

/**
 * A box used for collisions. When two colliders start touching, every behaviour on
 * both entities gets onCollide(other).
 *
 * With a Rigidbody somewhere, colliders are also solid: rigidbodies can't pass through
 * them. A Trigger collider is never solid (coins, checkpoints, danger zones).
 *
 * Size and offset are relative to the entity's scale, so the default (1, 1, 1) matches a cube.
 */
public final class BoxCollider extends Component {

    public final Vec3 size = new Vec3(1, 1, 1);
    /** Moves the box away from the entity's center, e.g. down to a character's feet. */
    public final Vec3 offset = new Vec3();
    public boolean trigger = false;

    @Override
    public String type() {
        return "BoxCollider";
    }

    @Override
    public String displayName() {
        return "Box Collider";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("size", Json.vec3(size));
        if (offset.x != 0 || offset.y != 0 || offset.z != 0) out.put("offset", Json.vec3(offset));
        if (trigger) out.put("trigger", true);
    }

    @Override
    public void load(Map<String, Object> in) {
        Json.vec3(in, "size", size);
        Json.vec3(in, "offset", offset);
        trigger = Json.bool(in, "trigger", trigger);
    }
}
