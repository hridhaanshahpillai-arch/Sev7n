package sev7n.scene;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One frame cut out of a sprite sheet, in sheet pixels.
 *
 * A frame is a rectangle, or a polygon (any straight edges, diagonals too) inside that
 * rectangle. Frames found automatically show only their own picture, even where a
 * neighbour pokes into the rectangle; frames drawn by hand show exactly what's inside.
 * The pivot is the point frames are lined up by (usually the bottom of the hand or feet).
 */
public final class SpriteFrame {

    public int x;
    public int y;
    public int width;
    public int height;
    public int pivotX;
    public int pivotY;
    /** Found automatically (keeps only its own blobs of pixels). */
    public boolean auto;
    /** Corner points x0, y0, x1, y1, ... for a polygon crop, or null for the whole rectangle. */
    public int[] polygon;

    public SpriteFrame(int x, int y, int width, int height, int pivotX, int pivotY, boolean auto) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.auto = auto;
    }

    /** A hand-drawn rectangle, with its pivot at the bottom middle. */
    public static SpriteFrame rectangle(int x, int y, int width, int height) {
        return new SpriteFrame(x, y, width, height, x + width / 2, y + height, false);
    }

    /** A hand-drawn polygon (x0, y0, x1, y1, ...): its rectangle is the box around the points. */
    public static SpriteFrame polygon(int[] points) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i + 1 < points.length; i += 2) {
            minX = Math.min(minX, points[i]);
            maxX = Math.max(maxX, points[i]);
            minY = Math.min(minY, points[i + 1]);
            maxY = Math.max(maxY, points[i + 1]);
        }
        SpriteFrame frame = rectangle(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
        frame.polygon = points.clone();
        return frame;
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean contains(double px, double py) {
        if (px < x || py < y || px >= right() || py >= bottom()) return false;
        return polygon == null || insidePolygon(polygon, px, py);
    }

    /** Even-odd test: is the point inside the polygon? */
    public static boolean insidePolygon(int[] points, double px, double py) {
        boolean inside = false;
        int n = points.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = points[i * 2], yi = points[i * 2 + 1];
            double xj = points[j * 2], yj = points[j * 2 + 1];
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }

    /** Moves the frame (and its pivot and polygon) by whole pixels. */
    public void move(int dx, int dy) {
        x += dx;
        y += dy;
        pivotX += dx;
        pivotY += dy;
        if (polygon != null) {
            for (int i = 0; i + 1 < polygon.length; i += 2) {
                polygon[i] += dx;
                polygon[i + 1] += dy;
            }
        }
    }

    /**
     * This frame and the next as one (for a frame that was found in two pieces): the box
     * around both, keeping the bigger piece's pivot. A polygon crop becomes a rectangle.
     */
    public SpriteFrame joined(SpriteFrame other) {
        int left = Math.min(x, other.x);
        int top = Math.min(y, other.y);
        boolean thisIsBigger = (long) width * height >= (long) other.width * other.height;
        SpriteFrame bigger = thisIsBigger ? this : other;
        return new SpriteFrame(left, top, Math.max(right(), other.right()) - left, Math.max(bottom(), other.bottom()) - top,
                bigger.pivotX, bigger.pivotY, auto && other.auto);
    }

    public SpriteFrame copy() {
        SpriteFrame copy = new SpriteFrame(x, y, width, height, pivotX, pivotY, auto);
        copy.polygon = polygon == null ? null : polygon.clone();
        return copy;
    }

    /** As numbers for the scene file: x, y, w, h, pivotX, pivotY, auto (1/0), then polygon points. */
    public List<Object> toList() {
        List<Object> out = new ArrayList<>(List.of(x, y, width, height, pivotX, pivotY, auto ? 1 : 0));
        if (polygon != null) for (int value : polygon) out.add(value);
        return out;
    }

    public static SpriteFrame fromList(List<?> values) {
        int[] v = new int[values.size()];
        for (int i = 0; i < v.length; i++) v[i] = values.get(i) instanceof Double d ? d.intValue() : 0;
        if (v.length < 6) return rectangle(0, 0, 1, 1);
        SpriteFrame frame = new SpriteFrame(v[0], v[1], Math.max(1, v[2]), Math.max(1, v[3]), v[4], v[5], v.length < 7 || v[6] != 0);
        if (v.length >= 7 + 6) { // at least 3 corner points
            frame.polygon = Arrays.copyOfRange(v, 7, v.length - (v.length - 7) % 2);
        }
        return frame;
    }

    @Override
    public String toString() {
        return toList().toString();
    }
}
