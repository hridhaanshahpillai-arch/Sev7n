package sev7n.scene;

import sev7n.math.Vec3;

import java.util.Map;

/**
 * Lets an animated character go limp and fall like a real body (a "ragdoll"): its bones
 * tumble under gravity, bump into walls and floors, and keep the speed it had.
 * Scripts switch it on with entity.ragdoll() and back with entity.getUp().
 *
 * How it fits together:
 *   - the renderer picks the main bones of the model (hips, spine, head, arms, legs), fills
 *     'skeleton' once, and writes where those bones are into 'pose' every frame
 *   - when the ragdoll starts, the physics turns them into balls joined by sticks and
 *     moves those, writing the results into 'points'
 *   - the renderer then turns the model's bones to follow 'points'
 */
public final class Ragdoll extends Component {

    /** 0 = floppy, 1 = stiff (joints try to keep the pose it fell in). */
    public double stiffness = 0.2;
    /** How quickly sliding along the ground stops: 0 = ice, 1 = sticky. */
    public double friction = 0.6;
    /** 0 = lands with a thud, 1 = bounces. */
    public double bounciness = 0.1;
    /** Seconds to blend from lying limp back into the animation after getting up. */
    public double blendOut = 0.4;

    // while running (not saved)

    /** True while it's limp. */
    public boolean active = false;
    /** The bones it simulates (from the renderer), or null before the model was first drawn. */
    public Skeleton skeleton;
    /** World positions (x, y, z per bone) of the animated bones, written every frame while not limp. */
    public double[] pose;
    /** Where the entity was when 'pose' was written. */
    public final Vec3 poseOrigin = new Vec3();
    /** Which way the body faced when 'pose' was written (so knees bend forward and elbows back). */
    public final Vec3 poseForward = new Vec3(0, 0, -1);
    /** World positions of the simulated bones while limp. */
    public double[] points;
    /** Set by the physics while limp; the renderer reads it to know it's showing a new fall. */
    public int fall = 0;
    /** A push (velocity, units per second) to start the fall with, used once. */
    public double pushX, pushY, pushZ;

    /**
     * The simulated bones: each has a parent bone (-1 for the hips), a ball size at its joint,
     * how thick the bone from its parent to it is, which solid piece
     * it belongs to (0 = the hips with the tops of the legs, 1 = the chest with the shoulders,
     * -1 = none: arms, legs, neck, head), and whether it's part of the trunk (the two pieces and
     * the spine between them, which only bends a little).
     */
    public record Skeleton(String[] names, int[] parent, double[] radius, double[] thickness, int[] group, boolean[] trunk) {
        public int size() {
            return names.length;
        }
    }

    /** True if this entity is lying limp right now (its normal collider and Rigidbody are switched off). */
    public static boolean limp(Entity entity) {
        Ragdoll ragdoll = entity.get(Ragdoll.class);
        return ragdoll != null && ragdoll.active;
    }

    @Override
    public String type() {
        return "Ragdoll";
    }

    @Override
    public String displayName() {
        return "Ragdoll";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("stiffness", stiffness);
        out.put("friction", friction);
        out.put("bounciness", bounciness);
        out.put("blendOut", blendOut);
    }

    @Override
    public void load(Map<String, Object> in) {
        stiffness = Json.number(in, "stiffness", stiffness);
        friction = Json.number(in, "friction", friction);
        bounciness = Json.number(in, "bounciness", bounciness);
        blendOut = Json.number(in, "blendOut", blendOut);
    }
}
