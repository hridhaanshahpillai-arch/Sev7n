package sev7n.editor;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiSelectableFlags;
import imgui.type.ImString;
import sev7n.math.Vec3;
import sev7n.project.AssetKind;
import sev7n.scene.Behaviour;
import sev7n.scene.Camera;
import sev7n.scene.Entity;
import sev7n.scene.Hierarchy;
import sev7n.scene.Light;
import sev7n.scene.MeshRenderer;
import sev7n.scene.ParticleEmitter;
import sev7n.scene.Scene;
import sev7n.scene.SpriteRenderer;
import sev7n.ui.Theme;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of everything in the scene, as a family tree (children under their parents).
 *   click to select · right-click for actions
 *   drag onto an object to make it a child · drag onto a row's top edge to reorder · drag onto empty space to unparent
 *   drop a script from the Project panel onto an entity to attach it
 *   drop a picture or model onto an entity to apply it, or onto empty space to make a new one
 */
final class HierarchyPanel {

    static final String TITLE = "Hierarchy";

    private final EditorContext ctx;
    private final ImString filter = new ImString(64);

    HierarchyPanel(EditorContext ctx) {
        this.ctx = ctx;
    }

    /** The colored dot next to each entity: what kind of thing it is at a glance. */
    static Color iconColor(Entity entity) {
        if (entity.has(Camera.class)) return new Color(0x97E1F1);
        if (entity.has(Light.class)) return new Color(0xE7EE98);
        if (entity.has(MeshRenderer.class)) return Theme.ACCENT;
        if (entity.has(SpriteRenderer.class)) return new Color(0xF286C4);
        if (entity.has(ParticleEmitter.class)) return Theme.SUCCESS;
        return Theme.TEXT_FAINT;
    }

