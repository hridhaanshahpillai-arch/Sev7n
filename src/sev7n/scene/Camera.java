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
    }

    @Override
    public void load(Map<String, Object> in) {
        orthographic = Json.bool(in, "orthographic", orthographic);
        fieldOfView = Json.number(in, "fieldOfView", fieldOfView);
        size = Json.number(in, "size", size);
        near = Json.number(in, "near", near);
        far = Json.number(in, "far", far);
        background = Json.color(in, "background", background);
    }
}
