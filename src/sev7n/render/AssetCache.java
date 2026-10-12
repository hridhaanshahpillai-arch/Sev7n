package sev7n.render;

import sev7n.scene.SpriteFrame;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Loads imported pictures and models onto the GPU, once each, by their path relative to
 * the project folder (e.g. "assets/textures/brick.png").
 *
 * If a file changes on disk (say you edit a texture in Paint), it's reloaded automatically.
 * Missing or broken files return null; the problem is reported once, not every frame.
 */
public final class AssetCache implements AutoCloseable {

    private static final long CHECK_EVERY_MS = 1000;

    private record Entry<T>(T value, long modified, long checkedAt) { }

    private final GraphicsBackend gpu;
    private Path root = Path.of("");
    private Consumer<String> problems = message -> { };
    private final Map<String, Entry<GpuTexture>> textures = new HashMap<>();
    private final Map<String, Entry<Model>> models = new HashMap<>();
    /** Recently used sprite atlases (editing frames makes new ones, so old ones are freed). */
    private final Map<String, Entry<SpriteAtlas>> atlases = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry<SpriteAtlas>> eldest) {
            if (size() <= MAX_ATLASES) return false;
            closeQuietly(eldest.getValue().value());
            return true;
        }
    };
    private static final int MAX_ATLASES = 48;
    /** Decoded pictures for building atlases, so editing frames doesn't re-read the file every time. */
    private final Map<String, Entry<BufferedImage>> pictures = new HashMap<>();
    private final Map<String, Long> failed = new HashMap<>(); // path -> file time when it failed

    public AssetCache(GraphicsBackend gpu) {
        this.gpu = gpu;
    }

    /** The project folder that asset paths are relative to. */
    public void setRoot(Path root) {
        this.root = root;
    }

    /** Where to report files that can't be loaded (e.g. the editor's Console). */
    public void onProblem(Consumer<String> problems) {
        this.problems = problems;
    }

    public Path resolve(String relativePath) {
        return root.resolve(relativePath);
    }

    public GpuTexture texture(String path, boolean pixelArt) {
        return texture(path, pixelArt, false);
    }

    /** Shows every picture with sharp pixels (the "Pixelated textures" setting), whatever each asks for. */
    public void setPixelated(boolean pixelated) {
        this.forcePixelated = pixelated;
    }

    private boolean forcePixelated = false;

    /** @param removeBackground make the picture's background color (its top-left pixel) see-through */
    public GpuTexture texture(String path, boolean pixelArt, boolean removeBackground) {
        if (path == null || path.isEmpty()) return null;
        boolean sharp = pixelArt || forcePixelated;
        String key = path + (sharp ? "#pixel" : "") + (removeBackground ? "#clear" : "");
        return load(textures, key, path, file -> gpu.texture(readPicture(file, removeBackground), sharp));
    }

    /** A sprite sheet's frames, each copied apart with only its own pixels (see SpriteAtlas). */
    public SpriteAtlas spriteAtlas(String path, boolean pixelArt, boolean removeBackground, List<SpriteFrame> frames) {
        if (path == null || path.isEmpty() || frames.isEmpty()) return null;
        boolean sharp = pixelArt || forcePixelated;
        StringBuilder key = new StringBuilder(path).append("#atlas").append(sharp ? "#pixel" : "").append(removeBackground ? "#clear" : "");
        for (SpriteFrame frame : frames) key.append(frame);
        List<SpriteFrame> copies = frames.stream().map(SpriteFrame::copy).toList(); // frames can be edited while it builds
        return load(atlases, key.toString(), path, file -> SpriteAtlas.build(picture(path, removeBackground), copies, sharp, gpu));
    }

    /**
     * The picture as an image in memory (for finding and packing frames), with its background
     * removed if asked. Kept until the file changes. Don't modify the returned image.
     */
    public BufferedImage picture(String path, boolean removeBackground) throws IOException {
        Path file = resolve(path);
        String key = path + (removeBackground ? "#clear" : "");
        long modified = lastModified(file);
        Entry<BufferedImage> entry = pictures.get(key);
        if (entry != null && entry.modified() == modified) return entry.value();
        BufferedImage image = readPicture(file, removeBackground);
        pictures.put(key, new Entry<>(image, modified, System.currentTimeMillis()));
        return image;
    }

    private static BufferedImage readPicture(Path file, boolean removeBackground) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) throw new IOException("not a picture Java can read (try PNG or JPG)");
        return removeBackground ? withoutBackground(image) : image;
    }

    /** Old sprite sheets use one solid color (often blue or magenta) for "see-through"; this makes it so. */
    public static BufferedImage withoutBackground(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        int key = pixels[0];
        int tolerance = 48; // jpeg-ish edges are rarely exactly the key color
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int distance = Math.abs(((p >> 16) & 255) - ((key >> 16) & 255))
                    + Math.abs(((p >> 8) & 255) - ((key >> 8) & 255))
                    + Math.abs((p & 255) - (key & 255));
            if (distance <= tolerance) pixels[i] = 0;
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, width, height, pixels, 0, width);
        return result;
    }

    /** A model file (.glb, .gltf or .obj) on the GPU. */
    public Model model(String path) {
        if (path == null || path.isEmpty()) return null;
        boolean sharp = forcePixelated;
        return load(models, path + (sharp ? "#pixel" : ""), path, file -> Model.upload(loadModel(file), sharp, gpu));
    }

    /** Reads a model file by its type. An .obj's color and texture are applied to its Mesh Renderer on import instead. */
    public static ModelData loadModel(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".glb") || name.endsWith(".gltf")) return GltfLoader.load(file);
        return new ModelData(List.of(new ModelData.Part(ObjLoader.load(file).mesh(), Color.WHITE, null)));
    }

    @FunctionalInterface
    private interface Loader<T> {
        T load(Path file) throws IOException;
    }

    private <T extends AutoCloseable> T load(Map<String, Entry<T>> cache, String key, String path, Loader<T> loader) {
        Path file = resolve(path);
        long now = System.currentTimeMillis();
        Entry<T> entry = cache.get(key);
        if (entry != null && now - entry.checkedAt() < CHECK_EVERY_MS) return entry.value();

        long modified = lastModified(file);
        if (entry != null && entry.modified() == modified) {
            cache.put(key, new Entry<>(entry.value(), modified, now));
            return entry.value();
        }
        if (failed.containsKey(key) && failed.get(key) == modified) return null; // already reported

        try {
            if (modified < 0) throw new IOException("file not found");
            T value = loader.load(file);
            if (entry != null) closeQuietly(entry.value());
            cache.put(key, new Entry<>(value, modified, now));
            failed.remove(key);
            return value;
        } catch (IOException | RuntimeException e) {
            failed.put(key, modified);
            problems.accept("Couldn't load " + path + ": " + e.getMessage());
            return entry == null ? null : entry.value(); // keep showing the old version if there was one
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // GPU resources don't fail to free in practice
        }
    }

    @Override
    public void close() {
        textures.values().forEach(e -> closeQuietly(e.value()));
        models.values().forEach(e -> closeQuietly(e.value()));
        atlases.values().forEach(e -> closeQuietly(e.value()));
        textures.clear();
        models.clear();
        atlases.clear();
    }
}
