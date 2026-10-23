package sev7n.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sev7n.math.Easing;
import sev7n.math.Vec3;
import sev7n.scene.Animator;
import sev7n.scene.BoxCollider;
import sev7n.scene.CapsuleCollider;
import sev7n.scene.Entity;
import sev7n.scene.Light;
import sev7n.scene.Npc;
import sev7n.scene.Ragdoll;
import sev7n.scene.Terrain;
import sev7n.scene.MeshRenderer;
import sev7n.scene.ParticleEmitter;
import sev7n.scene.Scene;
import sev7n.scene.SphereCollider;
import sev7n.scene.SpriteFrame;
import sev7n.scene.SpriteRenderer;
import sev7n.scene.Tilemap;
import sev7n.ui.Theme;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final GpuMesh wireSphere;
    private final GpuMesh wireCapsuleSides;
    private final Map<Integer, GpuMesh> fans = new HashMap<>();
    private final HudPainter hudPainter;
    private final GpuTexture softDot; // what particles look like without a picture
    private final AssetCache assets;
    private final Matrix4f model = new Matrix4f();
    private RenderSettings settings = new RenderSettings();

    /** An entity's visible shape in its local space, for outlines and mouse picking. */
    public record Bounds(Matrix4f matrix, Vector3f min, Vector3f max) { }

    /** What the editor shows on top of the scene. */
    public record Overlays(boolean grid, Entity selected, boolean colliders, boolean hud) {
        /** The game as players see it: no editor helpers, with the HUD. */
        public static final Overlays NONE = new Overlays(false, null, false, true);
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
        wireSphere = gpu.mesh(MeshData.wireSphere(32));
        wireCapsuleSides = gpu.mesh(MeshData.wireCapsuleSides());
        softDot = gpu.texture(softDotPicture(64), false);
        hudPainter = new HudPainter(gpu, quad, assets);
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
        eye = view.eye();
        Map<Entity, Posed> animated = poseCharacters(scene);
        Entity sun = sun(scene);
        Vector3f lightDirection = toVector(sun != null ? sun.transform.forward() : DEFAULT_LIGHT_DIRECTION);
        GraphicsBackend.Shadows shadows = drawShadowMap(scene, view, sun, lightDirection, animated);
        SkyLook sky = skyLook(scene, view, lightDirection);
        if (sky != null) background = sky.horizon(); // the fog fades into the horizon, so faraway things melt into the sky
        gpu.begin(frame(sun, lightDirection, view, background, shadows));
        if (sky != null) drawSky(view, sky, lightDirection);
        drawMeshes(scene, animated, false);
        if (overlays.grid()) drawGrid(view);
        drawSprites(scene, view);
        if (overlays.colliders()) drawColliders(scene);
        if (overlays.selected() != null && scene.contains(overlays.selected())) {
            drawOutline(overlays.selected());
            Npc npc = overlays.selected().get(Npc.class);
            if (npc != null) drawSenses(overlays.selected(), npc);
        }
        if (overlays.hud()) hudPainter.draw(scene.hud, view);
        gpu.end();
    }

    // ---------------------------------------------------------------- sky

    /** This frame's sky colours (after day and night) and whether the sun shows. */
    private record SkyLook(Color top, Color horizon, Color ground, boolean sun, Color sunColor) { }

    /** Sky pictures by colours (a sun moving through the day changes them slowly). The oldest go once there are many;
     *  they were last used dozens of frames ago, so no frame still in flight needs them. */
    private final Map<String, GpuTexture> skyTextures = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, GpuTexture> eldest) {
            if (size() <= 24) return false;
            eldest.getValue().close();
            return true;
        }
    };

    /** The main camera's sky, coloured for the time of day, or null (no sky, or a flat 2D view). */
    private static SkyLook skyLook(Scene scene, RenderView view, Vector3f lightDirection) {
        if (view.orthographic()) return null;
        Entity cameraEntity = scene.mainCamera();
        sev7n.scene.Camera camera = cameraEntity == null ? null : cameraEntity.get(sev7n.scene.Camera.class);
        if (camera == null || !camera.sky || camera.orthographic) return null;
        Color top = camera.skyTop, horizon = camera.skyHorizon, ground = camera.skyGround;
        Color sunColor = new Color(255, 246, 225);
        if (camera.dayNight) {
            float height = -lightDirection.y;                                   // the sun's height: 1 overhead, 0 on the horizon
            float day = smoothstep(-0.18f, 0.22f, height);                       // 0 at night, 1 by day
            float dusk = Math.max(0, 1 - Math.abs(height - 0.02f) / 0.28f);      // strongest as the sun touches the horizon
            top = mix(new Color(0x0B1026), top, day);
            horizon = mix(mix(new Color(0x1C2340), horizon, day), new Color(0xF2955A), dusk * 0.65f);
            ground = mix(new Color(0x101218), ground, day);
            sunColor = mix(sunColor, new Color(255, 170, 110), dusk);
        }
        return new SkyLook(top, horizon, ground, camera.sun, sunColor);
    }

    /**
     * The sky behind everything: a card just past the near plane filling the view, showing the slice of the
     * sky gradient between the view's top and bottom edges, then the sun where the light comes from.
     * Neither writes depth and both go first, so everything else is drawn in front of them.
     */
    private void drawSky(RenderView view, SkyLook sky, Vector3f lightDirection) {
        sky = new SkyLook(coarse(sky.top()), coarse(sky.horizon()), coarse(sky.ground()), sky.sun(), sky.sunColor());
        String key = sky.top().getRGB() + ":" + sky.horizon().getRGB() + ":" + sky.ground().getRGB();
        GpuTexture skyTexture = skyTextures.get(key);
        if (skyTexture == null) {
            skyTexture = gpu.texture(skyPicture(sky), false);
            skyTextures.put(key, skyTexture);
        }
        Matrix4f p = view.projection();
        float near = p.m32() / (p.m22() - 1);
        float d = Math.max(0.02f, near * 1.5f);
        Matrix4f camera = view.view().invert(new Matrix4f());
        Vector3f forward = camera.transformDirection(new Vector3f(0, 0, -1)).normalize();
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, forward.y))));
        float halfFov = (float) Math.toDegrees(Math.atan(1 / p.m11()));
        float vTop = clamp01((90 - (pitch + halfFov)) / 180), vBottom = clamp01((90 - (pitch - halfFov)) / 180);
        Matrix4f card = new Matrix4f(camera).translate(0, 0, -d).scale(2 * d / p.m00() * 1.02f, 2 * d / p.m11() * 1.02f, 1);
        gpu.drawParticle(quad, skyTexture, card, Color.WHITE, false, 0, vTop, 1, Math.max(0.001f, vBottom - vTop));

        Vector3f toSun = new Vector3f(lightDirection).negate().normalize();
        if (!sky.sun() || toSun.y < -0.12f) return;
        Matrix4f facing = new Matrix4f(camera).setTranslation(0, 0, 0);
        Vector3f at = new Vector3f(view.eye()).add(new Vector3f(toSun).mul(d));
        float glow = 2 * d * (float) Math.tan(Math.toRadians(9)), core = 2 * d * (float) Math.tan(Math.toRadians(1.6));
        Color c = sky.sunColor();
        gpu.drawParticle(quad, softDot, new Matrix4f().translate(at).mul(facing).scale(glow), new Color(c.getRed(), c.getGreen(), c.getBlue(), 70), true);
        gpu.drawParticle(quad, softDot, new Matrix4f().translate(at).mul(facing).scale(core * 2.2f), new Color(c.getRed(), c.getGreen(), c.getBlue(), 160), true);
        gpu.drawParticle(quad, softDot, new Matrix4f().translate(at).mul(facing).scale(core), c, true);
    }

    /** The whole sky from straight up (top row) to straight down (bottom row). */
    private static java.awt.image.BufferedImage skyPicture(SkyLook sky) {
        int rows = 256;
        java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(1, rows, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < rows; y++) {
            float elevation = 90 - 180 * (y + 0.5f) / rows;
            Color c = elevation >= 0
                    ? mix(sky.horizon(), sky.top(), (float) Math.pow(elevation / 90, 0.55))
                    : mix(sky.horizon(), sky.ground(), Math.min(1, -elevation / 6));
            picture.setRGB(0, y, c.getRGB() | 0xFF000000);
        }
        return picture;
    }

    /** Colours in steps of 4, so a slowly moving sun makes a new sky picture only now and then. */
    private static Color coarse(Color c) {
        return new Color(c.getRed() & ~3, c.getGreen() & ~3, c.getBlue() & ~3);
    }

    private static Color mix(Color a, Color b, float t) {
        t = clamp01(t);
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static float smoothstep(float from, float to, float x) {
        float t = clamp01((x - from) / (to - from));
        return t * t * (3 - 2 * t);
    }

    private static float clamp01(float x) {
        return Math.max(0, Math.min(1, x));
    }

    /** The first active Light in the scene lights everything (null: none, so a soft default light does). */
    private static Entity sun(Scene scene) {
        for (Entity entity : scene.entities()) {
            if (entity.active && entity.has(Light.class)) return entity;
        }
        return null;
    }

    private GraphicsBackend.Frame frame(Entity sun, Vector3f lightDirection, RenderView view, Color background,
                                        GraphicsBackend.Shadows shadows) {
        if (sun == null) return new GraphicsBackend.Frame(view, background, lightDirection, new Vector3f(0.9f), 0.3f, settings, shadows);
        Light light = sun.get(Light.class);
        Vector3f color = new Vector3f(light.color.getRed(), light.color.getGreen(), light.color.getBlue()).mul((float) light.intensity / 255f);
        return new GraphicsBackend.Frame(view, background, lightDirection, color, (float) light.ambient, settings, shadows);
    }

    // ---------------------------------------------------------------- shadows

    /** How far behind the shadow area (toward the light) things still cast into it: tall towers, hills. */
    private static final float SHADOW_REACH = 100;
    /** How dark shadows are without a Light in the scene. */
    private static final float DEFAULT_SHADOW_STRENGTH = 0.6f;

    // the light's view of the shadow area this frame: its rotation, and the area's center and half size in it
    private final Matrix4f lightView = new Matrix4f();
    private final Vector3f shadowCenter = new Vector3f();
    private float shadowRadius;

    /**
     * Draws the shadow map: how far every caster near the camera is from the light, looking
     * along the light. Lit surfaces then check it to see whether something is between them
     * and the light. Returns null when there are no shadows this frame.
     */
    private GraphicsBackend.Shadows drawShadowMap(Scene scene, RenderView view, Entity sun, Vector3f lightDirection,
                                                  Map<Entity, Posed> animated) {
        int size = settings.shadows.mapSize;
        Light light = sun == null ? null : sun.get(Light.class);
        float strength = light == null ? DEFAULT_SHADOW_STRENGTH : (float) light.shadowStrength;
        boolean lit = settings.shading == RenderSettings.Shading.LIT || settings.shading == RenderSettings.Shading.TOON;
        if (size == 0 || !lit || strength <= 0 || (light != null && !light.shadows) || lightDirection.lengthSquared() < 1e-8f) return null;

        // a square area around what the camera sees, looked at straight along the light
        float radius = (float) settings.shadowDistance / 2;
        Vector3f direction = new Vector3f(lightDirection).normalize();
        boolean straightDown = Math.abs(direction.y) > 0.99f;
        lightView.setLookAt(0, 0, 0, direction.x, direction.y, direction.z, 0, straightDown ? 0 : 1, straightDown ? 1 : 0);
        Vector3f center = lightView.transformPosition(shadowAreaCenter(view, radius));
        // moved only in whole shadow map pixels, so shadow edges don't shimmer as the camera moves
        float texel = radius * 2 / size;
        center.x = (float) Math.floor(center.x / texel) * texel;
        center.y = (float) Math.floor(center.y / texel) * texel;
        shadowCenter.set(center);
        shadowRadius = radius;

        Matrix4f matrix = new Matrix4f()
                .ortho(center.x - radius, center.x + radius, center.y - radius, center.y + radius,
                        -center.z - radius - SHADOW_REACH, -center.z + radius)
                .mul(lightView);
        gpu.beginShadows(matrix, size);
        drawMeshes(scene, animated, true);
        gpu.endShadows();
        return new GraphicsBackend.Shadows(matrix, Math.min(1, strength), texel);
    }

    /** Where the shadow area is centered: ahead of the camera, or for flat views, the ground they look down at. */
    private Vector3f shadowAreaCenter(RenderView view, float radius) {
        Vector3f forward = view.view().invert(new Matrix4f()).transformDirection(new Vector3f(0, 0, -1)).normalize();
        if (view.orthographic() && forward.y < -0.05f) return new Vector3f(forward).mul(-eye.y / forward.y).add(eye);
        return new Vector3f(forward).mul(radius * 0.8f).add(eye);
    }

    /** True if something at (x, y, z), 'reach' across, can't cast a shadow into this frame's shadow area. */
    private boolean outsideShadowArea(float x, float y, float z, float reach) {
        Vector3f p = lightView.transformPosition(new Vector3f(x, y, z));
        float side = shadowRadius + reach;
        return Math.abs(p.x - shadowCenter.x) > side || Math.abs(p.y - shadowCenter.y) > side
                || p.z < shadowCenter.z - side || p.z > shadowCenter.z + SHADOW_REACH + reach;
    }

    /** A solid surface: lit in the scene, or a shadow caster while drawing the shadow map. */
    private void surface(GpuMesh mesh, GpuTexture texture, Matrix4f matrix, Color color, boolean shadowPass) {
        if (shadowPass) gpu.drawShadowCaster(mesh, texture, matrix);
        else gpu.drawLit(mesh, texture, matrix, color);
    }

    private static Vector3f toVector(Vec3 v) {
        return new Vector3f((float) v.x, (float) v.y, (float) v.z);
    }

    // ---------------------------------------------------------------- meshes

    private Vector3f eye = new Vector3f();

    /** How far things are drawn: just past where the fog hides everything, or (without fog) very far. */
    private float drawDistance() {
        return settings.fog ? (float) settings.fogEnd + 5 : 2000;
    }

    /** Every solid thing: in the scene, or (shadowPass) into the shadow map, skipping what can't cast into it. */
    private void drawMeshes(Scene scene, Map<Entity, Posed> animated, boolean shadowPass) {
        float far = drawDistance();
        for (Entity entity : scene.entities()) {
            Terrain terrain = entity.get(Terrain.class);
            if (entity.active && terrain != null) drawTerrain(entity, terrain, far, shadowPass);
            ParticleEmitter emitter = entity.get(ParticleEmitter.class);
            if (!shadowPass && entity.active && emitter != null && emitter.look == ParticleEmitter.Look.MODEL) drawModelParticles(emitter); // solid, so drawn with the meshes
            MeshRenderer renderer = entity.get(MeshRenderer.class);
            if (!entity.active || renderer == null || (shadowPass && !renderer.castShadows)) continue;
            Model shape = model(renderer.mesh);
            Vec3 p = entity.transform.position;
            float reach = reach(entity, shape);
            if (shadowPass ? outsideShadowArea((float) p.x, (float) p.y, (float) p.z, reach)
                    : settings.fog && eye.distance((float) p.x, (float) p.y, (float) p.z) - reach > far) continue; // hidden by the fog anyway
            entity.transform.matrix(model).rotateY((float) Math.toRadians(renderer.turn));
            // the renderer's texture (if set) replaces the model's own; its color tints every part
            GpuTexture override = assets.texture(renderer.texture, false);
            Posed character = animated.get(entity);
            List<GpuMesh> posed = character != null && character.model == shape ? character.meshes : null;
            for (int i = 0; i < shape.parts.size(); i++) {
                Model.Part part = shape.parts.get(i);
                GpuMesh mesh = posed != null && posed.get(i) != null ? posed.get(i) : part.mesh();
                surface(mesh, override != null ? override : part.texture(), model, multiply(renderer.color, part.color()), shadowPass);
            }
        }
    }

    /** How far an entity's model can stick out from its position. */
    private static float reach(Entity entity, Model shape) {
        Vec3 s = entity.transform.scale;
        Vector3f from = new Vector3f(shape.boundsMin), to = new Vector3f(shape.boundsMax);
        float corner = Math.max(from.length(), to.length()); // models needn't be centered on their position
        return corner * (float) Math.max(Math.abs(s.x), Math.max(Math.abs(s.y), Math.abs(s.z)));
    }

    // ---------------------------------------------------------------- terrain

    /** A terrain's mesh and colour map, made for one set of its settings. */
    private record TerrainLook(String key, GpuMesh mesh, GpuTexture texture) {
        void close() {
            mesh.close();
            texture.close();
        }
    }

    private final Map<Entity, TerrainLook> terrainLooks = new IdentityHashMap<>();
    private static final Color TRUNK = new Color(0x6B4F3A);
    private static final Color LEAVES = new Color(0x3F7A3C);

    private void drawTerrain(Entity entity, Terrain terrain, float far, boolean shadowPass) {
        String key = terrain.shapeKey() + "|" + terrain.sand + terrain.grass + terrain.rock + terrain.snow;
        TerrainLook look = terrainLooks.get(entity);
        if (look == null || !look.key().equals(key)) {
            if (look != null) look.close();
            look = new TerrainLook(key, gpu.mesh(terrainMesh(terrain)), gpu.texture(terrainColours(terrain), false));
            terrainLooks.put(entity, look);
        }
        Vec3 p = entity.transform.position;
        model.identity().translate((float) p.x, (float) p.y, (float) p.z);
        surface(look.mesh(), shadowPass ? null : look.texture(), model, Color.WHITE, shadowPass); // (the colour map has no see-through parts)

        // trees: a trunk and a round top, skipped when out of sight in the distance (or of the shadows)
        GpuMesh trunk = builtIn.get("cylinder"), top = builtIn.get("sphere");
        for (double[] tree : terrain.trees()) {
            float x = (float) (p.x + tree[0]), y = (float) (p.y + tree[2]), z = (float) (p.z + tree[1]), s = (float) tree[3];
            if (shadowPass ? outsideShadowArea(x, y + 2 * s, z, 3 * s) : eye.distance(x, y, z) > far) continue;
            model.identity().translate(x, y + 1.1f * s, z).scale(0.35f * s, 2.2f * s, 0.35f * s);
            surface(trunk, null, model, TRUNK, shadowPass);
            model.identity().translate(x, y + 3.0f * s, z).scale(2.4f * s, 2.8f * s, 2.4f * s);
            surface(top, null, model, LEAVES, shadowPass);
        }
    }

    /** The land as triangles: two per square, the same way Terrain works out heights between corners. */
    private static MeshData terrainMesh(Terrain terrain) {
        float[] h = terrain.heights();
        int n = Math.max(2, terrain.resolution);
        float size = (float) terrain.size, cell = size / n;
        MeshData.Builder b = new MeshData.Builder();
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (int iz = 0; iz <= n; iz++) {
            for (int ix = 0; ix <= n; ix++) {
                float y = h[iz * (n + 1) + ix];
                low = Math.min(low, y);
                high = Math.max(high, y);
                float left = h[iz * (n + 1) + Math.max(0, ix - 1)], right = h[iz * (n + 1) + Math.min(n, ix + 1)];
                float back = h[Math.max(0, iz - 1) * (n + 1) + ix], front = h[Math.min(n, iz + 1) * (n + 1) + ix];
                Vector3f normal = new Vector3f(left - right, 2 * cell, back - front).normalize();
                b.vertex(((float) ix / n - 0.5f) * size, y, ((float) iz / n - 0.5f) * size, normal.x, normal.y, normal.z, (float) ix / n, (float) iz / n);
            }
        }
        for (int iz = 0; iz < n; iz++) {
            for (int ix = 0; ix < n; ix++) {
                int a = iz * (n + 1) + ix, right = a + 1, down = a + n + 1, both = down + 1;
                b.triangle(a, down, right);
                b.triangle(both, right, down);
            }
        }
        return b.build(false, new Vector3f(-size / 2, low, -size / 2), new Vector3f(size / 2, high, size / 2));
    }

    /**
     * The land's colours (sand, grass, rock, snow by height and steepness), with a little grain.
     * Read straight from the height grid: this is redone every frame while the land is sculpted.
     */
    private static BufferedImage terrainColours(Terrain terrain) {
        int pixels = 256;
        float[] h = terrain.heights();
        int n = Math.max(2, terrain.resolution);
        double cell = terrain.size / n, top = Math.max(0.001, terrain.height);
        BufferedImage image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB);
        java.util.Random grain = new java.util.Random(terrain.seed);
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                double gx = (px + 0.5) / pixels * n, gz = (py + 0.5) / pixels * n;
                double hh = gridHeight(h, n, gx, gz);
                double dx = gridHeight(h, n, gx + 1, gz) - gridHeight(h, n, gx - 1, gz);
                double dz = gridHeight(h, n, gx, gz + 1) - gridHeight(h, n, gx, gz - 1);
                double steepness = Math.min(1, Math.hypot(dx, dz) / (2 * cell));
                Color colour = terrain.colorAt(hh / top, steepness);
                double shade = 0.94 + grain.nextDouble() * 0.12;
                int r = (int) Math.min(255, colour.getRed() * shade), g = (int) Math.min(255, colour.getGreen() * shade), bl = (int) Math.min(255, colour.getBlue() * shade);
                image.setRGB(px, py, r << 16 | g << 8 | bl);
            }
        }
        return image;
    }

    /** The height at grid position (gx, gz) (0..n across), blended between corners; clamped at the edges. */
    private static double gridHeight(float[] h, int n, double gx, double gz) {
        gx = Math.max(0, Math.min(n, gx));
        gz = Math.max(0, Math.min(n, gz));
        int ix = Math.min(n - 1, (int) gx), iz = Math.min(n - 1, (int) gz);
        double fx = gx - ix, fz = gz - iz;
        double near = h[iz * (n + 1) + ix] * (1 - fx) + h[iz * (n + 1) + ix + 1] * fx;
        double far = h[(iz + 1) * (n + 1) + ix] * (1 - fx) + h[(iz + 1) * (n + 1) + ix + 1] * fx;
        return near * (1 - fz) + far * fz;
    }

    // ---------------------------------------------------------------- animated models

    /** An animated entity's own copy of its model's meshes, posed every frame. */
    private static final class Posed {
        final Model model;
        final List<GpuMesh> meshes = new ArrayList<>(); // null for parts that don't move
        final List<float[]> vertices = new ArrayList<>();
        final Matrix4f[] joints;
        long usedAt;
        // ragdolls: every node of the model as drawn last frame, as it was when it went limp, and as it is now
        final Matrix4f[] lastNodes;
        final Matrix4f[] limpStart;
        final Matrix4f[] nodes;
        final Quaternionf[] groupTurns = {new Quaternionf(), new Quaternionf()};
        int fall = 0;
        boolean limp = false;
        boolean posedBefore = false;
        // getting up: the last limp pose (in the world), blended out over the Ragdoll's blend time
        final Matrix4f[] limpWorld;
        boolean gettingUp = false;
        long gotUpAt;

        Posed(Model model, GraphicsBackend gpu) {
            this.model = model;
            joints = new Matrix4f[model.rig.jointCount()];
            for (int j = 0; j < joints.length; j++) joints[j] = new Matrix4f();
            lastNodes = matrices(model.rig.nodeCount());
            limpStart = matrices(model.rig.nodeCount());
            nodes = matrices(model.rig.nodeCount());
            limpWorld = matrices(model.rig.nodeCount());
            for (Model.Part part : model.parts) {
                boolean skinned = part.source() != null && part.source().skin() != null;
                meshes.add(skinned ? gpu.dynamicMesh(part.source().mesh()) : null);
                vertices.add(skinned ? part.source().mesh().vertices().clone() : null);
            }
        }

        void close() {
            for (GpuMesh mesh : meshes) if (mesh != null) mesh.close();
        }

        private static Matrix4f[] matrices(int count) {
            Matrix4f[] m = new Matrix4f[count];
            for (int i = 0; i < count; i++) m[i] = new Matrix4f();
            return m;
        }
    }

    /** Which bones each rigged model uses as a ragdoll (an empty Optional if it can't be one). */
    private final Map<Rig, java.util.Optional<RagdollPose>> ragdollBones = new IdentityHashMap<>();

    private RagdollPose ragdollBones(Rig rig) {
        return ragdollBones.computeIfAbsent(rig, r -> java.util.Optional.ofNullable(RagdollPose.of(r))).orElse(null);
    }

    private final Map<Entity, Posed> posed = new IdentityHashMap<>();

    /**
     * Poses the model as the Animator says (blending two animations while it fades between them),
     * or, while its Ragdoll is limp, so its bones follow the simulated ones: works out its joint
     * matrices (poseCharacters then moves the vertices).
     */
    private Posed posedMeshes(Entity entity, Animator animator, Ragdoll ragdoll, Model shape, Matrix4f toWorld) {
        Rig rig = shape.rig;
        if (animator != null && animator.available().isEmpty() && !rig.clips.isEmpty()) {
            Map<String, Double> lengths = new LinkedHashMap<>();
            for (Rig.Clip clip : rig.clips) lengths.put(clip.name(), (double) clip.duration());
            animator.setClips(rig.clipNames(), lengths);
        }
        Posed instance = posed.get(entity);
        if (instance == null || instance.model != shape) {
            if (instance != null) instance.close();
            instance = new Posed(shape, gpu);
            posed.put(entity, instance);
        }
        instance.usedAt = renderCount;

        RagdollPose bones = ragdoll == null ? null : ragdollBones(rig);
        if (bones != null && ragdoll.active && ragdoll.points != null && instance.posedBefore) {
            if (ragdoll.fall != instance.fall) {
                // a new fall: start from the pose it was in (the one the physics started from)
                instance.fall = ragdoll.fall;
                for (int i = 0; i < instance.lastNodes.length; i++) instance.limpStart[i].set(instance.lastNodes[i]);
                for (Quaternionf turn : instance.groupTurns) turn.identity();
            }
            bones.apply(instance.limpStart, ragdoll.points, toWorld.invert(new Matrix4f()), instance.groupTurns, instance.nodes);
            instance.limp = true;
            rig.skin(instance.nodes, instance.joints);
        } else {
            Matrix4f[] nodes = animate(rig, animator);
            if (instance.limp) {
                // just got up: blend from the limp pose into the animation
                instance.limp = false;
                instance.gettingUp = true;
                instance.gotUpAt = System.nanoTime();
                for (int i = 0; i < nodes.length; i++) toWorld.mul(instance.nodes[i], instance.limpWorld[i]);
            }
            double blendOut = ragdoll == null ? 0 : ragdoll.blendOut;
            double sinceUp = (System.nanoTime() - instance.gotUpAt) / 1e9;
            if (instance.gettingUp && sinceUp >= blendOut) instance.gettingUp = false;
            if (instance.gettingUp) {
                blendFromLimp(instance, nodes, toWorld.invert(new Matrix4f()), (float) Easing.apply("inOutQuad", sinceUp / blendOut));
                nodes = instance.nodes;
            }
            if (bones != null) {
                if (ragdoll.skeleton == null) ragdoll.skeleton = bones.skeleton;
                if (ragdoll.pose == null) ragdoll.pose = new double[bones.boneNode.length * 3];
                bones.capture(nodes, toWorld, ragdoll.pose);
                ragdoll.poseOrigin.set(entity.transform.position);
                Vector3f facing = toWorld.transformDirection(new Vector3f(0, 0, 1)).normalize(); // glTF characters face +Z
                ragdoll.poseForward.set(facing.x, facing.y, facing.z);
                for (int i = 0; i < nodes.length; i++) instance.lastNodes[i].set(nodes[i]);
                instance.posedBefore = true;
            }
            rig.skin(nodes, instance.joints);
        }
        return instance;
    }

    /** One skinned part of one character, to be moved to its pose. */
    private record SkinJob(ModelData.Skin skin, Matrix4f[] joints, float[] vertices) { }

    /**
     * Poses every animated character (one after another: models share their working space),
     * then moves all their vertices at once on every CPU core (the slow part with a crowd),
     * then hands the new vertices to the GPU.
     */
    private Map<Entity, Posed> poseCharacters(Scene scene) {
        Map<Entity, Posed> animated = new IdentityHashMap<>();
        List<SkinJob> jobs = new ArrayList<>();
        Matrix4f toWorld = new Matrix4f();
        for (Entity entity : scene.entities()) {
            MeshRenderer renderer = entity.get(MeshRenderer.class);
            if (!entity.active || renderer == null) continue;
            Animator animator = entity.get(Animator.class);
            Ragdoll ragdoll = entity.get(Ragdoll.class);
            if (animator == null && ragdoll == null) continue;
            Model shape = model(renderer.mesh);
            if (shape.rig == null) continue;
            entity.transform.matrix(toWorld).rotateY((float) Math.toRadians(renderer.turn));
            Posed instance = posedMeshes(entity, animator, ragdoll, shape, new Matrix4f(toWorld));
            animated.put(entity, instance);
            for (int i = 0; i < shape.parts.size(); i++) {
                if (instance.meshes.get(i) != null) jobs.add(new SkinJob(shape.parts.get(i).source().skin(), instance.joints, instance.vertices.get(i)));
            }
        }
        if (jobs.size() > 1) jobs.parallelStream().forEach(job -> Skinning.apply(job.skin(), job.joints(), job.vertices()));
        else jobs.forEach(job -> Skinning.apply(job.skin(), job.joints(), job.vertices()));
        for (Posed instance : animated.values()) {
            for (int i = 0; i < instance.meshes.size(); i++) {
                if (instance.meshes.get(i) != null) gpu.updateMesh(instance.meshes.get(i), instance.vertices.get(i));
            }
        }
        return animated;
    }

    /** Every node of the model, posed as the Animator says (the rest pose without one). */
    private static Matrix4f[] animate(Rig rig, Animator animator) {
        if (animator == null) {
            rig.poseNodes(null, 0, null, 0, 0);
            return rig.nodes();
        }
        Rig.Clip current = rig.clip(animator.current());
        Rig.Clip previous = animator.previous() == null ? null : rig.clip(animator.previous());
        if (previous != null) {
            rig.poseNodes(previous, (float) animator.previousTime(), current, (float) animator.time(), (float) animator.blend());
        } else {
            rig.poseNodes(current, (float) animator.time(), null, 0, 0);
        }
        return rig.nodes();
    }

    /** Mixes the last limp pose with the animated one into instance.nodes: 0 = limp, 1 = animated. */
    private static void blendFromLimp(Posed instance, Matrix4f[] animated, Matrix4f worldToModel, float amount) {
        Vector3f limpAt = new Vector3f(), animAt = new Vector3f(), scale = new Vector3f();
        Quaternionf limpTurn = new Quaternionf(), animTurn = new Quaternionf();
        Matrix4f limp = new Matrix4f();
        for (int i = 0; i < animated.length; i++) {
            worldToModel.mul(instance.limpWorld[i], limp);
            limp.getTranslation(limpAt);
            limp.getNormalizedRotation(limpTurn);
            animated[i].getTranslation(animAt);
            animated[i].getNormalizedRotation(animTurn);
            animated[i].getScale(scale);
            instance.nodes[i].translationRotateScale(limpAt.lerp(animAt, amount), limpTurn.slerp(animTurn, amount), scale);
        }
    }

    /** Frees the posed meshes of entities that are gone (deleted, or from a finished Play session). */
    private void forgetUnusedPoses() {
        posed.entrySet().removeIf(entry -> {
            boolean stale = renderCount - entry.getValue().usedAt > 120;
            if (stale) entry.getValue().close();
            return stale;
        });
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
        forgetUnusedPoses();
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
            matrix.rotateY((float) Math.toRadians(meshRenderer.turn));
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

    /** Every collider as wire lines: green for solid ones, yellow for triggers. */
    private void drawColliders(Scene scene) {
        for (Entity entity : scene.entities()) {
            if (!entity.active) continue;
            BoxCollider box = entity.get(BoxCollider.class);
            if (box != null) {
                entity.transform.matrix(model)
                        .translate((float) box.offset.x, (float) box.offset.y, (float) box.offset.z)
                        .scale((float) box.size.x, (float) box.size.y, (float) box.size.z);
                gpu.drawLines(wireCube, model, box.trigger ? TRIGGER_COLOR : Theme.SUCCESS, 1, 1e6f, 2e6f, false);
            }
            SphereCollider sphere = entity.get(SphereCollider.class);
            if (sphere != null) {
                Vector3f center = colliderCenter(entity, sphere.offset);
                Vec3 s = entity.transform.scale;
                float diameter = (float) (sphere.radius * 2 * Math.max(Math.abs(s.x), Math.max(Math.abs(s.y), Math.abs(s.z))));
                model.identity().translate(center).mul(rotationOf(entity)).scale(diameter);
                gpu.drawLines(wireSphere, model, sphere.trigger ? TRIGGER_COLOR : Theme.SUCCESS, 1, 1e6f, 2e6f, false);
            }
            CapsuleCollider capsule = entity.get(CapsuleCollider.class);
            if (capsule != null) drawCapsule(entity, capsule);
        }
    }

    /** A capsule outline: a ball at each end, joined by four lines. */
    private void drawCapsule(Entity entity, CapsuleCollider capsule) {
        Vec3 s = entity.transform.scale;
        float radius = (float) (capsule.radius * Math.max(Math.abs(s.x), Math.abs(s.z)));
        float half = (float) Math.max(0, capsule.height * Math.abs(s.y) / 2 - radius);
        Vector3f center = colliderCenter(entity, capsule.offset);
        Matrix4f rotation = rotationOf(entity);
        Vector3f up = rotation.transformDirection(new Vector3f(0, half, 0));
        Color color = capsule.trigger ? TRIGGER_COLOR : Theme.SUCCESS;
        for (int end = -1; end <= 1; end += 2) {
            model.identity().translate(new Vector3f(up).mul(end).add(center)).mul(rotation).scale(radius * 2);
            gpu.drawLines(wireSphere, model, color, 1, 1e6f, 2e6f, false);
        }
        model.identity().translate(center).mul(rotation).scale(radius * 2, half * 2, radius * 2);
        gpu.drawLines(wireCapsuleSides, model, color, 1, 1e6f, 2e6f, false);
    }

    /** What a selected NPC notices: its view (purple wedge) and how far it hears (faint circle). */
    private void drawSenses(Entity entity, Npc npc) {
        Vec3 p = entity.transform.position;
        int angle = (int) Math.max(1, Math.min(360, Math.round(npc.sightAngle)));
        float range = (float) npc.sightRange;
        model.identity().translate((float) p.x, (float) p.y, (float) p.z)
                .rotateY((float) Math.toRadians(entity.transform.rotation.y)).scale(range, 1, range);
        gpu.drawLines(fan(angle), model, Theme.ACCENT, 1, 1e6f, 2e6f, false);
        float hearing = (float) npc.hearing;
        model.identity().translate((float) p.x, (float) p.y, (float) p.z).scale(hearing, 1, hearing);
        gpu.drawLines(fan(360), model, Theme.TEXT_MUTED, 0.5f, 1e6f, 2e6f, false);
    }

    private GpuMesh fan(int degrees) {
        return fans.computeIfAbsent(degrees, d -> gpu.mesh(MeshData.wireFan(d)));
    }

    private static Vector3f colliderCenter(Entity entity, Vec3 offset) {
        return entity.transform.matrix(new Matrix4f()).transformPosition(new Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
    }

    private static Matrix4f rotationOf(Entity entity) {
        return entity.transform.matrixWithoutScale(new Matrix4f()).setTranslation(0, 0, 0);
    }

    @Override
    public void close() {
        builtIn.values().forEach(GpuMesh::close);
        skyTextures.values().forEach(GpuTexture::close);
        quad.close();
        grid.close();
        wireCube.close();
        wireSphere.close();
        wireCapsuleSides.close();
        fans.values().forEach(GpuMesh::close);
        terrainLooks.values().forEach(TerrainLook::close);
        hudPainter.close();
        softDot.close();
        tileMeshes.values().forEach(t -> t.mesh().close());
        posed.values().forEach(Posed::close);
        assets.close();
        gpu.close();
    }
}
