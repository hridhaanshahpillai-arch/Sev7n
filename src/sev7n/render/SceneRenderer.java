package sev7n.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import sev7n.math.Vec3;
import sev7n.scene.BoxCollider;
import sev7n.scene.Entity;
import sev7n.scene.Light;
import sev7n.scene.MeshRenderer;
import sev7n.scene.ParticleEmitter;
import sev7n.scene.Scene;
import sev7n.scene.SpriteFrame;
import sev7n.scene.SpriteRenderer;
import sev7n.scene.Tilemap;
import sev7n.ui.Theme;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a scene: lit meshes (built-in or imported, optionally textured), then sprites and
 * tilemaps, plus editor extras (floor grid, selection outline, colliders) when asked.
 *
 * It decides what to draw and in what order; the GraphicsBackend (OpenGL or Vulkan) does the drawing.
 */
public final class SceneRenderer implements AutoCloseable {

    private static final int GRID_HALF_SIZE = 50;
    private static final Vec3 DEFAULT_LIGHT_DIRECTION = new Vec3(-0.4, -1, -0.3);
    private static final Color TRIGGER_COLOR = new Color(0xE7EE98);

    private final GraphicsBackend gpu;
    private final Map<String, GpuMesh> builtIn = new LinkedHashMap<>();
    private final Map<String, Model> builtInModels = new LinkedHashMap<>();
    private final GpuMesh quad;
    private final GpuMesh grid;
    private final GpuMesh wireCube;
    private final GpuTexture softDot; // what particles look like without a picture
    private final AssetCache assets;
    private final Matrix4f model = new Matrix4f();
    private RenderSettings settings = new RenderSettings();

    /** An entity's visible shape in its local space, for outlines and mouse picking. */
    public record Bounds(Matrix4f matrix, Vector3f min, Vector3f max) { }

    /** What the editor shows on top of the scene. */
    public record Overlays(boolean grid, Entity selected, boolean colliders) {
        public static final Overlays NONE = new Overlays(false, null, false);
    }

    /** Draws with OpenGL (the window's context must be current). */
    public SceneRenderer() {
        this(new GlBackend());
    }

    public SceneRenderer(GraphicsBackend gpu) {
        this.gpu = gpu;
        this.assets = new AssetCache(gpu);
        quad = gpu.mesh(MeshData.quad());
        grid = gpu.mesh(MeshData.grid(GRID_HALF_SIZE));
        wireCube = gpu.mesh(MeshData.wireCube());
        softDot = gpu.texture(softDotPicture(64), false);
        builtIn.put("cube", gpu.mesh(MeshData.cube()));
        builtIn.put("sphere", gpu.mesh(MeshData.sphere(32, 16)));
        builtIn.put("plane", gpu.mesh(MeshData.plane()));
        builtIn.put("cylinder", gpu.mesh(MeshData.cylinder(32)));
        builtIn.forEach((name, mesh) -> builtInModels.put(name, Model.of(mesh)));
    }

    /** Imported files are loaded relative to this project folder. */
    public AssetCache assets() {
        return assets;
    }

    public GraphicsBackend backend() {
        return gpu;
    }

    /** A built-in shape or an imported model; anything missing falls back to a cube. */
    public Model model(String name) {
        Model shape = builtInModels.get(name);
        if (shape == null) shape = assets.model(name);
        return shape != null ? shape : builtInModels.get("cube");
    }

    /** The game's look (shading, fog, brightness, texture smoothing). */
    public void setSettings(RenderSettings settings) {
        this.settings = settings;
    }

    public RenderSettings settings() {
        return settings;
    }

    public void render(Scene scene, RenderView view, Color background, Overlays overlays) {
        assets.setPixelated(settings.pixelatedTextures);
        gpu.begin(frame(scene, view, background));
        drawMeshes(scene);
        if (overlays.grid()) drawGrid(view);
        drawSprites(scene, view);
        if (overlays.colliders()) drawColliders(scene);
        if (overlays.selected() != null && scene.contains(overlays.selected())) drawOutline(overlays.selected());
        gpu.end();
    }

