package sev7n.ui;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Sev7n app icon: a purple "7" on a dark rounded square.
 *
 * Drawn in code so the window icon and the shortcut icon always match.
 * To regenerate the Windows .ico file:
 *   java -cp build\classes sev7n.ui.AppIcon assets\sev7n.ico
 */
public final class AppIcon {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private AppIcon() { }

    /** All icon sizes, for JFrame.setIconImages(). */
    public static List<Image> images() {
        List<Image> images = new ArrayList<>();
        for (int size : SIZES) images.add(render(size));
        return images;
    }

    public static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        int corner = Math.max(4, size * 22 / 100);
        g.setColor(Theme.SURFACE);
        g.fillRoundRect(0, 0, size, size, corner * 2, corner * 2);

        // Center the "7" by its actual ink, not its font box, so it sits visually in the middle.
        Font font = Theme.uiFont(Math.round(size * 0.8f), true);
        FontRenderContext context = g.getFontRenderContext();
        TextLayout seven = new TextLayout("7", font, context);
        Rectangle2D ink = seven.getBounds();
        float x = (float) ((size - ink.getWidth()) / 2 - ink.getX());
        float y = (float) ((size - ink.getHeight()) / 2 - ink.getY());
        g.setColor(Theme.ACCENT);
        seven.draw(g, x, y);

        g.dispose();
        return image;
    }

    /**
     * Writes a Windows .ico file containing every size. Following the usual convention,
     * small sizes are stored as classic bitmaps (readable everywhere) and 256px as PNG.
     */
    public static void writeIco(Path file) throws IOException {
        writeIco(file, null);
    }

    /** Like writeIco(file), but made from a picture (a game's own icon); null means the Sev7n logo. */
    public static void writeIco(Path file, BufferedImage picture) throws IOException {
        List<byte[]> entries = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage image = picture == null ? render(size) : fitted(picture, size);
            entries.add(size >= 256 ? png(image) : bitmap(image));
        }

        int headerSize = 6 + 16 * SIZES.length;
        int total = headerSize + entries.stream().mapToInt(e -> e.length).sum();
        ByteBuffer ico = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0);            // reserved
        ico.putShort((short) 1);            // type: icon
        ico.putShort((short) SIZES.length); // image count

        int offset = headerSize;
        for (int i = 0; i < SIZES.length; i++) {
            int size = SIZES[i];
            ico.put((byte) (size >= 256 ? 0 : size)); // 0 means 256
            ico.put((byte) (size >= 256 ? 0 : size));
            ico.put((byte) 0);                        // palette colors
            ico.put((byte) 0);                        // reserved
            ico.putShort((short) 1);                  // color planes
            ico.putShort((short) 32);                 // bits per pixel
            ico.putInt(entries.get(i).length);
            ico.putInt(offset);
            offset += entries.get(i).length;
        }
        for (byte[] entry : entries) ico.put(entry);

        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, ico.array());
    }

    /** A picture shrunk (smoothly) into a size x size square, keeping its shape, centered on a see-through background. */
    public static BufferedImage fitted(BufferedImage picture, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double scale = Math.min((double) size / picture.getWidth(), (double) size / picture.getHeight());
        int w = Math.max(1, (int) Math.round(picture.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(picture.getHeight() * scale));
        // shrink in halves first: one big jump makes small icons look grainy
        BufferedImage step = picture;
        while (step.getWidth() / 2 >= w && step.getHeight() / 2 >= h) {
            BufferedImage half = new BufferedImage(step.getWidth() / 2, step.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = half.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(step, 0, 0, half.getWidth(), half.getHeight(), null);
            g.dispose();
            step = half;
        }
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(step, (size - w) / 2, (size - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    /** A game's own icon for its windows (an exported game with an icon picked); null = the Sev7n logo. */
    private static volatile BufferedImage windowIcon;

    public static void useForWindows(BufferedImage picture) {
        windowIcon = picture;
    }

    /** The window icon at a size: the game's own, if one was set, else the logo. */
    public static BufferedImage windowIcon(int size) {
        BufferedImage custom = windowIcon;
        return custom == null ? render(size) : fitted(custom, size);
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** An icon-style 32-bit DIB: header, bottom-up BGRA pixels, then an (all-clear) AND mask. */
    private static byte[] bitmap(BufferedImage image) {
        int size = image.getWidth();
        int maskRowBytes = (size + 31) / 32 * 4;
        ByteBuffer dib = ByteBuffer.allocate(40 + size * size * 4 + maskRowBytes * size)
                .order(ByteOrder.LITTLE_ENDIAN);
        dib.putInt(40);              // header size
        dib.putInt(size);            // width
        dib.putInt(size * 2);        // height: pixels + mask, as icons require
        dib.putShort((short) 1);     // planes
        dib.putShort((short) 32);    // bits per pixel
        dib.putInt(0);               // no compression
        dib.putInt(size * size * 4); // image size
        dib.putInt(0).putInt(0).putInt(0).putInt(0); // resolution and palette: unused

        for (int y = size - 1; y >= 0; y--) {
            for (int x = 0; x < size; x++) {
                dib.putInt(image.getRGB(x, y)); // ARGB int written little-endian = B, G, R, A
            }
        }
        // The AND mask stays zero: transparency comes from the alpha channel.
        return dib.array();
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "assets/sev7n.ico");
        writeIco(out);
        System.out.println("Wrote " + out);
    }
}
