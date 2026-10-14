package sev7n.scene;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import sev7n.math.Vec3;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What's drawn on top of the game this frame: text, boxes, bars, shapes and pictures (score,
 * health, menus). Scripts add to it every frame (text(...), rect(...)...); the game clears it
 * before each update, and the renderer draws it.
 *
 * Positions are in screen units: (0, 0) is the top left, the screen is HEIGHT units tall and as
 * wide as its shape makes it (1280 for a 16:9 window), whatever its real size in pixels.
 *
 * Things can also be drawn on a canvas floating in the world (a hologram, a sign, a panel beside
 * the player): between beginWorld(...) and endWorld(), the same units are measured on that
 * canvas instead of the screen, and what's drawn is hidden behind things in front of it.
 * Glowing things (setGlow) add their light instead of covering what's behind, like a hologram.
 */
public final class Hud {

    public static final double HEIGHT = 720;
    /** The font text is drawn with, at the size its pictures are made (then scaled). */
    public static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 64);
    /** Space around text pictures for the shadow, at FONT's size. */
    public static final int TEXT_PADDING = 4;
    private static final FontMetrics METRICS = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().getFontMetrics(FONT);

    /** Something on the HUD. */
    public sealed interface Item permits Text, Box, Picture, Line, Circle { }

    /**
     * 'align' is "left", "center" or "right": which part of the text is at x. y is the top of the text.
     * 'shadow' gives it a soft dark shadow (on for light text over the game, off for dark text on buttons).
     */
    public record Text(String text, double x, double y, double size, Color color, String align, boolean shadow) implements Item {
        public Text(String text, double x, double y, double size, Color color, String align) {
            this(text, x, y, size, color, align, true);
        }
    }

    /** A filled box; 'radius' rounds its corners (half its height makes a pill or a circle). */
    public record Box(double x, double y, double width, double height, Color color, double radius) implements Item {
        public Box(double x, double y, double width, double height, Color color) {
            this(x, y, width, height, color, 0);
        }
    }

    public record Picture(String path, double x, double y, double width, double height, Color tint) implements Item { }

    /** A straight line 'thickness' wide. */
    public record Line(double x1, double y1, double x2, double y2, double thickness, Color color) implements Item { }

    /** A filled circle (thickness 0) or a ring 'thickness' wide. */
    public record Circle(double x, double y, double radius, double thickness, Color color) implements Item { }

    /**
     * A canvas floating in the world: its top left corner at 'position', 'scale' world units per
     * screen unit, standing up and turned 'yaw' degrees around the up axis, or, if yaw is NaN,
     * square on to the camera (like a sprite).
     */
    public record Canvas(Vec3 position, double yaw, double scale) {
        public boolean facesCamera() {
            return Double.isNaN(yaw);
        }
    }

    /** One thing to draw, where (null canvas = the screen), and whether it glows. */
    public record Entry(Item item, Canvas canvas, boolean glow) { }

    private final List<Entry> entries = new ArrayList<>();
    private Canvas canvas;
    private boolean glow;
    private double aspect = 16 / 9.0;
    private final Matrix4f camera = new Matrix4f(); // world -> the screen's -1..1 square, as last drawn

    public void add(Item item) {
        entries.add(new Entry(item, canvas, glow));
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** Forgets everything drawn (before each frame's update), back to the screen without glow. */
    public void clear() {
        entries.clear();
        canvas = null;
        glow = false;
    }

    /** From now on, draw on a canvas in the world (null: back to the screen). */
    public void setCanvas(Canvas canvas) {
        this.canvas = canvas;
    }

    /** From now on, things glow (add their light) or don't. */
    public void setGlow(boolean glow) {
        this.glow = glow;
    }

    /** The screen's width in screen units. */
    public double width() {
        return HEIGHT * aspect;
    }

    /** How wide a piece of text is when drawn at 'size' (screen units). */
    public static double textWidth(String text, double size) {
        return (METRICS.stringWidth(text) + TEXT_PADDING * 2) * size / FONT.getSize();
    }

    /** How tall text drawn at 'size' is (screen units). */
    public static double textHeight(double size) {
        return (METRICS.getHeight() + TEXT_PADDING * 2) * size / FONT.getSize();
    }

    /** Set by the renderer each frame: the screen's shape and the camera it was drawn with (projection times view). */
    public void setView(double aspect, Matrix4f viewProjection) {
        if (aspect > 0 && Double.isFinite(aspect)) this.aspect = aspect;
        camera.set(viewProjection);
    }

    /** Where a point in the world shows on the screen (screen units, as x and y of a Vec3), or null if it's behind the camera. */
    public Vec3 worldToScreen(Vec3 point) {
        Vector4f clip = camera.transform(new Vector4f((float) point.x, (float) point.y, (float) point.z, 1));
        if (clip.w <= 1e-5) return null;
        double x = (clip.x / clip.w + 1) / 2 * width();
        double y = (1 - clip.y / clip.w) / 2 * HEIGHT;
        return new Vec3(x, y, 0);
    }
}