    /** The first active Light in the scene lights everything; without one, a soft default light does. */
    private GraphicsBackend.Frame frame(Scene scene, RenderView view, Color background) {
        for (Entity entity : scene.entities()) {
            Light light = entity.get(Light.class);
            if (!entity.active || light == null) continue;
            Vec3 direction = entity.transform.forward();
            float intensity = (float) light.intensity;
            Vector3f color = new Vector3f(light.color.getRed(), light.color.getGreen(), light.color.getBlue()).mul(intensity / 255f);
            return new GraphicsBackend.Frame(view, background, toVector(direction), color, (float) light.ambient, settings);
        }
        return new GraphicsBackend.Frame(view, background, toVector(DEFAULT_LIGHT_DIRECTION), new Vector3f(0.9f), 0.3f, settings);
    }

    private static Vector3f toVector(Vec3 v) {
        return new Vector3f((float) v.x, (float) v.y, (float) v.z);
    }

    // ---------------------------------------------------------------- meshes

    private void drawMeshes(Scene scene) {
        for (Entity entity : scene.entities()) {
            ParticleEmitter emitter = entity.get(ParticleEmitter.class);
            if (entity.active && emitter != null && emitter.look == ParticleEmitter.Look.MODEL) drawModelParticles(emitter); // solid, so drawn with the meshes
            MeshRenderer renderer = entity.get(MeshRenderer.class);
            if (!entity.active || renderer == null) continue;
            entity.transform.matrix(model);
            // the renderer's texture (if set) replaces the model's own; its color tints every part
            GpuTexture override = assets.texture(renderer.texture, false);
            for (Model.Part part : model(renderer.mesh).parts) {
                gpu.drawLit(part.mesh(), override != null ? override : part.texture(), model, multiply(renderer.color, part.color()));
            }
        }
    }

    private static Color multiply(Color a, Color b) {
        if (b == Color.WHITE) return a;
        return new Color(a.getRed() * b.getRed() / 255, a.getGreen() * b.getGreen() / 255, a.getBlue() * b.getBlue() / 255);
    }

    // ---------------------------------------------------------------- sprites & tilemaps

    /** Something see-through to draw (a sprite, tilemap or particle effect), with its distance from the eye and its draw order. */
    private record Flat(Entity entity, float depth, int order, boolean particles) { }

    /**
     * Sprites and tilemaps are see-through, so they're drawn after solid objects,
     * farthest first. Things at the same distance (a flat 2D scene) go by their order,
     * and tilemaps go behind sprites.
     */
    private void drawSprites(Scene scene, RenderView view) {
        List<Flat> flats = new ArrayList<>();
        for (Entity entity : scene.entities()) {
            if (!entity.active) continue;
            SpriteRenderer spriteRenderer = entity.get(SpriteRenderer.class);
            Tilemap tilemap = entity.get(Tilemap.class);
            ParticleEmitter emitter = entity.get(ParticleEmitter.class);
            Vec3 p = entity.transform.position;
            float depth = view.depth(p.x, p.y, p.z);
            if (spriteRenderer != null) flats.add(new Flat(entity, depth, spriteRenderer.order, false));
            else if (tilemap != null && !tilemap.isEmpty()) flats.add(new Flat(entity, depth, -1000, false));
            if (emitter != null && !emitter.particles().isEmpty() && emitter.look == ParticleEmitter.Look.DOT) flats.add(new Flat(entity, depth, Integer.MAX_VALUE, true)); // in front of its own sprite
        }
        flats.sort(Comparator.comparingDouble((Flat f) -> -f.depth()).thenComparingInt(Flat::order));

        for (Flat flat : flats) {
            if (flat.particles()) drawParticles(flat.entity().get(ParticleEmitter.class), view);
            else if (flat.entity().has(SpriteRenderer.class)) drawSprite(flat.entity(), flat.entity().get(SpriteRenderer.class), view);
            else drawTilemap(flat.entity(), flat.entity().get(Tilemap.class));
        }
        forgetUnusedTilemaps();
    }

