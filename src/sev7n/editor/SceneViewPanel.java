package sev7n.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.extension.imguizmo.ImGuizmo;
import imgui.extension.imguizmo.flag.Mode;
import imgui.extension.imguizmo.flag.Operation;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import org.joml.Intersectionf;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import sev7n.math.Vec3;
import sev7n.render.Framebuffer;
import sev7n.render.RenderView;
import sev7n.render.SceneRenderer;
import sev7n.scene.Camera;
import sev7n.scene.Entity;
import sev7n.scene.Terrain;
import sev7n.scene.Tilemap;
import sev7n.ui.Theme;

/**
 * The editor's view of the scene, in 3D or flat 2D (the 2D button).
 *   click to select · W / E / R = move / rotate / scale gizmo (hold Ctrl to snap)
 *   3D: right-drag or Alt+left-drag = orbit · middle-drag = pan · wheel = zoom · F = focus
 *   2D: right- or middle-drag = pan · wheel = zoom
 *   while painting a tilemap: click/drag = paint · Shift = erase · Esc = stop
 *   while sculpting terrain: drag = brush · Shift = lower · Ctrl = smooth · [ ] = brush size · Esc = stop
 */
final class SceneViewPanel {

    static final String TITLE = "Scene";
    private static final float ICON_RADIUS = 9;

    private final EditorContext ctx;
    private final EditorCamera camera = new EditorCamera();
    private final Framebuffer framebuffer = new Framebuffer();
    private final Matrix4f matrix = new Matrix4f();
    private final float[] viewArray = new float[16];
    private final float[] projectionArray = new float[16];
    private final float[] modelArray = new float[16];
    private final float[] deltaArray = new float[16];

    private int gizmoOperation = Operation.TRANSLATE;
    private int gizmoMode = Mode.LOCAL;
    private boolean navigating = false;
    private boolean focused = false;
    private boolean startedUp = false;

    SceneViewPanel(EditorContext ctx) {
        this.ctx = ctx;
    }

    boolean focused() {
        return focused;
    }

    void focusSelection() {
        Entity selected = ctx.selected();
        if (selected != null) {
            camera.focus(new Vector3f((float) selected.transform.position.x,
                    (float) selected.transform.position.y, (float) selected.transform.position.z));
        }
    }

    void draw() {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        boolean visible = ImGui.begin(TITLE, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        if (!visible) {
            focused = false;
            ImGui.end();
            return;
        }
        focused = ImGui.isWindowFocused();
        if (!startedUp) {
            // open in 2D if the game's camera is a 2D one
            Entity gameCamera = ctx.scene().mainCamera();
            camera.setFlat(gameCamera != null && gameCamera.get(Camera.class).orthographic);
            startedUp = true;
        }

        ImVec2 origin = ImGui.getCursorScreenPos();
        float width = ImGui.getContentRegionAvailX();
        float height = ImGui.getContentRegionAvailY();
        if (width < 2 || height < 2) {
            ImGui.end();
            return;
        }

        RenderView view = camera.view(width / height);
        framebuffer.resize((int) width, (int) height, ctx.graphics.antiAliasing, false);
        framebuffer.bind();
        EditorSettings prefs = ctx.editorSettings;
        ctx.renderer.render(ctx.scene(), view, Theme.EDITOR, new SceneRenderer.Overlays(prefs.showGrid, ctx.selected(), prefs.showColliders, false));
        framebuffer.end();

        ImGui.image(framebuffer.texture(), width, height, 0, 1, 1, 0); // GL textures are upside down
        boolean hovered = ImGui.isItemHovered();
        acceptDroppedAsset(origin, width, height, view);

        drawEntityIcons(origin, width, height, view);
        drawToolbar(origin);
        Entity tilemap = ctx.paintingTilemap();
        Entity terrain = ctx.sculptingTerrain();
        boolean gizmoBusy = tilemap == null && terrain == null && drawGizmo(origin, width, height, view);
        handleNavigation(hovered, height);
        if (tilemap != null) {
            paintTiles(tilemap, hovered, origin, width, height, view);
        } else if (terrain != null) {
            sculptTerrain(terrain, hovered, origin, width, height, view);
        } else if (hovered && !gizmoBusy && !navigating && !ImGui.isAnyItemHovered()
                && ImGui.isMouseClicked(0) && !ImGui.getIO().getKeyAlt()) {
            ImVec2 mouse = ImGui.getMousePos();
            ctx.select(pick(mouse.x - origin.x, mouse.y - origin.y, width, height, view));
        }
        if ((focused || hovered) && !ImGui.getIO().getWantTextInput()) handleShortcuts();

        ImGui.end();
    }

    // ---------------------------------------------------------------- toolbar & shortcuts

    private void drawToolbar(ImVec2 origin) {
        ImGui.setCursorScreenPos(origin.x + 8, origin.y + 8);
        toolButton("Move", Operation.TRANSLATE, "W");
        ImGui.sameLine();
        toolButton("Rotate", Operation.ROTATE, "E");
        ImGui.sameLine();
        toolButton("Scale", Operation.SCALE, "R");
        ImGui.sameLine(0, 12);
        if (ImGui.button(gizmoMode == Mode.LOCAL ? "Local" : "World")) {
            gizmoMode = gizmoMode == Mode.LOCAL ? Mode.WORLD : Mode.LOCAL;
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Gizmo space: the object's own axes, or the world's");
        ImGui.sameLine(0, 12);
        boolean flat = camera.flat();
        if (flat) pushAccent();
        if (ImGui.button("2D")) camera.setFlat(!flat);
        if (flat) ImGui.popStyleColor(3);
        if (ImGui.isItemHovered()) ImGui.setTooltip(flat ? "Back to the 3D view" : "Look straight at the X/Y plane, flat: for 2D games");
    }

    private static void pushAccent() {
        ImGui.pushStyleColor(ImGuiCol.Button, EditorStyle.pack(Theme.ACCENT));
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, EditorStyle.pack(Theme.ACCENT_HOVER));
        ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(Theme.BLACK));
    }

