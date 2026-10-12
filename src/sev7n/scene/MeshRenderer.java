package sev7n.scene;

import java.awt.Color;
import java.util.List;
import java.util.Map;

/**
 * Draws a 3D shape: a built-in one ("cube", "sphere"...) or an imported model
 * ("assets/models/tree/tree.obj"), optionally wrapped in a texture.
 */
public final class MeshRenderer extends Component {

    /** The built-in meshes. Names match sev7n.render.SceneRenderer. */
    public static final List<String> MESHES = List.of("cube", "sphere", "plane", "cylinder");

    public String mesh = "cube";
    public Color color = new Color(0xBF9EEE);
    /** A picture wrapped around the mesh, e.g. "assets/textures/brick.png", or "" for none. */
    public String texture = "";

    /** True for built-in shapes, false for imported model files. */
    public static boolean isBuiltIn(String mesh) {
        return MESHES.contains(mesh);
    }

    @Override
    public String type() {
        return "MeshRenderer";
    }

    @Override
    public String displayName() {
        return "Mesh Renderer";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("mesh", mesh);
        out.put("color", Json.color(color));
        if (!texture.isEmpty()) out.put("texture", texture);
    }

    @Override
    public void load(Map<String, Object> in) {
        mesh = Json.string(in, "mesh", mesh);
        color = Json.color(in, "color", color);
        texture = Json.string(in, "texture", texture);
    }
}