    void draw() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }

        if (ImGui.button("+")) ImGui.openPopup("create");
        if (ImGui.isItemHovered()) ImGui.setTooltip("Create");
        if (ImGui.beginPopup("create")) {
            createItems();
            ImGui.endPopup();
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##filter", "Search", filter);

        ImGui.beginChild("##entities");
        Scene scene = ctx.scene();
        String search = filter.get().trim().toLowerCase();
        if (search.isEmpty()) {
            // the family tree: each object, then its children (indented), unless it's folded up
            for (Entity entity : new ArrayList<>(scene.entities())) {
                if (entity.parent < 0 || scene.findById(entity.parent) == null) drawTree(scene, entity, 0);
            }
        } else {
            for (Entity entity : new ArrayList<>(scene.entities())) {
                if (!entity.name.toLowerCase().contains(search)) continue;
                ImGui.pushID(Long.toString(entity.id()));
                drawRow(scene, entity, 0, false);
                ImGui.popID();
            }
        }
        acceptDropOnEmptySpace(scene);

        // clicking empty space clears the selection; right-clicking it offers Create
        if (ImGui.isWindowHovered() && ImGui.isMouseClicked(0) && !ImGui.isAnyItemHovered()) ctx.select(null);
        if (ImGui.beginPopupContextWindow("##background", ImGuiPopupFlags.MouseButtonRight | ImGuiPopupFlags.NoOpenOverItems)) {
            createItems();
            ImGui.endPopup();
        }
        ImGui.endChild();
        ImGui.end();
    }

    /** Folded-up parents (their children are hidden). */
    private final java.util.Set<Long> folded = new java.util.HashSet<>();

    private void drawTree(Scene scene, Entity entity, int depth) {
        List<Entity> children = Hierarchy.children(scene, entity);
        ImGui.pushID(Long.toString(entity.id()));
        drawRow(scene, entity, depth, !children.isEmpty());
        ImGui.popID();
        if (folded.contains(entity.id()) || depth > 32) return;
        for (Entity child : children) drawTree(scene, child, depth + 1);
    }

    private void drawRow(Scene scene, Entity entity, int depth, boolean hasChildren) {
        boolean selected = entity == ctx.selected();
        float indent = depth * ImGui.getFontSize() * 1.1f;
        float arrowSize = ImGui.getFontSize();
        ImVec2 rowStart = ImGui.getCursorScreenPos();
        if (!entity.active) ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(Theme.TEXT_FAINT));
        // the label leaves room for the indent, the fold arrow and the dot
        int spaces = (int) Math.ceil((indent + arrowSize * 1.9f) / ImGui.calcTextSize(" ").x);
        if (ImGui.selectable(" ".repeat(spaces) + entity.name, selected, ImGuiSelectableFlags.AllowDoubleClick | ImGuiSelectableFlags.AllowItemOverlap)) {
            ctx.select(entity);
        }
        if (!entity.active) ImGui.popStyleColor();

        ImVec2 min = ImGui.getItemRectMin();
        ImVec2 max = ImGui.getItemRectMax();
        float midY = (min.y + max.y) / 2;
        float radius = ImGui.getFontSize() * 0.22f;
        ImGui.getWindowDrawList().addCircleFilled(min.x + indent + arrowSize + radius * 2, midY, radius,
                EditorStyle.pack(iconColor(entity)));
        if (hasChildren) {
            // a small triangle to fold the children away
            boolean isFolded = folded.contains(entity.id());
            float ax = min.x + indent + arrowSize * 0.35f;
            float s = arrowSize * 0.28f;
            int color = EditorStyle.pack(Theme.TEXT_MUTED);
            if (isFolded) ImGui.getWindowDrawList().addTriangleFilled(ax, midY - s, ax, midY + s, ax + s * 1.4f, midY, color);
            else ImGui.getWindowDrawList().addTriangleFilled(ax - s * 0.2f, midY - s * 0.6f, ax + s * 1.6f, midY - s * 0.6f, ax + s * 0.7f, midY + s * 0.8f, color);
            ImVec2 mouse = ImGui.getMousePos();
            if (ImGui.isMouseClicked(0) && mouse.y >= min.y && mouse.y <= max.y && mouse.x >= min.x + indent && mouse.x <= min.x + indent + arrowSize
                    && ImGui.isWindowHovered()) {
                if (isFolded) folded.remove(entity.id());
                else folded.add(entity.id());
            }
        }

        if (ImGui.beginPopupContextItem()) {
            ctx.select(entity);
            if (ImGui.menuItem("Duplicate", "Ctrl+D")) ctx.duplicate(entity);
            if (entity.parent >= 0 && ImGui.menuItem("Unparent")) ctx.setParent(entity, null);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Stop following its parent (it stays where it is)");
            if (ImGui.menuItem("Create Child")) {
                Entity child = ctx.create("Empty");
                child.transform.position.set(entity.transform.position);
                ctx.setParent(child, entity);
                folded.remove(entity.id());
            }
            if (ImGui.menuItem("Save as Prefab")) ctx.saveAsPrefab(entity);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Save this object to place again (Create > Prefab) and spawn from scripts: spawnPrefab(name)");
            if (ImGui.menuItem(entity.active ? "Disable" : "Enable")) {
                entity.active = !entity.active;
                ctx.markDirty();
            }
            if (ImGui.beginMenu("New Effect")) { // made where this entity is
                for (String preset : ParticleEmitter.PRESETS) {
                    if (ImGui.menuItem(preset)) ctx.createEffect(preset).transform.position.set(entity.transform.position);
                }
                ImGui.endMenu();
            }
            ImGui.separator();
            if (ImGui.menuItem("Delete", "Del")) ctx.delete(entity);
            ImGui.endPopup();
        }

        if (ImGui.beginDragDropSource()) {
            ImGui.setDragDropPayload(DragTypes.ENTITY, entity.id());
            ImGui.text(entity.name);
            ImGui.endDragDropSource();
        }
        if (ImGui.beginDragDropTarget()) {
            // dropping an object on the middle of a row puts it inside (a child); on the top edge, just above
            ImVec2 mouse = ImGui.getMousePos();
            boolean onEdge = mouse.y < min.y + (max.y - min.y) * 0.28f;
            if (onEdge) ImGui.getWindowDrawList().addLine(min.x + indent, min.y, max.x, min.y, EditorStyle.pack(Theme.ACCENT), 2);
            Long dragged = ImGui.acceptDragDropPayload(DragTypes.ENTITY, Long.class);
            Entity moving = dragged == null ? null : scene.findById(dragged);
            if (moving != null && moving != entity) {
                if (onEdge) {
                    // a sibling of this row, placed just above it
                    Entity newParent = entity.parent < 0 ? null : scene.findById(entity.parent);
                    if (newParent != moving && (newParent == null || !Hierarchy.isAncestor(scene, moving, newParent))) {
                        if ((newParent == null ? -1 : newParent.id()) != moving.parent) ctx.setParent(moving, newParent);
                        scene.move(moving, scene.entities().indexOf(entity));
                        ctx.markDirty();
                    }
                } else {
                    ctx.setParent(moving, entity);
                    folded.remove(entity.id());
                }
            }
            String script = ImGui.acceptDragDropPayload(DragTypes.SCRIPT, String.class);
            if (script != null) {
                entity.add(new Behaviour(script));
                ctx.select(entity);
                ctx.markDirty();
            }
            String asset = ImGui.acceptDragDropPayload(DragTypes.ASSET, String.class);
            if (asset != null) dropAssetOn(entity, asset);
            ImGui.endDragDropTarget();
        }
    }

    /** A picture dropped on an object becomes its texture; a model replaces its mesh. */
    private void dropAssetOn(Entity entity, String asset) {
        if (AssetKind.of(Path.of(asset)) == AssetKind.TEXTURE) {
            ctx.applyTexture(entity, asset);
            return;
        }
        MeshRenderer mesh = entity.get(MeshRenderer.class);
        if (mesh == null) mesh = entity.add(new MeshRenderer());
        mesh.mesh = asset;
        ctx.select(entity);
        ctx.markDirty();
    }

    /** Dropping a picture or model on empty space makes a new object from it; dropping an object there takes it out of its parent. */
    private void acceptDropOnEmptySpace(Scene scene) {
        ImGui.dummy(ImGui.getContentRegionAvailX(), Math.max(ImGui.getContentRegionAvailY(), ImGui.getFrameHeight()));
        if (!ImGui.beginDragDropTarget()) return;
        String asset = ImGui.acceptDragDropPayload(DragTypes.ASSET, String.class);
        if (asset != null) ctx.createFromAsset(asset, new Vec3());
        Long dragged = ImGui.acceptDragDropPayload(DragTypes.ENTITY, Long.class);
        Entity moving = dragged == null ? null : scene.findById(dragged);
        if (moving != null) {
            if (moving.parent >= 0) ctx.setParent(moving, null);
            List<Entity> family = new ArrayList<>(List.of(moving));
            family.addAll(Hierarchy.descendants(scene, moving));
            for (Entity e : family) scene.move(e, scene.entities().size()); // to the bottom, family together
            ctx.markDirty();
        }
        ImGui.endDragDropTarget();
    }

    private void createItems() {
        for (String kind : EditorContext.CREATABLE) {
            if (kind.equals("Sprite") || kind.equals("Camera") || kind.equals("NPC")) ImGui.separator();
            if (ImGui.menuItem(kind)) ctx.create(kind);
        }
        ImGui.separator();
        effectMenu(ctx);
    }

    /** "New Effect" with a particle effect preset in each item. Also in the menu bar's Create menu. */
    static void effectMenu(EditorContext ctx) {
        if (!ImGui.beginMenu("New Effect")) return;
        for (String preset : ParticleEmitter.PRESETS) {
            if (ImGui.menuItem(preset)) ctx.createEffect(preset);
        }
        ImGui.endMenu();
    }
}
