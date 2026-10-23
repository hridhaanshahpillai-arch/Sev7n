package sev7n.scene;

import java.util.Map;

/**
 * Put it on the camera: the camera follows a character from behind and orbits around it
 * with the mouse (or a gamepad's right stick), and moves in closer when a wall is in the way.
 * Scripts move the character relative to where the camera looks with cameraForward() / cameraRight().
 */
public final class ThirdPersonCamera extends Component {

    /** The name of the object to follow, e.g. "Player". */
    public String target = "Player";
    /** How far behind the target the camera stays. */
    public double distance = 5;
    /** The point the camera looks at, this high above the target's feet. */
    public double height = 1.6;
    /** Mouse speed (degrees per pixel). The gamepad's right stick turns at 120 degrees a second. */
    public double sensitivity = 0.15;
    /** How quickly the camera catches up (higher = snappier, 0 = instantly). */
    public double smoothing = 12;
    /** Hide and hold the cursor while playing, so the mouse orbits the camera (Esc-style menus can call lockMouse(false)). */
    public boolean lockMouse = true;
    /** Move in closer instead of going through walls. */
    public boolean avoidWalls = true;
    /** Lowest and highest the camera can look from (degrees; positive = from above). */
    public double minPitch = -30;
    public double maxPitch = 70;

    // while playing (not saved)
    public transient double yaw;
    public transient double pitch = 15;
    public transient boolean started;
    public transient double shownDistance = -1;

    @Override
    public String type() {
        return "ThirdPersonCamera";
    }

    @Override
    public String displayName() {
        return "Third Person Camera";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("target", target);
        out.put("distance", distance);
        out.put("height", height);
        out.put("sensitivity", sensitivity);
        out.put("smoothing", smoothing);
        out.put("lockMouse", lockMouse);
        out.put("avoidWalls", avoidWalls);
        out.put("minPitch", minPitch);
        out.put("maxPitch", maxPitch);
    }

    @Override
    public void load(Map<String, Object> in) {
        target = Json.string(in, "target", target);
        distance = Math.max(0.5, Json.number(in, "distance", distance));
        height = Json.number(in, "height", height);
        sensitivity = Math.max(0, Json.number(in, "sensitivity", sensitivity));
        smoothing = Math.max(0, Json.number(in, "smoothing", smoothing));
        lockMouse = Json.bool(in, "lockMouse", lockMouse);
        avoidWalls = Json.bool(in, "avoidWalls", avoidWalls);
        minPitch = Math.max(-89, Json.number(in, "minPitch", minPitch));
        maxPitch = Math.min(89, Math.max(minPitch, Json.number(in, "maxPitch", maxPitch)));
    }
}
