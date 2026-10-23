package sev7n.scene;

import java.awt.Color;
import java.util.Map;

/**
 * The game is seen through the first active entity with a Camera.
 *
 * A perspective camera sees in 3D (far things look smaller). An orthographic camera
 * is flat: perfect for 2D games, and a 2D game can switch to perspective at any time.
 */
public final class Camera extends Component {

    public boolean orthographic = false;
    public double fieldOfView = 60;   // perspective: vertical, in degrees
    public double size = 5;           // orthographic: half the view's height, in world units
    public double near = 0.1;
    public double far = 500;
    public Color background = new Color(0x282A36);
    /** 3D: a sky behind everything (a gradient from the top down to the horizon, the ground below, and the sun) instead of the plain background. */
    public boolean sky = true;
    public Color skyTop = new Color(0x3F7FD8);
    public Color skyHorizon = new Color(0xBFD9F2);
    public Color skyGround = new Color(0x5F6B58);
    /** The sun glows in the sky where the scene's Light shines from. */
    public boolean sun = true;
    /** The sky darkens at night and glows orange at sunrise and sunset, following the Light's angle. */
    public boolean dayNight = true;

    @Override
    public String type() {
        return "Camera";
    }

    @Override
    public String displayName() {
        return "Camera";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("orthographic", orthographic);
        out.put("fieldOfView", fieldOfView);
        out.put("size", size);
        out.put("near", near);
        out.put("far", far);
        out.put("background", Json.color(background));
        out.put("sky", sky);
        out.put("skyTop", Json.color(skyTop));
        out.put("skyHorizon", Json.color(skyHorizon));
        out.put("skyGround", Json.color(skyGround));
        out.put("sun", sun);
        out.put("dayNight", dayNight);
    }

    @Override
    public void load(Map<String, Object> in) {
        orthographic = Json.bool(in, "orthographic", orthographic);
        fieldOfView = Json.number(in, "fieldOfView", fieldOfView);
        size = Json.number(in, "size", size);
        near = Json.number(in, "near", near);
        far = Json.number(in, "far", far);
        background = Json.color(in, "background", background);
        sky = Json.bool(in, "sky", false);   // scenes from before the sky keep their plain background
        skyTop = Json.color(in, "skyTop", skyTop);
        skyHorizon = Json.color(in, "skyHorizon", skyHorizon);
        skyGround = Json.color(in, "skyGround", skyGround);
        sun = Json.bool(in, "sun", sun);
        dayNight = Json.bool(in, "dayNight", dayNight);
    }
}
