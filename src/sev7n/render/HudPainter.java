package sev7n.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import sev7n.scene.Hud;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws a Hud: every item is a picture card. On the screen, cards sit just in front of the
 * camera, exactly covering their spot, so it works with any camera (3D or 2D) and with both
 * OpenGL and Vulkan; later items are a hair nearer, so they're drawn over earlier ones. On a
 * world canvas, cards lie on the canvas (each a hair in front of the one before), hidden by
 * whatever is in front of them.
 *
 * Shapes come from a few pictures: a white pixel (boxes, lines), a soft disc (round corners,
 * circles) and rings of different thicknesses. Text is drawn into a picture with Java's fonts and
 * tinted by the text's color; pictures of text are kept while they're used and freed when they aren't.
 */
final class HudPainter implements AutoCloseable {

    private static final int FONT_PIXELS = Hud.FONT.getSize(); // text pictures are made this big, then scaled
    private static final Font FONT = Hud.FONT;
    private static final int ROUND_PIXELS = 64;
    private static final double CANVAS_STEP = 0.0008; // world units each canvas item sits in front of the last

    private final GraphicsBackend gpu;
    private final GpuMesh quad;
    private final AssetCache assets;
    private final GpuTexture white;
    private final GpuTexture disc; // its quarters make rounded corners
    private final Map<Integer, GpuTexture> rings = new HashMap<>();
    private final Map<String, TextPicture> texts = new HashMap<>();
    private long frame;

    private record TextPicture(GpuTexture texture, float width, float height, long[] usedAt) { }

    /** Where one item is drawn: screen units (x right, y down) to the world, and whether it glows or lies on a world canvas. */
    private record Place(Vector3f origin, Vector3f right, Vector3f down, boolean glow, boolean onCanvas) { }

    HudPainter(GraphicsBackend gpu, GpuMesh quad, AssetCache assets) {
        this.gpu = gpu;
        this.quad = quad;
        this.assets = assets;
        BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        pixel.setRGB(0, 0, 0xFFFFFFFF);
        white = gpu.texture(pixel, true);
        disc = gpu.texture(ring(1), false);
    }

    void draw(Hud hud, RenderView view) {
        frame++;
        Matrix4f projection = view.projection();
        hud.setView(projection.m11() / projection.m00(), new Matrix4f(projection).mul(view.view()));
        Matrix4f toView = projection.invert(new Matrix4f());
        Matrix4f toWorld = view.view().invert(new Matrix4f());
        int onScreen = 0;
        int onCanvas = 0;
        for (Hud.Entry entry : hud.entries()) {
            Place place = entry.canvas() == null
                    ? screenPlace(hud, toView, toWorld, -0.9 - 0.0002 * Math.min(onScreen++, 400), entry.glow())
                    : canvasPlace(entry.canvas(), view, onCanvas++, entry.glow());
            drawItem(entry.item(), place);
        }
        forgetUnusedText();
    }

    private void drawItem(Hud.Item item, Place place) {
        switch (item) {
            case Hud.Box box -> {
                if (box.radius() > 0) roundedBox(box, place);
                else card(white, place, box.x(), box.y(), box.width(), box.height(), 0, box.color(), 0, 0, 1, 1);
            }
            case Hud.Picture picture -> {
                GpuTexture texture = assets.texture(picture.path(), false);
                if (texture != null) card(texture, place, picture.x(), picture.y(), picture.width(), picture.height(), 0, picture.tint(), 0, 0, 1, 1);
            }
            case Hud.Line line -> {
                double dx = line.x2() - line.x1(), dy = line.y2() - line.y1();
                double length = Math.hypot(dx, dy);
                if (length < 1e-9) return;
                card(white, place, (line.x1() + line.x2()) / 2 - length / 2, (line.y1() + line.y2()) / 2 - line.thickness() / 2,
                        length, line.thickness(), Math.atan2(dy, dx), line.color(), 0, 0, 1, 1);
            }
            case Hud.Circle circle -> {
                double r = circle.radius();
                GpuTexture texture = circle.thickness() <= 0 || circle.thickness() >= r ? disc : ringTexture(circle.thickness() / r);
                card(texture, place, circle.x() - r, circle.y() - r, r * 2, r * 2, 0, circle.color(), 0, 0, 1, 1);
            }
            case Hud.Text text -> {
                TextPicture picture = picture(text.text(), text.shadow());
                if (picture == null) return;
                double scale = text.size() / FONT_PIXELS;
                double width = picture.width() * scale, height = picture.height() * scale;
                double x = switch (text.align()) {
                    case "center" -> text.x() - width / 2;
                    case "right" -> text.x() - width;
                    default -> text.x();
                };
                card(picture.texture(), place, x, text.y(), width, height, 0, text.color(), 0, 0, 1, 1);
            }
        }
    }