    private void toolButton(String label, int operation, String key) {
        boolean active = gizmoOperation == operation;
        if (active) pushAccent();
        if (ImGui.button(label)) gizmoOperation = operation;
        if (active) ImGui.popStyleColor(3);
        if (ImGui.isItemHovered()) ImGui.setTooltip(label + " (" + key + ")");
    }

    private void handleShortcuts() {
        if (ImGui.getIO().getKeyCtrl()) return;
        if (ImGui.isKeyPressed(ImGuiKey.W, false)) gizmoOperation = Operation.TRANSLATE;
        if (ImGui.isKeyPressed(ImGuiKey.E, false)) gizmoOperation = Operation.ROTATE;
        if (ImGui.isKeyPressed(ImGuiKey.R, false)) gizmoOperation = Operation.SCALE;
        if (ImGui.isKeyPressed(ImGuiKey.F, false)) focusSelection();
    }

    // ---------------------------------------------------------------- camera

    private void handleNavigation(boolean hovered, float height) {
        ImGuiIO io = ImGui.getIO();
        boolean altOrbit = io.getKeyAlt() && ImGui.isMouseClicked(0);
        if (hovered && (ImGui.isMouseClicked(1) || ImGui.isMouseClicked(2) || altOrbit)) navigating = true;
        if (!ImGui.isMouseDown(0) && !ImGui.isMouseDown(1) && !ImGui.isMouseDown(2)) navigating = false;

        if (navigating) {
            float dx = io.getMouseDeltaX();
            float dy = io.getMouseDeltaY();
            boolean orbitButton = ImGui.isMouseDown(1) || (io.getKeyAlt() && ImGui.isMouseDown(0));
            if (orbitButton && !camera.flat()) camera.orbit(dx, dy);
            else if (orbitButton || ImGui.isMouseDown(2)) camera.pan(dx, dy, height); // 2D has no orbit: every drag pans
        }
        if (hovered && io.getMouseWheel() != 0) camera.zoom(io.getMouseWheel());
    }

    // ---------------------------------------------------------------- gizmo

