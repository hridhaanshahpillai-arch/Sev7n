package sev7n.scene;

import sev7n.math.Vec3;

import java.util.Map;

/**
 * Makes an entity move by physics: gravity pulls it, and its Box Collider stops it
 * at other colliders (and solid tilemaps) instead of passing through.
 *
 * Colliders without a Rigidbody are the world: walls, floors and platforms that never move.
 * Colliders marked Trigger are never solid; they only report onCollide.
 */
public final class Rigidbody extends Component {

    /** 1 = normal gravity, 0 = floats (top-down games, spaceships), 2 = falls twice as hard. */
    public double gravityScale = 1;
    /** 0 = stops dead when it lands, 1 = bounces back up just as fast. */
    public double bounciness = 0;
    /** Slows it down over time, like air resistance. 0 = none. */
    public double drag = 0;
    /** How fast it's moving (units per second). The value here is what it starts with. */
    public final Vec3 velocity = new Vec3();

    /** True while it's standing on something (set by the physics every frame). */
    public boolean grounded = false;

    @Override
    public String type() {
        return "Rigidbody";
    }

    @Override
    public String displayName() {
        return "Rigidbody";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("gravityScale", gravityScale);
        out.put("bounciness", bounciness);
        out.put("drag", drag);
        out.put("velocity", Json.vec3(velocity));
    }

    @Override
    public void load(Map<String, Object> in) {
        gravityScale = Json.number(in, "gravityScale", gravityScale);
        bounciness = Json.number(in, "bounciness", bounciness);
        drag = Json.number(in, "drag", drag);
        Json.vec3(in, "velocity", velocity);
    }
}