    private void drawSprite(Entity entity, SpriteRenderer renderer, RenderView view) {
        SpriteLook look = look(renderer);
        // the frame's part of the picture, mirrored if flipped
        float u = look.u(), v = look.v(), w = look.uvWidth(), h = look.uvHeight();
        if (renderer.flipX) {
            u += w;
            w = -w;
        }
        if (renderer.flipY) {
            v += h;
            h = -h;
        }
        gpu.drawSprite(quad, look.texture(), spriteMatrix(entity, renderer, look, view), renderer.tint, u, v, w, h);
    }

    /**
     * How a sprite's current frame is drawn: the texture and the part of it to show, and the
     * card's center and size in the sprite's own space (before flipping).
     */
    private record SpriteLook(GpuTexture texture, float u, float v, float uvWidth, float uvHeight,
                              float centerX, float centerY, float width, float height) { }

    private SpriteLook look(SpriteRenderer renderer) {
        int frame = Math.floorMod(renderer.frame, renderer.frameCount());
        float size = (float) renderer.size;
        SpriteAtlas atlas = assets.spriteAtlas(renderer.texture, renderer.pixelArt, renderer.removeBackground, renderer.frames);
        if (atlas != null) {
            // found frames: the tallest one is 'size' high, and every frame stands on its pivot
            int tallest = renderer.frames.stream().mapToInt(f -> f.height).max().orElse(1);
            float unitsPerPixel = size / Math.max(1, tallest);
            SpriteFrame f = renderer.frames.get(frame);
            float[] uv = atlas.uv(frame);
            float width = f.width * unitsPerPixel;
            float height = f.height * unitsPerPixel;
            float centerX = (f.x + f.width / 2f - f.pivotX) * unitsPerPixel;
            float centerY = -size / 2 + (f.pivotY - (f.y + f.height / 2f)) * unitsPerPixel;
            if (renderer.flipX) centerX = -centerX;
            return new SpriteLook(atlas.texture, uv[0], uv[1], uv[2], uv[3], centerX, centerY, width, height);
        }
        GpuTexture texture = assets.texture(renderer.texture, renderer.pixelArt, renderer.removeBackground);
        int columns = Math.max(1, renderer.columns);
        int rows = Math.max(1, renderer.rows);
        float aspect = texture == null ? 1 : texture.aspect() * rows / columns;
        float w = 1f / columns;
        float h = 1f / rows;
        return new SpriteLook(texture, (frame % columns) * w, (frame / columns) * h, w, h, 0, 0, size * aspect, size);
    }

    private void drawTilemap(Entity entity, Tilemap map) {
        GpuTexture texture = assets.texture(map.tileset, map.pixelArt);
        gpu.drawSprite(tilemapMesh(map, texture), texture, entity.transform.matrix(model),
                texture != null ? Color.WHITE : Theme.SELECTION, 0, 0, 1, 1);
    }

    /** Tilemap meshes are rebuilt only when tiles change. */
    private record TileMesh(GpuMesh mesh, int version, int textureWidth, int textureHeight, long usedAt) { }

    private final Map<Tilemap, TileMesh> tileMeshes = new IdentityHashMap<>();
    private long renderCount = 0;

    private GpuMesh tilemapMesh(Tilemap map, GpuTexture texture) {
        int tw = texture != null ? texture.width() : 1;
        int th = texture != null ? texture.height() : 1;
        TileMesh cached = tileMeshes.get(map);
        if (cached != null && cached.version() == map.version() && cached.textureWidth() == tw && cached.textureHeight() == th) {
            tileMeshes.put(map, new TileMesh(cached.mesh(), cached.version(), tw, th, renderCount));
            return cached.mesh();
        }
        if (cached != null) cached.mesh().close();
        GpuMesh mesh = gpu.mesh(TilemapMesh.build(map, tw, th));
        tileMeshes.put(map, new TileMesh(mesh, map.version(), tw, th, renderCount));
        return mesh;
    }

