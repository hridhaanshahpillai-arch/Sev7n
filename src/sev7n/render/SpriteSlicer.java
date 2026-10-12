package sev7n.render;

import sev7n.scene.SpriteFrame;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the separate frames on a sprite sheet whose frames aren't on an even grid
 * (common in ripped and hand-made sheets).
 *
 *   1. Every separate blob of pixels is found (pixels touching each other, diagonals too).
 *   2. Specks of a few pixels are ignored.
 *   3. Big blobs are the frames' bodies. Bodies whose boxes mostly overlap are one frame;
 *      neighbours that only partly overlap stay apart (when unsure it splits, because
 *      joining two frames afterwards is easy and splitting one isn't).
 *   4. Small loose bits (a flying shell casing, sparks) join the nearest frame.
 *   5. Frames are put in reading order: top row first, left to right.
 *
 * Each frame also gets a pivot: the bottom middle of its biggest blob (the hand and gun,
 * the character). Frames are lined up by their pivots, so a muzzle flash or a casing off
 * to one side doesn't make the sprite jump around.
 *
 * The picture must already have its background see-through (see AssetCache.withoutBackground).
 */
public final class SpriteSlicer {

    /** A box in pixels. */
    private record Box(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        Box union(Box o) {
            int left = Math.min(x, o.x);
            int top = Math.min(y, o.y);
            return new Box(left, top, Math.max(right(), o.right()) - left, Math.max(bottom(), o.bottom()) - top);
        }

        /** How much of the smaller box lies inside the other one: 0 = apart, 1 = fully inside. */
        double overlap(Box o) {
            long w = Math.max(0, Math.min(right(), o.right()) - Math.max(x, o.x));
            long h = Math.max(0, Math.min(bottom(), o.bottom()) - Math.max(y, o.y));
            long smaller = Math.min((long) width * height, (long) o.width * o.height);
            return smaller == 0 ? 0 : (double) (w * h) / smaller;
        }

        double gap(Box o) {
            double dx = Math.max(0, Math.max(x - o.right(), o.x - right()));
            double dy = Math.max(0, Math.max(y - o.bottom(), o.y - bottom()));
            return Math.hypot(dx, dy);
        }

        double centerDistance(Box o) {
            return Math.hypot((x + width / 2.0) - (o.x + o.width / 2.0), (y + height / 2.0) - (o.y + o.height / 2.0));
        }
    }

    /** A blob of touching pixels. */
    private record Blob(Box box, int pixels) { }

    /** A frame being built: its whole box, plus its biggest blob (for the pivot). */
    private record Group(Box box, Blob body) {
        Group join(Group o) {
            return new Group(box.union(o.box), body.pixels() >= o.body.pixels() ? body : o.body);
        }

        Group with(Blob bit) {
            return new Group(box.union(bit.box()), body);
        }
    }

    private static final int ALPHA_THRESHOLD = 16;
    private static final int SPECK = 6;              // blobs this small (in pixels) are noise
    private static final double BODY_SHARE = 0.10;   // blobs with this share of the biggest blob's pixels are frame bodies
    private static final double SAME_FRAME = 0.6;    // bodies whose boxes overlap this much are one frame (a gun and its flash)
    private static final double BIT_REACH = 0.4;     // loose bits join a frame this close (share of a typical frame's height)

    private SpriteSlicer() { }

    public static List<SpriteFrame> findFrames(BufferedImage image) {
        List<Blob> blobs = blobs(image);
        if (blobs.isEmpty()) {
            return List.of(new SpriteFrame(0, 0, image.getWidth(), image.getHeight(), image.getWidth() / 2, image.getHeight(), true));
        }
        int biggest = blobs.stream().mapToInt(Blob::pixels).max().orElse(1);

        List<Group> groups = new ArrayList<>();
        List<Blob> bits = new ArrayList<>();
        for (Blob blob : blobs) {
            if (blob.pixels() >= biggest * BODY_SHARE) groups.add(new Group(blob.box(), blob));
            else bits.add(blob);
        }
        joinOverlapping(groups);

        // loose bits join the nearest frame (ties, e.g. inside two boxes, go to the closest middle)
        List<Box> bodies = groups.stream().map(Group::box).toList();
        double reach = BIT_REACH * typicalHeight(bodies);
        for (Blob bit : bits) {
            int nearest = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < bodies.size(); i++) {
                double score = bit.box().gap(bodies.get(i)) * 1000 + bit.box().centerDistance(bodies.get(i));
                if (score < best) {
                    best = score;
                    nearest = i;
                }
            }
            if (bit.box().gap(bodies.get(nearest)) <= reach) groups.set(nearest, groups.get(nearest).with(bit));
        }

        List<SpriteFrame> frames = new ArrayList<>();
        for (Group group : readingOrder(groups)) {
            Box b = group.box();
            Box body = group.body().box();
            frames.add(new SpriteFrame(b.x(), b.y(), b.width(), b.height(), body.x() + body.width() / 2, body.bottom(), true));
        }
        return frames;
    }

    /** Every 8-connected blob of solid pixels, except specks. */
    private static List<Blob> blobs(BufferedImage image) {
        Labels labels = label(image);
        List<Blob> blobs = new ArrayList<>();
        for (int i = 0; i < labels.boxes().size(); i++) {
            if (labels.pixels().get(i) >= SPECK) blobs.add(new Blob(labels.boxes().get(i), labels.pixels().get(i)));
        }
        return blobs;
    }

    /**
     * Which blob every pixel belongs to: 'ids' holds a blob number per pixel (-1 for
     * see-through pixels), with each blob's box and pixel count.
     */
    record Labels(int[] ids, List<Box> boxes, List<Integer> pixels) { }

    static Labels label(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        int[] ids = new int[width * height];
        java.util.Arrays.fill(ids, -1);
        int[] stack = new int[width * height];
        List<Box> boxes = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int start = 0; start < ids.length; start++) {
            if ((argb[start] >>> 24) <= ALPHA_THRESHOLD || ids[start] >= 0) continue;
            int id = boxes.size();
            int top = 0;
            stack[top++] = start;
            ids[start] = id;
            int minX = width, minY = height, maxX = 0, maxY = 0, pixels = 0;
            while (top > 0) {
                int i = stack[--top];
                int x = i % width;
                int y = i / width;
                pixels++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                        int n = ny * width + nx;
                        if ((argb[n] >>> 24) > ALPHA_THRESHOLD && ids[n] < 0) {
                            ids[n] = id;
                            stack[top++] = n;
                        }
                    }
                }
            }
            boxes.add(new Box(minX, minY, maxX - minX + 1, maxY - minY + 1));
            counts.add(pixels);
        }
        return new Labels(ids, boxes, counts);
    }

    /**
     * Which frame each blob belongs to (-1 for none): the frame whose box fully holds the
     * blob, the closest-centred one if several do, or else the one it overlaps most.
     * This is what stops a frame from showing a piece of its neighbour that pokes into its box.
     */
    static int[] assign(Labels labels, List<SpriteFrame> frames) {
        int[] owner = new int[labels.boxes().size()];
        for (int blob = 0; blob < owner.length; blob++) {
            Box box = labels.boxes().get(blob);
            int best = -1;
            double bestScore = Double.MAX_VALUE;
            for (int f = 0; f < frames.size(); f++) {
                SpriteFrame frame = frames.get(f);
                if (!frame.auto) continue; // hand-drawn frames take whatever is inside them
                Box rect = new Box(frame.x, frame.y, frame.width, frame.height);
                boolean inside = box.x() >= rect.x() && box.y() >= rect.y() && box.right() <= rect.right() && box.bottom() <= rect.bottom();
                double covered = box.overlap(rect);
                if (!inside && covered == 0) continue;
                double score = (inside ? 0 : 1_000_000 * (1 - covered)) + box.centerDistance(rect);
                if (score < bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
            owner[blob] = best;
        }
        return owner;
    }

    private static void joinOverlapping(List<Group> groups) {
        boolean joined = true;
        while (joined) {
            joined = false;
            outer:
            for (int i = 0; i < groups.size(); i++) {
                for (int j = i + 1; j < groups.size(); j++) {
                    if (groups.get(i).box().overlap(groups.get(j).box()) >= SAME_FRAME) {
                        groups.set(i, groups.get(i).join(groups.get(j)));
                        groups.remove(j);
                        joined = true;
                        break outer;
                    }
                }
            }
        }
    }

    private static double typicalHeight(List<Box> boxes) {
        int[] heights = boxes.stream().mapToInt(Box::height).sorted().toArray();
        return heights[heights.length / 2];
    }

    /** Rows from top to bottom (a frame belongs to a row if its middle is level with the row's first frame), then left to right. */
    private static List<Group> readingOrder(List<Group> groups) {
        List<Group> byTop = new ArrayList<>(groups);
        byTop.sort(Comparator.comparingInt(g -> g.box().y()));
        List<List<Group>> rows = new ArrayList<>();
        for (Group group : byTop) {
            int middle = group.box().y() + group.box().height() / 2;
            List<Group> row = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (row != null && middle >= row.get(0).box().y() && middle <= row.get(0).box().bottom()) row.add(group);
            else {
                row = new ArrayList<>();
                row.add(group);
                rows.add(row);
            }
        }
        List<Group> ordered = new ArrayList<>();
        for (List<Group> row : rows) {
            row.sort(Comparator.comparingInt(g -> g.box().x()));
            ordered.addAll(row);
        }
        return ordered;
    }
}