    // ---------------------------------------------------------------- where things go

    /** The screen at one depth just past the near plane: unprojecting three points gives where screen units go. */
    private static Place screenPlace(Hud hud, Matrix4f toView, Matrix4f toWorld, double depth, boolean glow) {
        Vector3f origin = unproject(toView, -1, 1, depth);
        Vector3f oneRight = unproject(toView, -1 + 2 / hud.width(), 1, depth);
        Vector3f oneDown = unproject(toView, -1, 1 - 2 / Hud.HEIGHT, depth);
        Vector3f right = oneRight.sub(origin, new Vector3f());
        Vector3f down = oneDown.sub(origin, new Vector3f());
        toWorld.transformPosition(origin);
        toWorld.transformDirection(right);
        toWorld.transformDirection(down);
        return new Place(origin, right, down, glow, false);
    }

    /** A canvas in the world, facing its way (or the camera), each item a hair in front of the last. */
    private static Place canvasPlace(Hud.Canvas canvas, RenderView view, int index, boolean glow) {
        Vector3f origin = new Vector3f((float) canvas.position().x, (float) canvas.position().y, (float) canvas.position().z);
        float scale = (float) canvas.scale();
        Vector3f facing, right, down;
        if (canvas.facesCamera()) {
            // square on to the camera, like a sprite: the camera's own right, up and back
            Matrix4f toWorld = view.view().invert(new Matrix4f());
            right = toWorld.transformDirection(new Vector3f(1, 0, 0)).normalize().mul(scale);
            down = toWorld.transformDirection(new Vector3f(0, -1, 0)).normalize().mul(scale);
            facing = toWorld.transformDirection(new Vector3f(0, 0, 1)).normalize();
        } else {
            double radians = Math.toRadians(canvas.yaw());
            facing = new Vector3f((float) Math.sin(radians), 0, (float) Math.cos(radians)); // toward the viewer
            right = new Vector3f(facing.z, 0, -facing.x).mul(scale);
            down = new Vector3f(0, -scale, 0);
        }
        origin.fma((float) (CANVAS_STEP * index), facing);
        return new Place(origin, right, down, glow, true);
    }

    private static Vector3f unproject(Matrix4f toView, double x, double y, double z) {
        Vector4f p = toView.transform(new Vector4f((float) x, (float) y, (float) z, 1));
        return new Vector3f(p.x / p.w, p.y / p.w, p.z / p.w);
    }

    // ---------------------------------------------------------------- drawing cards