    /** Frees meshes of tilemaps that are gone (deleted, or from a finished Play session). */
    private void forgetUnusedTilemaps() {
        renderCount++;
        tileMeshes.entrySet().removeIf(entry -> {
            boolean stale = renderCount - entry.getValue().usedAt() > 120;
            if (stale) entry.getValue().mesh().close();
            return stale;
        });
    }

    // ---------------------------------------------------------------- particles

    /** One particle and its distance from the eye, for drawing far ones first. */
    private record Dot(ParticleEmitter.Particle particle, float depth) { }

    /**
     * Every particle is a little card facing the camera. Soft ones are drawn farthest first so
     * they cover each other properly; glowing ones just add up, so their order doesn't matter.
     */
    private void drawParticles(ParticleEmitter emitter, RenderView view) {
        GpuTexture picture = emitter.texture.isEmpty() ? softDot : assets.texture(emitter.texture, false);
        if (picture == null) picture = softDot;
        List<Dot> dots = new ArrayList<>(emitter.particles().size());
        for (ParticleEmitter.Particle p : emitter.particles()) dots.add(new Dot(p, emitter.glow ? 0 : view.depth(p.x, p.y, p.z)));
        if (!emitter.glow) dots.sort(Comparator.comparingDouble((Dot d) -> -d.depth()));

        Matrix4f facing = view.view().invert(new Matrix4f()).setTranslation(0, 0, 0);
        for (Dot dot : dots) {
            ParticleEmitter.Particle p = dot.particle();
            float size = (float) Math.max(0, emitter.size(p));
            if (size == 0) continue;
            model.identity().translate((float) p.x, (float) p.y, (float) p.z).mul(facing)
                    .rotateZ((float) Math.toRadians(p.angle)).scale(size, size, size);
            gpu.drawParticle(quad, picture, model, emitter.color(p), emitter.glow);
        }
    }

    /** Model particles are solid, lit shapes spinning around their own axis; they shrink instead of fading. */
    private void drawModelParticles(ParticleEmitter emitter) {
        Model shape = model(emitter.model);
        for (ParticleEmitter.Particle p : emitter.particles()) {
            float size = (float) Math.max(0, emitter.size(p));
            if (size == 0) continue;
            model.identity().translate((float) p.x, (float) p.y, (float) p.z)
                    .rotate((float) Math.toRadians(p.angle), (float) p.axisX, (float) p.axisY, (float) p.axisZ)
                    .scale(size, size, size);
            Color color = emitter.color(p);
            for (Model.Part part : shape.parts) gpu.drawLit(part.mesh(), part.texture(), model, multiply(color, part.color()));
        }
    }

