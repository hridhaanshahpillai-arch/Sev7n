package sev7n.render;

import sev7n.scene.SpriteFrame;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A sprite sheet's frames, copied apart onto one clean texture.
 *
 * On hand-made sheets, frames sit close together and their boxes can overlap (a pickaxe
 * head poking over the next frame). Each frame here gets only its own pixels, with empty
 * space around it, so a frame never shows a sliver of its neighbour, even when smoothed:
 *   - frames found automatically keep only the blobs of pixels that belong to them
 *   - hand-drawn frames keep everything inside their rectangle or polygon
 */
public final class SpriteAtlas implements AutoCloseable {

    private static final int PADDING = 4;

    public final GpuTexture texture;
    /** Per frame: where it is on the atlas texture as {u, v, width, height} (0..1). */
    private final List<float[]> uvs;

    private SpriteAtlas(GpuTexture texture, List<float[]> uvs) {
        this.texture = texture;
        this.uvs = uvs;
    }

    public float[] uv(int frame) {
        return uvs.get(Math.floorMod(frame, uvs.size()));
    }

    /** Builds the atlas from a sheet (background already see-through) and its frames. */
    static SpriteAtlas build(BufferedImage sheet, List<SpriteFrame> frames, boolean pixelArt, GraphicsBackend gpu) {
        List<float[]> uvs = new ArrayList<>();
        BufferedImage atlas = pack(sheet, frames, uvs);
        return new SpriteAtlas(gpu.texture(atlas, pixelArt), List.copyOf(uvs));
    }

    /** Copies each frame's own pixels side by side onto a new picture; 'uvs' receives where each one went. */
    public static BufferedImage pack(BufferedImage sheet, List<SpriteFrame> frames, List<float[]> uvs) {
        SpriteSlicer.Labels labels = SpriteSlicer.label(sheet);
        int[] owner = SpriteSlicer.assign(labels, frames);

        int width = PADDING;
        int height = 0;
        for (SpriteFrame frame : frames) {
            width += frame.width + PADDING;
            height = Math.max(height, frame.height);
        }
        height += PADDING * 2;
        BufferedImage atlas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int sheetWidth = sheet.getWidth();
        int sheetHeight = sheet.getHeight();
        int x = PADDING;
        for (int f = 0; f < frames.size(); f++) {
            SpriteFrame frame = frames.get(f);
            for (int dy = 0; dy < frame.height; dy++) {
                for (int dx = 0; dx < frame.width; dx++) {
                    int sx = frame.x + dx;
                    int sy = frame.y + dy;
                    if (sx < 0 || sy < 0 || sx >= sheetWidth || sy >= sheetHeight) continue;
                    if (keeps(frame, f, sx, sy, labels, owner, sheetWidth)) atlas.setRGB(x + dx, PADDING + dy, sheet.getRGB(sx, sy));
                }
            }
            uvs.add(new float[] {(float) x / width, (float) PADDING / height,
                    (float) frame.width / width, (float) frame.height / height});
            x += frame.width + PADDING;
        }
        return atlas;
    }

    /** Whether a sheet pixel belongs in this frame. */
    private static boolean keeps(SpriteFrame frame, int index, int sx, int sy, SpriteSlicer.Labels labels, int[] owner, int sheetWidth) {
        if (frame.polygon != null && !SpriteFrame.insidePolygon(frame.polygon, sx + 0.5, sy + 0.5)) return false;
        if (!frame.auto) return true;
        int blob = labels.ids()[sy * sheetWidth + sx];
        return blob >= 0 && owner[blob] == index;
    }

    @Override
    public void close() {
        texture.close();
    }
}
