package sev7n.scene;

import java.awt.Color;
import java.util.Map;

/**
 * A directional light, like the sun: it shines in the direction the entity faces,
 * everywhere at once. Rotate the entity to change where light comes from.
 */
public final class Light extends Component {

    public Color color = Color.WHITE;
    public double intensity = 1.0;
    public double ambient = 0.25;     // how bright surfaces facing away from the light are

    @Override
    public String type() {
        return "Light";
    }

    @Override
    public String displayName() {
        return "Directional Light";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("color", Json.color(color));
        out.put("intensity", intensity);
        out.put("ambient", ambient);
    }

    @Override
    public void load(Map<String, Object> in) {
        color = Json.color(in, "color", color);
        intensity = Json.number(in, "intensity", intensity);
        ambient = Json.number(in, "ambient", ambient);
    }
}