    /** A white dot that fades out softly toward its edge. */
    private static BufferedImage softDotPicture(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        float center = (size - 1) / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double distance = Math.hypot(x - center, y - center) / (size / 2.0);
                double alpha = Math.max(0, 1 - distance);
                alpha = alpha * alpha * (3 - 2 * alpha); // smooth falloff
                image.setRGB(x, y, ((int) Math.round(alpha * 255) << 24) | 0xFFFFFF);
            }
        }
        return image;
    }

    /** Places the 1x1 quad: entity transform (or facing the camera), then the frame's spot and size. */
    private Matrix4f spriteMatrix(Entity entity, SpriteRenderer renderer, SpriteLook look, RenderView view) {
        Matrix4f m = new Matrix4f();
        if (renderer.billboard) {
            Matrix4f cameraRotation = view.view().invert(new Matrix4f()).setTranslation(0, 0, 0);
            Vec3 p = entity.transform.position;
            Vec3 s = entity.transform.scale;
            m.translate((float) p.x, (float) p.y, (float) p.z).mul(cameraRotation).scale((float) s.x, (float) s.y, (float) s.z);
        } else {
            entity.transform.matrix(m);
        }
        return m.translate(look.centerX(), look.centerY(), 0).scale(look.width(), look.height(), 1);
    }

    // ---------------------------------------------------------------- bounds (outline & picking)

    /** What an entity looks like as a box in its own space, or null if it has nothing visible. */
    public Bounds bounds(Entity entity) {
        Matrix4f matrix = entity.transform.matrix(new Matrix4f());
        MeshRenderer meshRenderer = entity.get(MeshRenderer.class);
        if (meshRenderer != null) {
            Model shape = model(meshRenderer.mesh);
            return new Bounds(matrix, new Vector3f(shape.boundsMin), new Vector3f(shape.boundsMax));
        }
        SpriteRenderer spriteRenderer = entity.get(SpriteRenderer.class);
        if (spriteRenderer != null) {
            SpriteLook look = look(spriteRenderer);
            float w = look.width() / 2;
            float h = look.height() / 2;
            float x = look.centerX();
            float y = look.centerY();
            // a little depth, so billboards can be clicked from any angle
            return new Bounds(matrix, new Vector3f(x - w, y - h, -w * 0.5f), new Vector3f(x + w, y + h, w * 0.5f));
        }
        Tilemap tilemap = entity.get(Tilemap.class);
        if (tilemap != null && !tilemap.isEmpty()) {
            GpuMesh mesh = tilemapMesh(tilemap, assets.texture(tilemap.tileset, tilemap.pixelArt));
            return new Bounds(matrix, new Vector3f(mesh.boundsMin()), new Vector3f(mesh.boundsMax()));
        }
        ParticleEmitter emitter = entity.get(ParticleEmitter.class);
        if (emitter != null) {
            // where particles start, so the effect can be clicked and outlined
            float r = (float) Math.max(0.25, Math.min(emitter.radius, 3));
            return new Bounds(matrix, new Vector3f(-r), new Vector3f(r));
        }
        return null;
    }

    // ---------------------------------------------------------------- editor overlays

    private void drawGrid(RenderView view) {
        // the floor grid (X/Z) stands up as an X/Y grid in the flat 2D view, with no distance fade
        model.identity();
        if (view.orthographic()) model.rotateX((float) Math.PI / 2);
        gpu.drawLines(grid, model, Theme.INDENT_GUIDE.brighter(), 0.8f,
                view.orthographic() ? 1e6f : 12f, view.orthographic() ? 2e6f : 45f, false);
    }

    /** A purple box around the selected entity, drawn on top of everything. */
    private void drawOutline(Entity entity) {
        Bounds bounds = bounds(entity);
        if (bounds != null) {
            Vector3f center = new Vector3f(bounds.min()).add(bounds.max()).mul(0.5f);
            Vector3f size = new Vector3f(bounds.max()).sub(bounds.min()).max(new Vector3f(0.02f));
            model.set(bounds.matrix()).translate(center).scale(size.mul(1.02f));
        } else {
            entity.transform.matrix(model).scale(0.35f); // cameras, lights and empties get a small marker box
        }
        gpu.drawLines(wireCube, model, Theme.ACCENT, 1, 1e6f, 2e6f, true);
    }

    /** Every Box Collider as a wire box: green for solid ones, yellow for triggers. */
    private void drawColliders(Scene scene) {
        for (Entity entity : scene.entities()) {
            BoxCollider collider = entity.get(BoxCollider.class);
            if (!entity.active || collider == null) continue;
            entity.transform.matrix(model)
                    .translate((float) collider.offset.x, (float) collider.offset.y, (float) collider.offset.z)
                    .scale((float) collider.size.x, (float) collider.size.y, (float) collider.size.z);
            gpu.drawLines(wireCube, model, collider.trigger ? TRIGGER_COLOR : Theme.SUCCESS, 1, 1e6f, 2e6f, false);
        }
    }

    @Override
    public void close() {
        builtIn.values().forEach(GpuMesh::close);
        quad.close();
        grid.close();
        wireCube.close();
        softDot.close();
        tileMeshes.values().forEach(t -> t.mesh().close());
        assets.close();
        gpu.close();
    }
}
