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
import sev7n.scene.Light;
import sev7n.scene.MeshRenderer;
import sev7n.scene.ParticleEmitter;
import sev7n.scene.Scene;
import sev7n.scene.SpriteRenderer;
import sev7n.ui.Theme;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * The list of everything in the scene.
 *   click to select · right-click for actions · drag to reorder
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
        for (Entity entity : new ArrayList<>(scene.entities())) {
            if (!search.isEmpty() && !entity.name.toLowerCase().contains(search)) continue;
            ImGui.pushID(Long.toString(entity.id()));
            drawRow(scene, entity);
            ImGui.popID();
        }
        acceptDropOnEmptySpace();

        // clicking empty space clears the selection; right-clicking it offers Create
        if (ImGui.isWindowHovered() && ImGui.isMouseClicked(0) && !ImGui.isAnyItemHovered()) ctx.select(null);
        if (ImGui.beginPopupContextWindow("##background", ImGuiPopupFlags.MouseButtonRight | ImGuiPopupFlags.NoOpenOverItems)) {
            createItems();
            ImGui.endPopup();
        }
        ImGui.endChild();
        ImGui.end();
    }

    private void drawRow(Scene scene, Entity entity) {
        boolean selected = entity == ctx.selected();
        if (!entity.active) ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(Theme.TEXT_FAINT));
        if (ImGui.selectable("      " + entity.name, selected, ImGuiSelectableFlags.AllowDoubleClick)) {
            ctx.select(entity);
        }
        if (!entity.active) ImGui.popStyleColor();

        ImVec2 min = ImGui.getItemRectMin();
        ImVec2 max = ImGui.getItemRectMax();
        float radius = ImGui.getFontSize() * 0.22f;
        ImGui.getWindowDrawList().addCircleFilled(min.x + radius * 3, (min.y + max.y) / 2, radius,
                EditorStyle.pack(iconColor(entity)));

        if (ImGui.beginPopupContextItem()) {
            ctx.select(entity);
            if (ImGui.menuItem("Duplicate", "Ctrl+D")) ctx.duplicate(entity);
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
            Long dragged = ImGui.acceptDragDropPayload(DragTypes.ENTITY, Long.class);
            if (dragged != null && dragged != entity.id()) {
                scene.move(scene.findById(dragged), scene.entities().indexOf(entity));
                ctx.markDirty();
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

    /** Dropping a picture or model on empty space makes a new object from it. */
    private void acceptDropOnEmptySpace() {
        ImGui.dummy(ImGui.getContentRegionAvailX(), Math.max(ImGui.getContentRegionAvailY(), ImGui.getFrameHeight()));
        if (!ImGui.beginDragDropTarget()) return;
        String asset = ImGui.acceptDragDropPayload(DragTypes.ASSET, String.class);
        if (asset != null) ctx.createFromAsset(asset, new Vec3());
        ImGui.endDragDropTarget();
    }

    private void createItems() {
        for (String kind : EditorContext.CREATABLE) {
            if (kind.equals("Sprite") || kind.equals("Camera")) ImGui.separator();
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
