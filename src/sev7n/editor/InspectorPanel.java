package sev7n.editor;

import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImString;
import sev7n.eights.GraphFile;
import sev7n.project.AssetKind;
import sev7n.render.Texture;
import sev7n.scene.Behaviour;
import sev7n.scene.BoxCollider;
import sev7n.scene.Camera;
import sev7n.scene.Component;
import sev7n.scene.Components;
import sev7n.scene.Entity;
import sev7n.scene.Light;
import sev7n.render.SceneRenderer;
import sev7n.scene.MeshRenderer;
import sev7n.scene.Rigidbody;
import sev7n.scene.SpriteAnimator;
import sev7n.scene.SpriteRenderer;
import sev7n.scene.Tilemap;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Shows and edits the selected entity: name, tags, transform and components. */
final class InspectorPanel {

    static final String TITLE = "Inspector";

    private final EditorContext ctx;
    private final ImString name = new ImString(128);
    private final ImString tags = new ImString(256);

    InspectorPanel(EditorContext ctx) {
        this.ctx = ctx;
    }

    void draw() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        Entity entity = ctx.selected();
        if (entity == null) {
            Widgets.hint("Select something in the Hierarchy or the Scene view to see its settings here.");
        } else {
            drawHeader(entity);
            drawTransform(entity);
            for (Component component : new ArrayList<>(entity.components())) {
                ImGui.pushID(System.identityHashCode(component));
                drawComponent(entity, component);
                ImGui.popID();
            }
            ImGui.spacing();
            drawAddComponent(entity);
            acceptDroppedScript(entity);
        }
        ImGui.end();
    }

    private void drawHeader(Entity entity) {
        if (ImGui.checkbox("##active", entity.active)) {
            entity.active = !entity.active;
            ctx.markDirty();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Active");
        ImGui.sameLine();
        ImGui.setNextItemWidth(-1);
        syncBuffer(name, entity.name);
        if (ImGui.inputText("##name", name)) {
            entity.name = name.get();
            ctx.markDirty();
        }

        syncBuffer(tags, String.join(", ", entity.tags));
        Widgets.label("Tags");
        if (ImGui.inputTextWithHint("##tags", "player, enemy, coin", tags)) {
            entity.tags.clear();
            Arrays.stream(tags.get().split(",")).map(String::trim).filter(t -> !t.isEmpty()).forEach(entity.tags::add);
            ctx.markDirty();
        }
        ImGui.spacing();
    }

    /** Copies the model into an ImGui text buffer, unless the user is typing in it. */
    private static void syncBuffer(ImString buffer, String value) {
        if (!buffer.get().equals(value) && !ImGui.isAnyItemActive()) buffer.set(value);
    }

    private void drawTransform(Entity entity) {
        if (!ImGui.collapsingHeader("Transform", ImGuiTreeNodeFlags.DefaultOpen)) return;
        boolean changed = Widgets.vec3("Position", entity.transform.position, 0.05f);
        changed |= Widgets.vec3("Rotation", entity.transform.rotation, 0.5f);
        changed |= Widgets.vec3("Scale", entity.transform.scale, 0.02f);
        if (changed) ctx.markDirty();
        ImGui.spacing();
    }

    private void drawComponent(Entity entity, Component component) {
        boolean open = ImGui.collapsingHeader(component.displayName() + "###header",
                ImGuiTreeNodeFlags.DefaultOpen | ImGuiTreeNodeFlags.AllowItemOverlap);
        if (ImGui.beginPopupContextItem()) {
            if (ImGui.menuItem("Remove Component")) {
                entity.remove(component);
                ctx.markDirty();
            }
            ImGui.endPopup();
        }
        // a small remove button on the header's right edge
        ImGui.sameLine(ImGui.getContentRegionMaxX() - ImGui.getFrameHeight());
        if (ImGui.smallButton("x")) {
            entity.remove(component);
            ctx.markDirty();
            return;
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Remove component");
        if (!open) return;

        boolean changed = switch (component) {
            case MeshRenderer m -> drawMeshRenderer(m);
            case SpriteRenderer s -> drawSpriteRenderer(entity, s);
            case SpriteAnimator a -> drawSpriteAnimator(entity, a);
            case Tilemap t -> drawTilemap(entity, t);
            case Camera c -> drawCamera(entity, c);
            case Light l -> drawLight(l);
            case BoxCollider b -> drawBoxCollider(entity, b);
            case Rigidbody r -> drawRigidbody(entity, r);
            case Behaviour b -> drawBehaviour(b);
            default -> false;
        };
        if (changed) ctx.markDirty();
        ImGui.spacing();
    }

    private boolean drawMeshRenderer(MeshRenderer m) {
        List<String> meshes = new ArrayList<>(MeshRenderer.MESHES);
        meshes.addAll(ctx.project.assets(AssetKind.MODEL));
        String mesh = acceptDrop(Widgets.choice("Mesh", m.mesh, meshes), AssetKind.MODEL);
        Color color = Widgets.color("Color", m.color);
        String texture = textureField("Texture", m.texture);
        boolean changed = !mesh.equals(m.mesh) || !color.equals(m.color) || !texture.equals(m.texture);
        m.mesh = mesh;
        m.color = color;
        m.texture = texture;
        return changed;
    }

    private boolean drawSpriteRenderer(Entity entity, SpriteRenderer s) {
        String texture = textureField("Picture", s.texture);
        Color tint = Widgets.color("Tint", s.tint);
        double size = Widgets.number("Size", s.size, 0.02f, 0.01f, 1000);
        boolean billboard = Widgets.checkbox("Billboard", s.billboard, "Always turn to face the camera");
        boolean pixelArt = Widgets.checkbox("Pixel art", s.pixelArt, "Keep pixels sharp instead of smoothing them");
        boolean removeBackground = Widgets.checkbox("Remove bg", s.removeBackground,
                "Make the background color see-through (the color of the picture's top-left corner). For retro sprites on a solid color");
        boolean flipX = Widgets.checkbox("Flip X", s.flipX, "Mirror left-right (face the other way)");
        boolean flipY = Widgets.checkbox("Flip Y", s.flipY, "Turn upside down");
        int order = Widgets.integer("Order", s.order, -1000, 1000);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Sprites at the same distance: higher numbers are drawn on top");

        // sprite sheets: frames cut out in the Sprite Editor (or found automatically), or an even grid
        int columns = s.columns;
        int rows = s.rows;
        int frame = s.frame;
        Texture sheet = ctx.texture(texture, pixelArt, removeBackground);
        boolean framesChanged = false;
        if (!s.frames.isEmpty()) {
            frame = Widgets.integer("Frame", s.frame, 0, s.frames.size() - 1);
            if (sheet != null) {
                int clicked = Widgets.framesPicture(sheet.id, sheet.width, sheet.height, s.frames, frame);
                if (clicked >= 0) frame = clicked;
            }
            Widgets.hint(s.frames.size() + " frames. Click one to show it; crop, join or reorder them in the Sprite Editor.");
        } else {
            columns = Widgets.integer("Sheet columns", s.columns, 1, 64);
            rows = Widgets.integer("Sheet rows", s.rows, 1, 64);
            if (columns * rows > 1) {
                frame = Widgets.integer("Frame", s.frame, 0, columns * rows - 1);
                if (sheet != null) {
                    int clicked = Widgets.gridPicture(sheet.id, sheet.aspect(), columns, rows, frame);
                    if (clicked >= 0) frame = clicked;
                }
            }
            if (!texture.isEmpty()) {
                ImGui.setCursorPosX(Widgets.labelWidth());
                if (ImGui.button("Find frames automatically", -1, 0) && SpriteEditorPanel.findFrames(ctx, s)) {
                    framesChanged = true;
                    removeBackground = s.removeBackground; // finding frames may have turned it on
                    frame = 0;
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("For sprite sheets whose frames aren't on an even grid: finds each picture on the sheet");
                }
            }
        }
        if (!texture.isEmpty()) {
            ImGui.setCursorPosX(Widgets.labelWidth());
            if (ImGui.button("Open Sprite Editor", -1, 0)) ctx.spriteEditor.open(entity);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Crop frames exactly: rectangles, polygons with diagonal edges, grids, pivots");
        }
        if (framesChanged) frame = Math.min(frame, s.frameCount() - 1);

        boolean changed = !texture.equals(s.texture) || !tint.equals(s.tint) || size != s.size
                || billboard != s.billboard || pixelArt != s.pixelArt || removeBackground != s.removeBackground
                || flipX != s.flipX || flipY != s.flipY
                || order != s.order || columns != s.columns || rows != s.rows || frame != s.frame || framesChanged;
        s.texture = texture;
        s.tint = tint;
        s.size = size;
        s.billboard = billboard;
        s.pixelArt = pixelArt;
        s.removeBackground = removeBackground;
        s.flipX = flipX;
        s.flipY = flipY;
        s.order = order;
        s.columns = columns;
        s.rows = rows;
        s.frame = Math.min(frame, s.frameCount() - 1);
        return changed;
    }

    // ---------------------------------------------------------------- sprite animator

    private final Map<SpriteAnimator.Clip, ImString> clipNames = new IdentityHashMap<>();

    private boolean drawSpriteAnimator(Entity entity, SpriteAnimator a) {
        boolean changed = false;
        SpriteRenderer sprite = entity.get(SpriteRenderer.class);
        if (sprite == null) {
            Widgets.hint("Needs a Sprite Renderer with a sprite sheet (set its Sheet columns and rows).");
            return false;
        }
        int lastFrame = sprite.frameCount() - 1;
        List<String> names = new ArrayList<>();
        for (SpriteAnimator.Clip clip : a.clips) names.add(clip.name);

        String shown = a.defaultClip.isEmpty() && !names.isEmpty() ? names.get(0) : a.defaultClip;
        String chosen = Widgets.choice("Plays first", shown, names);
        if (!chosen.equals(shown)) {
            a.defaultClip = chosen;
            changed = true;
        }

        SpriteAnimator.Clip remove = null;
        for (SpriteAnimator.Clip clip : a.clips) {
            ImGui.pushID(System.identityHashCode(clip));
            ImGui.separator();
            ImString name = clipNames.computeIfAbsent(clip, c -> new ImString(c.name, 64));
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - ImGui.getFrameHeight() - ImGui.getStyle().getItemSpacingX());
            if (ImGui.inputText("##name", name) && !name.get().isBlank()) {
                if (a.defaultClip.equals(clip.name)) a.defaultClip = name.get().trim();
                clip.name = name.get().trim();
                changed = true;
            }
            ImGui.sameLine();
            if (ImGui.button("x", ImGui.getFrameHeight(), 0)) remove = clip;
            if (ImGui.isItemHovered()) ImGui.setTooltip("Remove this animation");

            int first = Widgets.integer("From frame", clip.first, 0, lastFrame);
            int last = Widgets.integer("To frame", clip.last, 0, lastFrame);
            double fps = Widgets.number("Frames / sec", clip.fps, 0.1f, 0.1f, 120);
            boolean loop = Widgets.checkbox("Loop", clip.loop, "Start again after the last frame");
            changed |= first != clip.first || last != clip.last || fps != clip.fps || loop != clip.loop;
            clip.first = first;
            clip.last = last;
            clip.fps = fps;
            clip.loop = loop;
            ImGui.popID();
        }
        if (remove != null) {
            a.clips.remove(remove);
            changed = true;
        }
        ImGui.separator();
        if (ImGui.button("+ Animation")) {
            a.clips.add(new SpriteAnimator.Clip(uniqueClipName(a), 0, lastFrame));
            changed = true;
        }
        ImGui.sameLine();
        boolean previewing = ctx.previewing(entity);
        if (ImGui.button(previewing ? "Stop preview" : "Preview")) ctx.preview(previewing ? null : entity);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Play the first animation here in the editor");
        return changed;
    }

    private static String uniqueClipName(SpriteAnimator a) {
        for (int i = 1; ; i++) {
            String name = "animation" + i;
            if (a.clip(name) == null) return name;
        }
    }

    // ---------------------------------------------------------------- tilemap

    private boolean drawTilemap(Entity entity, Tilemap t) {
        String tileset = textureField("Tileset", t.tileset);
        int columns = Widgets.integer("Columns", t.columns, 1, 128);
        int rows = Widgets.integer("Rows", t.rows, 1, 128);
        double tileSize = Widgets.number("Tile size", t.tileSize, 0.01f, 0.01f, 100);
        boolean pixelArt = Widgets.checkbox("Pixel art", t.pixelArt, "Keep pixels sharp instead of smoothing them");
        boolean solid = Widgets.checkbox("Solid", t.solid, "Rigidbodies can't pass through the tiles");
        boolean changed = !tileset.equals(t.tileset) || columns != t.columns || rows != t.rows
                || tileSize != t.tileSize || pixelArt != t.pixelArt || solid != t.solid;
        t.tileset = tileset;
        t.columns = columns;
        t.rows = rows;
        t.tileSize = tileSize;
        t.pixelArt = pixelArt;
        t.solid = solid;

        ImGui.spacing();
        boolean painting = ctx.paintingTiles(entity);
        if (painting ? ImGui.button("Stop painting", -1, 0) : Widgets.primaryButton("Paint tiles")) {
            ctx.paintTiles(painting ? null : entity);
        }
        if (painting) {
            Widgets.hint("Click or drag in the Scene view to paint. Shift + click erases. Tip: turn on 2D in the Scene view.");
            if (ImGui.button(ctx.paintTile() == Tilemap.EMPTY ? "[ Eraser ]" : "Eraser")) ctx.setPaintTile(Tilemap.EMPTY);
            ImGui.sameLine();
            if (ImGui.button("Clear all")) ImGui.openPopup("clear-tiles");
            if (ImGui.beginPopup("clear-tiles")) {
                ImGui.text("Remove every tile?");
                if (ImGui.button("Remove all")) {
                    t.clear();
                    changed = true;
                    ImGui.closeCurrentPopup();
                }
                ImGui.endPopup();
            }
        }
        Texture texture = ctx.texture(tileset, pixelArt, false);
        if (texture != null) {
            int clicked = Widgets.gridPicture(texture.id, texture.aspect(), columns, rows, painting ? ctx.paintTile() : -1);
            if (clicked >= 0) {
                ctx.setPaintTile(clicked);
                ctx.paintTiles(entity);
            }
        } else {
            Widgets.hint("Pick a tileset picture, then set how many tiles across (columns) and down (rows) it has.");
        }
        return changed;
    }

    // ---------------------------------------------------------------- physics

    private boolean drawBoxCollider(Entity entity, BoxCollider b) {
        boolean changed = Widgets.vec3("Size", b.size, 0.02f);
        changed |= Widgets.vec3("Offset", b.offset, 0.02f);
        boolean trigger = Widgets.checkbox("Trigger", b.trigger, "Never solid: things pass through, but onCollide still happens (coins, checkpoints)");
        changed |= trigger != b.trigger;
        b.trigger = trigger;
        SceneRenderer.Bounds shape = ctx.renderer.bounds(entity);
        if (shape != null) {
            ImGui.setCursorPosX(Widgets.labelWidth());
            if (ImGui.button("Fit to shape", -1, 0)) {
                b.size.set(shape.max().x - shape.min().x, shape.max().y - shape.min().y, Math.max(0.1, shape.max().z - shape.min().z));
                b.offset.set((shape.max().x + shape.min().x) / 2, (shape.max().y + shape.min().y) / 2, 0);
                changed = true;
            }
        }
        return changed;
    }

    private boolean drawRigidbody(Entity entity, Rigidbody r) {
        double gravity = Widgets.number("Gravity", r.gravityScale, 0.01f, -10, 10);
        if (ImGui.isItemHovered()) ImGui.setTooltip("1 = normal, 0 = floats (top-down games, space), 2 = falls twice as hard");
        double bounciness = Widgets.number("Bounciness", r.bounciness, 0.01f, 0, 1);
        double drag = Widgets.number("Drag", r.drag, 0.01f, 0, 50);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Slows it down over time, like air resistance");
        boolean changed = Widgets.vec3("Start velocity", r.velocity, 0.05f);
        changed |= gravity != r.gravityScale || bounciness != r.bounciness || drag != r.drag;
        r.gravityScale = gravity;
        r.bounciness = bounciness;
        r.drag = drag;
        if (!entity.has(BoxCollider.class)) Widgets.hint("Add a Box Collider so it can land on and bump into things.");
        return changed;
    }

    /** A picture picker: a small preview, then a menu of imported pictures. Pictures can be dropped on it too. */
    private String textureField(String label, String current) {
        Widgets.label(label);
        float previewSize = ImGui.getFrameHeight();
        Texture preview = ctx.texture(current, false, false);
        if (preview != null) {
            ImGui.image(preview.id, previewSize, previewSize);
            ImGui.sameLine();
        }
        ImGui.setNextItemWidth(-1);
        String result = current;
        String shown = current.isEmpty() ? "(none)" : current.substring(current.lastIndexOf('/') + 1);
        if (ImGui.beginCombo("##" + label, shown)) {
            if (ImGui.selectable("(none)", current.isEmpty())) result = "";
            for (String texture : ctx.project.assets(AssetKind.TEXTURE)) {
                if (ImGui.selectable(texture, texture.equals(current))) result = texture;
            }
            if (ctx.project.assets(AssetKind.TEXTURE).isEmpty()) ImGui.textDisabled("Import pictures in the Project panel");
            ImGui.endCombo();
        }
        return acceptDrop(result, AssetKind.TEXTURE);
    }

    /** Lets an asset of the right kind be dropped onto the field drawn just before. */
    private static String acceptDrop(String current, AssetKind kind) {
        if (!ImGui.beginDragDropTarget()) return current;
        String asset = ImGui.acceptDragDropPayload(DragTypes.ASSET, String.class);
        ImGui.endDragDropTarget();
        return asset != null && AssetKind.of(Path.of(asset)) == kind ? asset : current;
    }

    private boolean drawCamera(Entity entity, Camera c) {
        boolean orthographic = Widgets.checkbox("2D (flat)", c.orthographic,
                "Orthographic: things don't shrink with distance. Turn off for 3D perspective (scripts can switch too)");
        double fov = c.fieldOfView;
        double size = c.size;
        if (orthographic) {
            size = Widgets.number("Size", c.size, 0.05f, 0.1f, 1000);
            if (ImGui.isItemHovered()) ImGui.setTooltip("How many units the camera sees above and below its center");
        } else {
            fov = Widgets.number("Field of view", c.fieldOfView, 0.5f, 10, 170);
        }
        double near = Widgets.number("Near", c.near, 0.01f, 0.01f, 10);
        double far = Widgets.number("Far", c.far, 1f, 1, 10000);
        Color background = Widgets.color("Background", c.background);
        boolean changed = orthographic != c.orthographic || size != c.size || fov != c.fieldOfView
                || near != c.near || far != c.far || !background.equals(c.background);

        // a 2D camera should look straight at the X/Y plane (down -Z), from in front of it
        boolean facingPlane = entity.transform.rotation.x == 0 && entity.transform.rotation.y == 0 && entity.transform.position.z > 0;
        if (orthographic && !facingPlane) {
            ImGui.setCursorPosX(Widgets.labelWidth());
            if (ImGui.button("Face the 2D plane", -1, 0)) {
                entity.transform.rotation.set(0, 0, 0);
                if (entity.transform.position.z <= 0) entity.transform.position.z = 10;
                changed = true;
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("Point the camera straight down -Z at the X/Y plane, where 2D games live");
        }
        c.orthographic = orthographic;
        c.size = size;
        c.fieldOfView = fov;
        c.near = near;
        c.far = Math.max(far, near + 0.01);
        c.background = background;
        return changed;
    }

    private boolean drawLight(Light l) {
        Color color = Widgets.color("Color", l.color);
        double intensity = Widgets.number("Intensity", l.intensity, 0.01f, 0, 10);
        double ambient = Widgets.number("Ambient", l.ambient, 0.01f, 0, 1);
        boolean changed = !color.equals(l.color) || intensity != l.intensity || ambient != l.ambient;
        l.color = color;
        l.intensity = intensity;
        l.ambient = ambient;
        return changed;
    }

    private boolean drawBehaviour(Behaviour b) {
        List<String> scripts = ctx.project.behaviourNames();
        String script = Widgets.choice("Script", b.script, scripts);
        boolean changed = !script.equals(b.script);
        b.script = script;
        Path file = b.script.isEmpty() ? null : ctx.project.behaviourFile(b.script);
        if (file != null) {
            ImGui.setCursorPosX(Widgets.labelWidth());
            boolean graph = file.toString().endsWith(GraphFile.EXTENSION);
            if (ImGui.button(graph ? "Edit graph" : "Edit script", -1, 0)) ctx.openScript(file, 0);
        } else if (!b.script.isEmpty()) {
            Widgets.hint("There's no " + b.script + ".7n or " + b.script + ".8s in the scripts folder.");
        }
        return changed;
    }

    private void drawAddComponent(Entity entity) {
        if (Widgets.primaryButton("Add Component")) ImGui.openPopup("add-component");
        if (!ImGui.beginPopup("add-component")) return;
        for (String type : Components.types()) {
            if (type.equals("Behaviour")) {
                if (ImGui.beginMenu("Behaviour")) {
                    for (String script : ctx.project.behaviourNames()) {
                        if (ImGui.menuItem(script)) addComponent(entity, new Behaviour(script));
                    }
                    if (ctx.project.behaviourNames().isEmpty()) ImGui.textDisabled("No scripts yet");
                    ImGui.endMenu();
                }
            } else if (ImGui.menuItem(Components.displayName(type))) {
                addComponent(entity, Components.create(type));
            }
        }
        ImGui.endPopup();
    }

    private void addComponent(Entity entity, Component component) {
        entity.add(component);
        ctx.markDirty();
    }

    /** Dropping a script from the Project panel anywhere on the Inspector attaches it. */
    private void acceptDroppedScript(Entity entity) {
        ImGui.dummy(ImGui.getContentRegionAvailX(), Math.max(40, ImGui.getContentRegionAvailY()));
        if (ImGui.beginDragDropTarget()) {
            String script = ImGui.acceptDragDropPayload(DragTypes.SCRIPT, String.class);
            if (script != null) addComponent(entity, new Behaviour(script));
            ImGui.endDragDropTarget();
        }
    }
}
