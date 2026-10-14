package sev7n.scene;

import sev7n.math.Vec3;

import java.util.Map;

/**
 * Makes an entity move by physics: gravity pulls it, forces push it, and its collider (box,
 * sphere or capsule) stops it at other colliders (and solid tilemaps) instead of passing through.
 * Rigidbodies push each other: the heavier one wins.
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
    /** How heavy it is, for pushing other rigidbodies and for forces. 1 = normal. */
    public double mass = 1;
    /** Slows sliding along whatever it touches: 0 = ice, 1 = rubber. */
    public double friction = 0.4;
    /** Moved only by scripts: gravity and collisions don't move it, but it still pushes others (moving platforms). */
    public boolean kinematic = false;
    /** Capsules walk up steps and ledges up to this high (stairs, curbs, chairs). 0 = never. */
    public double stepHeight = 0.35;
    /** Stops movement along an axis, e.g. lock Z for a 2D game. */
    public boolean lockX = false;
    public boolean lockY = false;
    public boolean lockZ = false;
    /** How fast it's moving (units per second). The value here is what it starts with. */
    public final Vec3 velocity = new Vec3();

    /** True while it's standing on something (set by the physics every frame). */
    public boolean grounded = false;
    /** Forces added by scripts since the last physics step (not saved). */
    public final Vec3 force = new Vec3();

    public double inverseMass() {
        return kinematic || mass <= 0 ? 0 : 1 / mass;
    }

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
        out.put("mass", mass);
        out.put("friction", friction);
        out.put("stepHeight", stepHeight);
        if (kinematic) out.put("kinematic", true);
        if (lockX) out.put("lockX", true);
        if (lockY) out.put("lockY", true);
        if (lockZ) out.put("lockZ", true);
        out.put("velocity", Json.vec3(velocity));
    }

    @Override
    public void load(Map<String, Object> in) {
        gravityScale = Json.number(in, "gravityScale", gravityScale);
        bounciness = Json.number(in, "bounciness", bounciness);
        drag = Json.number(in, "drag", drag);
        mass = Math.max(0.001, Json.number(in, "mass", 1));
        friction = Json.number(in, "friction", 0); // scenes from before friction existed keep sliding the way they did
        stepHeight = Json.number(in, "stepHeight", stepHeight);
        kinematic = Json.bool(in, "kinematic", false);
        lockX = Json.bool(in, "lockX", false);
        lockY = Json.bool(in, "lockY", false);
        lockZ = Json.bool(in, "lockZ", false);
        Json.vec3(in, "velocity", velocity);
    }
}
