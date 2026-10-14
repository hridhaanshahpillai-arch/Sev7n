package sev7n.scene;

import sev7n.math.Vec3;

import java.util.Map;

/**
 * A pill shape (a cylinder with rounded ends) standing up along the entity's Y: the usual
 * collider for characters, because it slides smoothly along walls and up small steps.
 * 'height' is the whole height, ends included.
 */
public final class CapsuleCollider extends Component {

    public double radius = 0.5;
    public double height = 2;
    public final Vec3 offset = new Vec3();
    public boolean trigger = false;

    @Override
    public String type() {
        return "CapsuleCollider";
    }

    @Override
    public String displayName() {
        return "Capsule Collider";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("radius", radius);
        out.put("height", height);
        if (offset.x != 0 || offset.y != 0 || offset.z != 0) out.put("offset", Json.vec3(offset));
        if (trigger) out.put("trigger", true);
    }

    @Override
    public void load(Map<String, Object> in) {
        radius = Json.number(in, "radius", radius);
        height = Json.number(in, "height", height);
        Json.vec3(in, "offset", offset);
        trigger = Json.bool(in, "trigger", trigger);
    }
}