    /** A box with rounded corners: nine pieces, the corners cut from the disc, the rest stretched. */
    private void roundedBox(Hud.Box box, Place place) {
        double r = Math.min(box.radius(), Math.min(box.width(), box.height()) / 2);
        double[] xs = {box.x(), box.x() + r, box.x() + box.width() - r, box.x() + box.width()};
        double[] ys = {box.y(), box.y() + r, box.y() + box.height() - r, box.y() + box.height()};
        float[] us = {0, 0.5f, 0.5f, 1};
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                card(disc, place, xs[column], ys[row], xs[column + 1] - xs[column], ys[row + 1] - ys[row], 0, box.color(),
                        us[column], us[row], us[column + 1] - us[column], us[row + 1] - us[row]);
            }
        }
    }

    /**
     * A picture (or the part u, v, uw, vh of it) covering the rectangle (x, y, width, height) in
     * screen units, turned 'angle' radians around its middle.
     */
    private void card(GpuTexture texture, Place place, double x, double y, double width, double height, double angle,
                      Color tint, float u, float v, float uw, float vh) {
        if (width <= 0 || height <= 0) return;
        double cx = x + width / 2, cy = y + height / 2;
        double cos = Math.cos(angle), sin = Math.sin(angle);
        // the card's own across and down directions in screen units, turned, then into the world
        Vector3f across = new Vector3f(place.right()).mul((float) (cos * width)).fma((float) (sin * width), place.down());
        Vector3f downward = new Vector3f(place.right()).mul((float) (-sin * height)).fma((float) (cos * height), place.down());
        Vector3f middle = new Vector3f(place.origin()).fma((float) cx, place.right()).fma((float) cy, place.down());
        // the quad runs -0.5..0.5 with +y at the top: its y axis points up the card
        Vector3f normal = across.cross(downward, new Vector3f()).normalize();
        Matrix4f model = new Matrix4f(
                across.x, across.y, across.z, 0,
                -downward.x, -downward.y, -downward.z, 0,
                normal.x, normal.y, normal.z, 0,
                middle.x, middle.y, middle.z, 1);
        if (place.glow() || place.onCanvas()) gpu.drawParticle(quad, texture, model, tint, place.glow(), u, v, uw, vh);
        else gpu.drawSprite(quad, texture, model, tint, u, v, uw, vh);
    }

    // ---------------------------------------------------------------- pictures

    /** A white ring whose band is 'thickness' of its radius (1 = a filled disc), with smooth edges. */
    private static BufferedImage ring(double thickness) {
        BufferedImage image = new BufferedImage(ROUND_PIXELS, ROUND_PIXELS, BufferedImage.TYPE_INT_ARGB);
        double r = ROUND_PIXELS / 2.0;
        double inner = r * (1 - thickness);
        for (int y = 0; y < ROUND_PIXELS; y++) {
            for (int x = 0; x < ROUND_PIXELS; x++) {
                double d = Math.hypot(x + 0.5 - r, y + 0.5 - r);
                double outside = Math.max(0, Math.min(1, r - d));
                double inside = thickness >= 1 ? 1 : Math.max(0, Math.min(1, d - inner));
                int alpha = (int) Math.round(255 * outside * inside);
                image.setRGB(x, y, alpha << 24 | 0xFFFFFF);
            }
        }
        return image;
    }

    /** A ring picture for a band 'thickness' of the radius (rounded to one of 32 steps, and kept). */
    private GpuTexture ringTexture(double thickness) {
        int step = (int) Math.max(1, Math.min(32, Math.round(thickness * 32)));
        return rings.computeIfAbsent(step, s -> gpu.texture(ring(s / 32.0), false));
    }

    /** The picture of a piece of text (made the first time it's needed), or null for empty text. */
    private TextPicture picture(String text, boolean shadow) {
        if (text == null || text.isEmpty()) return null;
        String key = (shadow ? "s:" : "p:") + text;
        TextPicture picture = texts.get(key);
        if (picture == null) {
            picture = render(text, shadow);
            texts.put(key, picture);
        }
        picture.usedAt()[0] = frame;
        return picture;
    }

    private TextPicture render(String text, boolean withShadow) {
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = measure.createGraphics();
        FontMetrics metrics = g.getFontMetrics(FONT);
        g.dispose();
        int shadow = Hud.TEXT_PADDING;
        int width = Math.max(1, metrics.stringWidth(text) + shadow * 2);
        int height = metrics.getHeight() + shadow * 2;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(FONT);
        if (withShadow) {
            g.setColor(new Color(0, 0, 0, 150));
            g.drawString(text, shadow * 2, metrics.getAscent() + shadow * 2);
        }
        g.setColor(Color.WHITE);
        g.drawString(text, shadow, metrics.getAscent() + shadow);
        g.dispose();
        return new TextPicture(gpu.texture(image, false), width, height, new long[] {frame});
    }

    /** Frees pictures of text that haven't been shown for a few seconds (a score that's moved on). */
    private void forgetUnusedText() {
        texts.values().removeIf(picture -> {
            boolean stale = frame - picture.usedAt()[0] > 180;
            if (stale) picture.texture().close();
            return stale;
        });
    }

    @Override
    public void close() {
        texts.values().forEach(picture -> picture.texture().close());
        texts.clear();
        rings.values().forEach(GpuTexture::close);
        white.close();
        disc.close();
    }
}