    /** Draws the move/rotate/scale handles. Returns true while the mouse is over or using them. */
    private boolean drawGizmo(ImVec2 origin, float width, float height, RenderView view) {
        Entity selected = ctx.selected();
        if (selected == null) return false;

        ImGuizmo.setOrthographic(view.orthographic());
        ImGuizmo.setDrawList();
        ImGuizmo.setRect(origin.x, origin.y, width, height);
        view.view().get(viewArray);
        view.projection().get(projectionArray);
        selected.transform.matrix(matrix).get(modelArray);

        if (ImGui.getIO().getKeyCtrl()) {
            float step = switch (gizmoOperation) {
                case Operation.TRANSLATE -> 0.5f;
                case Operation.ROTATE -> 15f;
                default -> 0.1f;
            };
            ImGuizmo.manipulate(viewArray, projectionArray, gizmoOperation, gizmoMode, modelArray, deltaArray,
                    new float[] {step, step, step});
        } else {
            ImGuizmo.manipulate(viewArray, projectionArray, gizmoOperation, gizmoMode, modelArray);
        }

        if (ImGuizmo.isUsing()) {
            // only change what this tool edits, so e.g. moving never rewrites the rotation numbers
            Matrix4f edited = new Matrix4f().set(modelArray);
            switch (gizmoOperation) {
                case Operation.TRANSLATE -> {
                    Vector3f position = edited.getTranslation(new Vector3f());
                    selected.transform.position.set(position.x, position.y, position.z);
                }
                case Operation.ROTATE -> selected.transform.setRotationFromMatrix(edited);
                default -> {
                    Vector3f scale = edited.getScale(new Vector3f());
                    selected.transform.scale.set(scale.x, scale.y, scale.z);
                }
            }
            ctx.markDirty();
            return true;
        }
        return ImGuizmo.isOver();
    }

    // ---------------------------------------------------------------- dropping assets

    /** A picture or model dragged from the Project panel lands where the mouse points on the ground. */
    private void acceptDroppedAsset(ImVec2 origin, float width, float height, RenderView view) {
        if (!ImGui.beginDragDropTarget()) return;
        String asset = ImGui.acceptDragDropPayload(DragTypes.ASSET, String.class);
        if (asset != null) {
            ImVec2 mouse = ImGui.getMousePos();
            float x = mouse.x - origin.x;
            float y = mouse.y - origin.y;
            if (camera.flat()) ctx.createFromAsset(asset, flatPoint(x, y, width, height, view), false);
            else ctx.createFromAsset(asset, groundPoint(x, y, width, height, view));
        }
        ImGui.endDragDropTarget();
    }

    /** Where the mouse points on the 2D plane (z = 0) in the flat view. */
    private static Vec3 flatPoint(float mouseX, float mouseY, float width, float height, RenderView view) {
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        new Matrix4f(view.projection()).mul(view.view()).unprojectRay(mouseX, height - mouseY,
                new int[] {0, 0, (int) width, (int) height}, origin, direction);
        return new Vec3(origin.x, origin.y, 0);
    }

    // ---------------------------------------------------------------- tile painting

    /** Paints (or with Shift, erases) the tile under the mouse while the button is held. */
    private void paintTiles(Entity entity, boolean hovered, ImVec2 origin, float width, float height, RenderView view) {
        Tilemap map = entity.get(Tilemap.class);
        drawBanner(origin, width, "Painting tiles on " + entity.name + "  ·  Shift erases  ·  Esc stops");
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            ctx.paintTiles(null);
            return;
        }
        if (!hovered || navigating) return;

        ImVec2 mouse = ImGui.getMousePos();
        int[] cell = cellUnderMouse(entity, map, mouse.x - origin.x, mouse.y - origin.y, width, height, view);
        if (cell == null) return;
        boolean erase = ImGui.getIO().getKeyShift() || ctx.paintTile() == Tilemap.EMPTY;
        outlineCell(entity, map, cell, origin, width, height, view, erase);

