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
    /**
     * Turns the model inside its object, in degrees around Y. Models made facing +Z (most
     * .glb / .gltf characters) need 180 to face the object's forward, the way NPCs walk.
     */
    public double turn = 0;
    /** Blocks the Directional Light, leaving a shadow (turn off for things like glass or far-away scenery). */
    public boolean castShadows = true;

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
        if (turn != 0) out.put("turn", turn);
        if (!castShadows) out.put("castShadows", false);
    }

    @Override
    public void load(Map<String, Object> in) {
        mesh = Json.string(in, "mesh", mesh);
        color = Json.color(in, "color", color);
        texture = Json.string(in, "texture", texture);
        turn = Json.number(in, "turn", 0);
        castShadows = Json.bool(in, "castShadows", true);
    }
}
