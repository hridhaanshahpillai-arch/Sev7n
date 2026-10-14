package sev7n.scene;

import sev7n.math.Vec3;

import java.util.Map;

/**
 * A ball used for collisions: balls, bullets, round pickups, planets. Works like a Box
 * Collider (solid, or a Trigger), but things roll and bounce off it the round way.
 * The radius grows with the entity's biggest scale, so it stays a ball.
 */
public final class SphereCollider extends Component {

    public double radius = 0.5;
    public final Vec3 offset = new Vec3();
    public boolean trigger = false;

    @Override
    public String type() {
        return "SphereCollider";
    }

    @Override
    public String displayName() {
        return "Sphere Collider";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("radius", radius);
        if (offset.x != 0 || offset.y != 0 || offset.z != 0) out.put("offset", Json.vec3(offset));
        if (trigger) out.put("trigger", true);
    }

    @Override
    public void load(Map<String, Object> in) {
        radius = Json.number(in, "radius", radius);
        Json.vec3(in, "offset", offset);
        trigger = Json.bool(in, "trigger", trigger);
    }
}