        if (ImGui.isMouseDown(0) && !ImGui.getIO().getKeyAlt() && !ImGui.isAnyItemHovered()) {
            int before = map.version();
            map.setTile(cell[0], cell[1], erase ? Tilemap.EMPTY : ctx.paintTile());
            if (map.version() != before) ctx.markDirty();
        }
    }

    /** The cell of the tilemap's plane under the mouse, as {x, y}, or null if the plane faces away. */
    private static int[] cellUnderMouse(Entity entity, Tilemap map, float mouseX, float mouseY,
                                        float width, float height, RenderView view) {
        Vector3f rayOrigin = new Vector3f();
        Vector3f rayDirection = new Vector3f();
        new Matrix4f(view.projection()).mul(view.view()).unprojectRay(mouseX, height - mouseY,
                new int[] {0, 0, (int) width, (int) height}, rayOrigin, rayDirection);
        Matrix4f inverse = entity.transform.matrix(new Matrix4f()).invert();
        Vector3f o = inverse.transformPosition(rayOrigin, new Vector3f());
        Vector3f d = inverse.transformDirection(rayDirection, new Vector3f());
        if (Math.abs(d.z) < 1e-6f) return null;
        float t = -o.z / d.z;
        if (t < 0) return null;
        float s = (float) map.tileSize;
        return new int[] {(int) Math.floor((o.x + d.x * t) / s), (int) Math.floor((o.y + d.y * t) / s)};
    }

    private void outlineCell(Entity entity, Tilemap map, int[] cell, ImVec2 origin, float width, float height,
                             RenderView view, boolean erase) {
        Matrix4f toScreen = new Matrix4f(view.projection()).mul(view.view()).mul(entity.transform.matrix(new Matrix4f()));
        float s = (float) map.tileSize;
        float[][] corners = {{cell[0], cell[1]}, {cell[0] + 1, cell[1]}, {cell[0] + 1, cell[1] + 1}, {cell[0], cell[1] + 1}};
        float[] screen = new float[8];
        for (int i = 0; i < 4; i++) {
            Vector4f clip = toScreen.transform(new Vector4f(corners[i][0] * s, corners[i][1] * s, 0, 1));
            if (clip.w <= 0.01f) return;
            screen[i * 2] = origin.x + (clip.x / clip.w * 0.5f + 0.5f) * width;
            screen[i * 2 + 1] = origin.y + (1 - (clip.y / clip.w * 0.5f + 0.5f)) * height;
        }
        int color = EditorStyle.pack(erase ? Theme.ERROR : Theme.ACCENT);
        ImGui.getWindowDrawList().addQuad(screen[0], screen[1], screen[2], screen[3], screen[4], screen[5], screen[6], screen[7], color, 2);
    }

    // ---------------------------------------------------------------- terrain sculpting

    /** The height Flatten levels to: where the current stroke started (NaN between strokes). */
    private double flattenTo = Double.NaN;

    /**
     * Sculpts the land under the mouse while the button is held: the chosen brush, or
     * Shift = Lower and Ctrl = Smooth for quick changes. The brush shows as a circle on the land.
     */
    private void sculptTerrain(Entity entity, boolean hovered, ImVec2 origin, float width, float height, RenderView view) {
        Terrain terrain = entity.get(Terrain.class);
        ImGuiIO io = ImGui.getIO();
        Terrain.Brush brush = io.getKeyCtrl() ? Terrain.Brush.SMOOTH : io.getKeyShift() ? Terrain.Brush.LOWER : ctx.brush;
        drawBanner(origin, width, "Sculpting " + entity.name + " (" + brush.label + ")  ·  Shift lowers  ·  Ctrl smooths  ·  [ ] size  ·  Esc stops");
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            ctx.sculpt(null);
            return;
        }
        if (!io.getWantTextInput()) {
            if (ImGui.isKeyPressed(ImGuiKey.LeftBracket)) ctx.brushSize = Math.max(0.5, ctx.brushSize / 1.15);
            if (ImGui.isKeyPressed(ImGuiKey.RightBracket)) ctx.brushSize = Math.min(500, ctx.brushSize * 1.15);
        }
        if (!ImGui.isMouseDown(0)) flattenTo = Double.NaN;
        if (!hovered || navigating) return;

        ImVec2 mouse = ImGui.getMousePos();
        Vector3f hit = terrainUnderMouse(entity, terrain, mouse.x - origin.x, mouse.y - origin.y, width, height, view);
        if (hit == null) return;
        Vec3 p = entity.transform.position;
        double x = hit.x - p.x, z = hit.z - p.z;
        drawBrush(entity, terrain, x, z, ctx.brushSize, origin, width, height, view, brush == Terrain.Brush.LOWER ? Theme.ERROR : Theme.ACCENT);

        if (ImGui.isMouseDown(0) && !io.getKeyAlt() && !ImGui.isAnyItemHovered()) {
            if (Double.isNaN(flattenTo)) flattenTo = terrain.heightAt(x, z);
            double dt = Math.min(io.getDeltaTime(), 0.05);
            // Raise/Lower: up to 12 units a second at full strength; Smooth/Flatten blend toward their goal
            double amount = brush == Terrain.Brush.RAISE || brush == Terrain.Brush.LOWER
                    ? ctx.brushStrength * 12 * dt
                    : Math.min(1, ctx.brushStrength * 8 * dt);
            if (terrain.sculpt(brush, x, z, ctx.brushSize, amount, flattenTo)) ctx.markDirty();
        }
    }

    /** Where the mouse points on the terrain, in the world, or null if it misses. */
    private static Vector3f terrainUnderMouse(Entity entity, Terrain terrain, float mouseX, float mouseY,
                                              float width, float height, RenderView view) {
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        new Matrix4f(view.projection()).mul(view.view()).unprojectRay(mouseX, height - mouseY,
                new int[] {0, 0, (int) width, (int) height}, origin, direction);
        direction.normalize();
        Vec3 p = entity.transform.position;
        // march along the ray until it goes under the land, then home in on the crossing
        float step = (float) Math.max(0.25, terrain.size / Math.max(2, terrain.resolution) / 2);
        float reach = (float) (terrain.size * 2 + origin.distance((float) p.x, (float) p.y, (float) p.z));
        float last = 0;
        for (float t = 0; t <= reach; t += step) {
            float x = origin.x + direction.x * t, y = origin.y + direction.y * t, z = origin.z + direction.z * t;
            double ground = terrain.heightAt(x - p.x, z - p.z);
            if (!Double.isNaN(ground) && y <= ground + p.y) {
                float low = last, high = t;
                for (int i = 0; i < 16; i++) {
                    float mid = (low + high) / 2;
                    float mx = origin.x + direction.x * mid, mz = origin.z + direction.z * mid;
                    double g = terrain.heightAt(mx - p.x, mz - p.z);
                    if (!Double.isNaN(g) && origin.y + direction.y * mid <= g + p.y) high = mid;
                    else low = mid;
                }
                return new Vector3f(direction).mul(high).add(origin);
            }
            last = t;
        }
        return null;
    }

    /** The brush as a circle lying on the land. */
    private static void drawBrush(Entity entity, Terrain terrain, double x, double z, double radius, ImVec2 origin, float width,
                                  float height, RenderView view, java.awt.Color color) {
        Matrix4f viewProjection = new Matrix4f(view.projection()).mul(view.view());
        Vec3 p = entity.transform.position;
        int segments = 48;
        float[] points = new float[(segments + 1) * 2];
        for (int i = 0; i <= segments; i++) {
            double angle = Math.PI * 2 * i / segments;
            double px = x + Math.cos(angle) * radius, pz = z + Math.sin(angle) * radius;
            double ground = terrain.heightAt(px, pz);
            if (Double.isNaN(ground)) ground = terrain.heightAt(x, z);
            Vector4f clip = viewProjection.transform(new Vector4f((float) (px + p.x), (float) (ground + p.y + 0.05), (float) (pz + p.z), 1));
            if (clip.w <= 0.01f) return;
            points[i * 2] = origin.x + (clip.x / clip.w * 0.5f + 0.5f) * width;
            points[i * 2 + 1] = origin.y + (1 - (clip.y / clip.w * 0.5f + 0.5f)) * height;
        }
        ImDrawList draw = ImGui.getWindowDrawList();
        for (int i = 0; i < segments; i++) {
            draw.addLine(points[i * 2], points[i * 2 + 1], points[i * 2 + 2], points[i * 2 + 3], EditorStyle.pack(color), 2);
        }
    }

    private static void drawBanner(ImVec2 origin, float width, String text) {
        ImVec2 size = ImGui.calcTextSize(text);
        float x = origin.x + (width - size.x) / 2;
        float y = origin.y + 44;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x - 10, y - 4, x + size.x + 10, y + size.y + 4, EditorStyle.pack(EditorStyle.withAlpha(Theme.BLACK, 200)), 6);
        draw.addText(x, y, EditorStyle.pack(Theme.TEXT_MUTED), text);
    }

    /** Where a ray through the mouse hits the ground (y = 0), or a spot in front of the camera if it misses. */
    private static Vec3 groundPoint(float mouseX, float mouseY, float width, float height, RenderView view) {
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        new Matrix4f(view.projection()).mul(view.view()).unprojectRay(mouseX, height - mouseY,
                new int[] {0, 0, (int) width, (int) height}, origin, direction);
        if (direction.y < -1e-4f) {
            float t = -origin.y / direction.y;
            if (t > 0 && t < 500) return new Vec3(origin.x + direction.x * t, 0, origin.z + direction.z * t);
        }
        Vector3f ahead = origin.add(direction.normalize().mul(8));
        return new Vec3(ahead.x, Math.max(0, ahead.y), ahead.z);
    }

    // ---------------------------------------------------------------- icons & picking

    /** Cameras, lights and empty entities have nothing visible, so they get a small clickable marker. */
    private void drawEntityIcons(ImVec2 origin, float width, float height, RenderView view) {
        ImDrawList draw = ImGui.getWindowDrawList();
        Matrix4f viewProjection = new Matrix4f(view.projection()).mul(view.view());
        for (Entity entity : ctx.scene().entities()) {
            if (ctx.renderer.bounds(entity) != null) continue;
            Vector2f screen = toScreen(entity, viewProjection, width, height);
            if (screen == null) continue;
            float x = origin.x + screen.x;
            float y = origin.y + screen.y;
            int color = EditorStyle.pack(HierarchyPanel.iconColor(entity));
            draw.addCircleFilled(x, y, ICON_RADIUS, EditorStyle.pack(Theme.BLACK));
            draw.addCircle(x, y, ICON_RADIUS, color, 0, 2);
            String letter = entity.name.isEmpty() ? "?" : entity.name.substring(0, 1).toUpperCase();
            ImVec2 size = ImGui.calcTextSize(letter);
            draw.addText(x - size.x / 2, y - size.y / 2, color, letter);
            if (entity == ctx.selected()) draw.addCircle(x, y, ICON_RADIUS + 3, EditorStyle.pack(Theme.ACCENT), 0, 2);
        }
    }

    /** Finds what's under the mouse: markers first, then the nearest mesh hit by a ray. */
    private Entity pick(float mouseX, float mouseY, float width, float height, RenderView view) {
        Matrix4f viewProjection = new Matrix4f(view.projection()).mul(view.view());

        for (Entity entity : ctx.scene().entities()) {
            if (ctx.renderer.bounds(entity) != null) continue;
            Vector2f screen = toScreen(entity, viewProjection, width, height);
            if (screen != null && screen.distance(mouseX, mouseY) <= ICON_RADIUS + 2) return entity;
        }

        Vector3f rayOrigin = new Vector3f();
        Vector3f rayDirection = new Vector3f();
        viewProjection.unprojectRay(mouseX, height - mouseY, new int[] {0, 0, (int) width, (int) height},
                rayOrigin, rayDirection);

        Entity best = null;
        float bestDistance = Float.POSITIVE_INFINITY;
        Vector2f hit = new Vector2f();
        for (Entity entity : ctx.scene().entities()) {
            SceneRenderer.Bounds bounds = entity.active ? ctx.renderer.bounds(entity) : null;
            if (bounds == null) continue;
            Matrix4f inverse = bounds.matrix().invert(new Matrix4f());
            if (!inverse.isFinite()) continue; // zero scale
            Vector3f localOrigin = inverse.transformPosition(rayOrigin, new Vector3f());
            Vector3f localDirection = inverse.transformDirection(rayDirection, new Vector3f());
            if (Intersectionf.intersectRayAab(localOrigin, localDirection, bounds.min(), bounds.max(), hit)
                    && hit.x >= 0 && hit.x < bestDistance) {
                bestDistance = hit.x;
                best = entity;
            }
        }
        return best;
    }

    /** Where an entity's position lands in the view, in pixels, or null if it's behind the camera. */
    private static Vector2f toScreen(Entity entity, Matrix4f viewProjection, float width, float height) {
        Vector4f clip = viewProjection.transform(new Vector4f(
                (float) entity.transform.position.x, (float) entity.transform.position.y,
                (float) entity.transform.position.z, 1));
        if (clip.w <= 0.01f) return null;
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        return new Vector2f((ndcX * 0.5f + 0.5f) * width, (1 - (ndcY * 0.5f + 0.5f)) * height);
    }
}
