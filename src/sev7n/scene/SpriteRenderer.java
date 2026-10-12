package sev7n.scene;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws a picture (a sprite) as a flat card, keeping the picture's shape.
 * Transparent parts of PNGs stay see-through.
 *
 * A sprite sheet (many frames in a grid) is shown one frame at a time: set the
 * grid's columns and rows, then pick the frame (a Sprite Animator can do that for you).
 */
public final class SpriteRenderer extends Component {

    /** The picture, e.g. "assets/textures/hero.png", or "" for a plain colored card. */
    public String texture = "";
    /** Multiplied with the picture's colors; white shows it unchanged. */
    public Color tint = Color.WHITE;
    /** Height in world units (before the entity's scale). Width follows the picture. */
    public double size = 1;
    /** Always turn to face the camera (like a tree in an old 3D game). */
    public boolean billboard = false;
    /** Keep pixels sharp instead of smoothing them: right for pixel art. */
    public boolean pixelArt = false;
    /** Make the picture's background color see-through (the color of its top-left pixel), for retro sprites without transparency. */
    public boolean removeBackground = false;
    /** Mirror the picture left-right (a character facing the other way) or upside down. */
    public boolean flipX = false;
    public boolean flipY = false;
    /** Sprites at the same distance are drawn in this order: higher numbers end up on top. */
    public int order = 0;

    /** The sprite sheet grid: 1 x 1 means the picture is a single frame. */
    public int columns = 1;
    public int rows = 1;
    /**
     * Frames cut out of the sheet (found automatically or drawn in the Sprite Editor).
     * When there are any, they're used instead of the grid, lined up by their pivots.
     */
    public final List<SpriteFrame> frames = new ArrayList<>();
    /** Which frame to show, counting left to right, then top to bottom, from 0. */
    public int frame = 0;

    public int frameCount() {
        return frames.isEmpty() ? Math.max(1, columns) * Math.max(1, rows) : frames.size();
    }

    @Override
    public String type() {
        return "SpriteRenderer";
    }

    @Override
    public String displayName() {
        return "Sprite Renderer";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("texture", texture);
        out.put("tint", Json.color(tint));
        out.put("size", size);
        out.put("billboard", billboard);
        out.put("pixelArt", pixelArt);
        if (removeBackground) out.put("removeBackground", true);
        if (flipX) out.put("flipX", true);
        if (flipY) out.put("flipY", true);
        if (order != 0) out.put("order", order);
        if (frameCount() > 1) {
            out.put("columns", columns);
            out.put("rows", rows);
            out.put("frame", frame);
        }
        if (!frames.isEmpty()) {
            List<Object> list = new ArrayList<>(); // one line per frame
            for (SpriteFrame frame : frames) list.add(frame.toList());
            out.put("frames", list);
        }
    }

    @Override
    public void load(Map<String, Object> in) {
        texture = Json.string(in, "texture", texture);
        tint = Json.color(in, "tint", tint);
        size = Json.number(in, "size", size);
        billboard = Json.bool(in, "billboard", billboard);
        pixelArt = Json.bool(in, "pixelArt", pixelArt);
        removeBackground = Json.bool(in, "removeBackground", removeBackground);
        flipX = Json.bool(in, "flipX", flipX);
        flipY = Json.bool(in, "flipY", flipY);
        order = (int) Json.number(in, "order", order);
        columns = Math.max(1, (int) Json.number(in, "columns", columns));
        rows = Math.max(1, (int) Json.number(in, "rows", rows));
        frame = (int) Json.number(in, "frame", frame);
        frames.clear();
        List<?> saved = Json.list(in, "frames");
        if (!saved.isEmpty() && saved.get(0) instanceof Double) {
            // the first version: six numbers per frame in one long list
            for (int i = 0; i + 5 < saved.size(); i += 6) frames.add(SpriteFrame.fromList(saved.subList(i, i + 6)));
        } else {
            for (Object frame : saved) if (frame instanceof List<?> values) frames.add(SpriteFrame.fromList(values));
        }
    }
}
