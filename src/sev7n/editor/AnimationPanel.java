package sev7n.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import sev7n.project.AssetKind;
import sev7n.scene.AnimationClip;
import sev7n.scene.AnimationClip.Key;
import sev7n.scene.AnimationClip.Property;
import sev7n.scene.AnimationPlayer;
import sev7n.scene.Entity;
import sev7n.scene.Light;
import sev7n.scene.MeshRenderer;
import sev7n.scene.SpriteRenderer;
import sev7n.scene.Transform;
import sev7n.ui.Theme;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Window > Animation: makes keyframe animations (.anim clips) for the selected object, on a timeline.
 *
 *   scrub        click or drag the ruler to see the object at that moment
 *   Record       while it's on, moving, turning, scaling or recolouring the object (gizmo or Inspector)
 *                sets a key at the current time
 *   keys         double-click a track to add one · drag to move · right-click for its value, curve or delete
 *   events       call a script function at a moment (footsteps, a creak, the end of a cutscene)
 *
 * While the window shows a clip, the object is posed as the clip is at the current time (a preview):
 * the real values come back when you pick something else, close the window, save or press Play.
 * Clips save by themselves.
 */
final class AnimationPanel {

    static final String TITLE = "Animation";
    private static final String[] EASINGS = {"linear", "step", "inOutSine", "inSine", "outSine", "inOutQuad", "inOutCubic",
            "outBack", "outBounce", "outElastic"};
    private static final double SNAP = 1 / 60.0;

    private final EditorContext ctx;
    private final ImBoolean open = new ImBoolean(false);
    private boolean focusRequested;

    // the clip being edited, and for which object
    private long targetId = -1;
    private String clipPath = "";
    private AnimationClip clip;
    private boolean clipChanged;
    private String problem = "";

    // the timeline
    private double time;
    private boolean playing;
    private boolean recording;
    private Property selectedProperty;
    private Key selectedKey;
    private boolean draggingKey;
    private boolean scrubbing;
    private AnimationClip.Event selectedEvent;
    private final ImString eventName = new ImString(64);

    // the preview: the object's real values while it's posed by the clip
    private Entity previewed;
    private Transform savedTransform;
    private Color savedColor;
    private Double savedLight;
    private boolean savedActive;
    private final Map<Property, Object> applied = new EnumMap<>(Property.class);

    AnimationPanel(EditorContext ctx) {
        this.ctx = ctx;
    }

    void open() {
        open.set(true);
        focusRequested = true;
    }

    boolean previewing() {
        return previewed != null;
    }

    // ---------------------------------------------------------------- the preview

    /** Puts the object back as it really is (the clip stops posing it until the next frame it's shown). */
    void endPreview() {
        if (previewed != null) {
            previewed.transform.copyFrom(savedTransform);
            setColor(previewed, savedColor);
            if (savedLight != null && previewed.get(Light.class) != null) previewed.get(Light.class).intensity = savedLight;
            previewed.active = savedActive;
            Entity parent = previewed.parent >= 0 ? ctx.scene().findById(previewed.parent) : null;
            if (parent != null) sev7n.scene.Hierarchy.setParent(ctx.scene(), previewed, parent); // its offset as it really is
        }
        previewed = null;
        applied.clear();
        playing = false;
        saveClip(); // nothing is lost
    }

    private void startPreview(Entity entity) {
        previewed = entity;
        savedTransform = new Transform();
        savedTransform.copyFrom(entity.transform);
        savedColor = AnimationClip.color(entity);
        savedLight = entity.get(Light.class) == null ? null : entity.get(Light.class).intensity;
        savedActive = entity.active;
    }

    /** Poses the object as the clip is at the current time, and remembers what it set (so Record can tell user changes apart). */
    private void pose(Entity entity) {
        clip.apply(ctx.scene(), entity, clip.wrap(time));
        applied.clear();
        for (Property p : Property.values()) {
            Object v = AnimationClip.current(ctx.scene(), entity, p);
            if (v != null) applied.put(p, v);
        }
    }

    /** While recording: whatever the user changed since the last pose becomes a key at the current time. */
    private void record(Entity entity) {
        for (Property p : Property.values()) {
            Object now = AnimationClip.current(ctx.scene(), entity, p);
            Object before = applied.get(p);
            if (now == null || before == null || same(now, before)) continue;
            clip.setKey(p, round(time), now);
            clipChanged = true;
        }
    }

    /**
     * Not recording: changes the user makes to things the clip doesn't animate are real edits, so
     * they're kept when the preview ends (only the clip's own properties go back).
     */
    private void keepEdits(Entity entity) {
        boolean noParent = entity.parent < 0;
        boolean transformTracks = false;
        for (Property p : clip.tracks.keySet()) transformTracks |= p.transform();
        for (Property p : Property.values()) {
            Object now = AnimationClip.current(ctx.scene(), entity, p);
            Object before = applied.get(p);
            if (now == null || before == null || same(now, before)) continue;
            applied.put(p, now);
            if (clip.tracks.containsKey(p)) continue; // animated: the clip decides, and it goes back afterwards
            if (p.transform()) {
                if (!transformTracks) savedTransform.copyFrom(entity.transform);
                else if (noParent) setComponent(savedTransform, p, (Double) now);
            } else if (p == Property.COLOR) {
                savedColor = (Color) now;
            } else if (p == Property.LIGHT) {
                savedLight = (Double) now;
            } else if (p == Property.ACTIVE) {
                savedActive = (Boolean) now;
            }
        }
    }

    private static void setComponent(Transform t, Property p, double v) {
        switch (p) {
            case POSITION_X -> t.position.x = v;
            case POSITION_Y -> t.position.y = v;
            case POSITION_Z -> t.position.z = v;
            case ROTATION_X -> t.rotation.x = v;
            case ROTATION_Y -> t.rotation.y = v;
            case ROTATION_Z -> t.rotation.z = v;
            case SCALE_X -> t.scale.x = v;
            case SCALE_Y -> t.scale.y = v;
            case SCALE_Z -> t.scale.z = v;
            default -> { }
        }
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Double x && b instanceof Double y) return Math.abs(x - y) < 1e-5;
        return a.equals(b);
    }

    // ---------------------------------------------------------------- drawing

    void draw() {
        if (!open.get()) {
            if (previewed != null) endPreview();
            return;
        }
        float scale = ImGui.getFontSize() / 15f;
        ImGui.setNextWindowSize(820 * scale, 300 * scale, ImGuiCond.FirstUseEver);
        if (ConsolePanel.dockId != 0) ImGui.setNextWindowDockID(ConsolePanel.dockId, ImGuiCond.Once); // opens as a tab next to the Console
        if (focusRequested) {
            ImGui.setNextWindowFocus();
            focusRequested = false;
        }
        if (!ImGui.begin(TITLE, open)) {
            ImGui.end();
            if (previewed != null) endPreview();
            return;
        }
        Entity entity = ctx.selected();
        if (ctx.playing()) {
            if (previewed != null) endPreview();
            Widgets.hint("Stop the game to make animations.");
        } else if (entity == null) {
            if (previewed != null) endPreview();
            Widgets.hint("Select an object to animate it: doors that swing, platforms that glide, lights that pulse, coins that bob.");
        } else {
            if (entity.id() != targetId) {
                if (previewed != null) endPreview();
                targetId = entity.id();
                time = 0;
                selectedKey = null;
                selectedEvent = null;
                recording = false;
            }
            drawFor(entity);
        }
        ImGui.end();
        if (clipChanged && !ImGui.isMouseDown(0)) saveClip();
    }

    private void drawFor(Entity entity) {
        AnimationPlayer player = entity.get(AnimationPlayer.class);
        if (player == null || player.clip.isBlank()) {
            if (previewed != null) endPreview();
            Widgets.hint(entity.name + " has no animation yet.");
            if (Widgets.primaryButtonInline("Create animation for " + entity.name)) createClip(entity, player);
            return;
        }
        if (!player.clip.equals(clipPath) || clip == null) {
            if (previewed != null) endPreview();
            loadClip(player.clip);
        }
        if (clip == null) {
            Widgets.hint(problem);
            if (Widgets.primaryButtonInline("Create " + player.clip)) createClip(entity, player);
            return;
        }
        if (previewed != entity) {
            startPreview(entity);
            pose(entity);
        }

        drawToolbar(entity, player);
        // move time on while playing; otherwise, when recording, turn the user's changes into keys
        if (playing) {
            time += ImGui.getIO().getDeltaTime();
            if (!clip.loop && time >= clip.length) {
                time = clip.length;
                playing = false;
            }
            pose(entity);
        } else if (recording) {
            record(entity);
        } else {
            keepEdits(entity);
        }
        drawTimeline(entity);
        drawSelection(entity);
    }

    private void drawToolbar(Entity entity, AnimationPlayer player) {
        float scale = ImGui.getFontSize() / 15f;
        // the clip, and switching to another
        ImGui.setNextItemWidth(220 * scale);
        List<String> clips = new ArrayList<>(ctx.project.assets(AssetKind.ANIMATION));
        if (!clips.contains(clipPath)) clips.add(0, clipPath);
        if (ImGui.beginCombo("##clip", Path.of(clipPath).getFileName().toString())) {
            for (String c : clips) {
                if (ImGui.selectable(c, c.equals(clipPath)) && !c.equals(clipPath)) {
                    player.clip = c;
                    ctx.markDirty();
                }
            }
            ImGui.separator();
            if (ImGui.selectable("New animation...")) createClip(entity, player);
            ImGui.endCombo();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("The animation " + entity.name + "'s Animation Player plays");
        ImGui.sameLine();
        if (ImGui.button("|<")) {
            time = 0;
            playing = false;
            pose(entity);
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Back to the start");
        ImGui.sameLine();
        if (playing) Widgets.pushAccent();
        if (ImGui.button(playing ? "Pause" : "Play")) {
            playing = !playing;
            if (playing) {
                recording = false;
                if (time >= clip.length && !clip.loop) time = 0;
            }
        }
        if (playing) Widgets.popAccent();
        ImGui.sameLine();
        boolean wasRecording = recording;
        if (wasRecording) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, EditorStyle.pack(Theme.ERROR));
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, EditorStyle.pack(Theme.ERROR.brighter()));
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, EditorStyle.pack(Theme.BLACK));
        }
        if (ImGui.button(recording ? "Recording" : "Record")) {
            recording = !recording;
            playing = false;
            if (recording) pose(entity); // start from the clip's pose, so only real changes become keys
        }
        if (wasRecording) ImGui.popStyleColor(3);
        if (ImGui.isItemHovered()) ImGui.setTooltip("While on, moving, turning, scaling or recolouring " + entity.name + " (gizmo or Inspector) sets a key at the current time");
        ImGui.sameLine();
        ImGui.alignTextToFramePadding();
        ImGui.textColored(EditorStyle.pack(Theme.TEXT_MUTED), String.format("%.2f s", clip.wrap(time)));
        ImGui.sameLine(0, 18 * scale);
        ImGui.textColored(EditorStyle.pack(Theme.TEXT_MUTED), "Length");
        ImGui.sameLine();
        ImGui.setNextItemWidth(70 * scale);
        float[] length = {(float) clip.length};
        if (ImGui.dragFloat("##length", length, 0.01f, 0.05f, 600, "%.2f s")) {
            clip.length = Math.max(0.05, length[0]);
            clipChanged = true;
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Loop", clip.loop)) {
            clip.loop = !clip.loop;
            clipChanged = true;
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(150 * scale);
        if (ImGui.beginCombo("##add", "Add property")) {
            for (Property p : Property.values()) {
                if (clip.tracks.containsKey(p) || AnimationClip.current(ctx.scene(), entity, p) == null) continue;
                if (ImGui.selectable(p.label)) {
                    clip.setKey(p, round(time), AnimationClip.current(ctx.scene(), entity, p));
                    clipChanged = true;
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        if (ImGui.button("Add event")) {
            selectedEvent = new AnimationClip.Event(round(clip.wrap(time)), "onEvent");
            clip.events.add(selectedEvent);
            selectedKey = null;
            clipChanged = true;
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Calls a function in " + entity.name + "'s scripts at this moment (when the game plays it)");
    }

    // ---------------------------------------------------------------- the timeline

    private void drawTimeline(Entity entity) {
        float scale = ImGui.getFontSize() / 15f;
        float row = ImGui.getFrameHeight();
        float names = 150 * scale;
        List<Property> properties = new ArrayList<>(clip.tracks.keySet());
        float rows = properties.size() + 1; // the events row
        float height = row * (rows + 1) + 4;
        ImVec2 origin = ImGui.getCursorScreenPos();
        float width = ImGui.getContentRegionAvailX();
        float left = origin.x + names, right = origin.x + width - 10 * scale;
        double span = Math.max(clip.length, 0.05);
        ImDrawList draw = ImGui.getWindowDrawList();
        ImGuiIO io = ImGui.getIO();
        ImVec2 mouse = ImGui.getMousePos();

        draw.addRectFilled(origin.x, origin.y, origin.x + width, origin.y + height, EditorStyle.pack(Theme.PANEL), 4);
        // the ruler
        float rulerBottom = origin.y + row;
        double step = niceStep(span, (right - left) / (60 * scale));
        for (double t = 0; t <= span + 1e-9; t += step) {
            float x = (float) (left + (right - left) * t / span);
            draw.addLine(x, rulerBottom - 6 * scale, x, origin.y + height, EditorStyle.pack(EditorStyle.withAlpha(Theme.TEXT_FAINT, 60)));
            draw.addText(x + 3, origin.y + 2, EditorStyle.pack(Theme.TEXT_FAINT), trim(t));
        }
        ImGui.setCursorScreenPos(left, origin.y);
        ImGui.invisibleButton("##ruler", right - left, row);
        if (ImGui.isItemActive()) {
            scrubbing = true;
            playing = false;
            time = Math.max(0, Math.min(span, (mouse.x - left) / (right - left) * span));
            if (!io.getKeyCtrl()) time = Math.round(time / SNAP) * SNAP;
            pose(entity);
        } else {
            scrubbing = false;
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Drag to see " + entity.name + " at any moment");

        // the tracks
        Property remove = null;
        for (int i = 0; i <= properties.size(); i++) {
            float top = rulerBottom + row * i + 2;
            boolean eventsRow = i == properties.size();
            Property p = eventsRow ? null : properties.get(i);
            if (i % 2 == 0) draw.addRectFilled(origin.x, top, origin.x + width, top + row, EditorStyle.pack(EditorStyle.withAlpha(Theme.BLACK, 40)));
            draw.addText(origin.x + 8 * scale, top + (row - ImGui.getFontSize()) / 2, EditorStyle.pack(eventsRow ? Theme.TEXT_FAINT : Theme.TEXT),
                    eventsRow ? "Events" : p.label);
            if (!eventsRow) {
                ImGui.setCursorScreenPos(left - row, top);
                ImGui.pushID(p.key);
                if (ImGui.smallButton("x")) remove = p;
                if (ImGui.isItemHovered()) ImGui.setTooltip("Remove the " + p.label + " track");
                // double-click empty space: a key there with the object's value now
                ImGui.setCursorScreenPos(left, top);
                ImGui.invisibleButton("##track", right - left, row);
                if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left) && hoveredKey(p, left, right, span, top, row, mouse) == null) {
                    double at = round(Math.max(0, Math.min(span, (mouse.x - left) / (right - left) * span)));
                    Object value = clip.sample(p, at);
                    selectedKey = clip.setKey(p, at, value != null ? value : AnimationClip.current(ctx.scene(), entity, p));
                    selectedProperty = p;
                    selectedEvent = null;
                    clipChanged = true;
                }
                ImGui.popID();
                drawKeys(p, left, right, span, top, row, draw, mouse, io);
            } else {
                drawEvents(left, right, span, top, row, draw, mouse);
            }
        }
        if (remove != null) {
            clip.tracks.remove(remove);
            if (selectedProperty == remove) selectedKey = null;
            clipChanged = true;
        }
        // the current time
        float now = (float) (left + (right - left) * clip.wrap(time) / span);
        draw.addLine(now, origin.y, now, origin.y + height, EditorStyle.pack(recording ? Theme.ERROR : Theme.ACCENT), 2);
        ImGui.setCursorScreenPos(origin.x, origin.y + height + 4);
        ImGui.dummy(width, 1);
        if (properties.isEmpty()) {
            Widgets.hint("Turn on Record and move " + entity.name + " (or use Add property), then drag the time along and change it again: "
                    + "Sev7n fills in the movement between.");
        }

        if (ImGui.isWindowFocused() && !io.getWantTextInput() && ImGui.isKeyPressed(ImGuiKey.Delete, false)) {
            if (selectedKey != null) {
                clip.removeKey(selectedProperty, selectedKey);
                selectedKey = null;
                clipChanged = true;
                pose(entity);
            } else if (selectedEvent != null) {
                clip.events.remove(selectedEvent);
                selectedEvent = null;
                clipChanged = true;
            }
        }
    }

    private void drawKeys(Property p, float left, float right, double span, float top, float row, ImDrawList draw, ImVec2 mouse, ImGuiIO io) {
        List<Key> keys = clip.tracks.get(p);
        float cy = top + row / 2, size = row * 0.32f;
        // the curve between keys, faintly
        for (int k = 0; k + 1 < keys.size(); k++) {
            float x0 = (float) (left + (right - left) * keys.get(k).time / span), x1 = (float) (left + (right - left) * keys.get(k + 1).time / span);
            draw.addLine(x0, cy, x1, cy, EditorStyle.pack(EditorStyle.withAlpha(Theme.ACCENT, keys.get(k).easing.equals("step") ? 40 : 110)), 2);
        }
        Key hovered = hoveredKey(p, left, right, span, top, row, mouse);
        for (Key key : keys) {
            float x = (float) (left + (right - left) * key.time / span);
            boolean selected = key == selectedKey;
            int color = EditorStyle.pack(selected ? Theme.WARNING : key == hovered ? Theme.ACCENT_HOVER : Theme.ACCENT);
            draw.addQuadFilled(x, cy - size, x + size, cy, x, cy + size, x - size, cy, color);
            if (key.value instanceof Color c) draw.addCircleFilled(x, cy, size * 0.45f, EditorStyle.pack(c));
        }
        if (hovered != null) {
            ImGui.setTooltip(p.label + " " + describe(hovered.value) + " at " + trim(hovered.time) + " s  (" + hovered.easing + ")");
            if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                selectedKey = hovered;
                selectedProperty = p;
                selectedEvent = null;
                draggingKey = true;
            }
            if (ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
                selectedKey = hovered;
                selectedProperty = p;
                ImGui.openPopup("##key");
            }
        }
        if (draggingKey && selectedProperty == p && selectedKey != null) {
            if (ImGui.isMouseDown(0)) {
                double t = Math.max(0, (mouse.x - left) / (right - left) * span);
                selectedKey.time = io.getKeyCtrl() ? Math.round(t * 1000) / 1000.0 : Math.round(t / SNAP) * SNAP;
                AnimationClip.sort(keys);
                clipChanged = true;
            } else {
                draggingKey = false;
                if (selectedKey.time > clip.length) clip.length = selectedKey.time;
                if (previewed != null) pose(previewed);
            }
        }
        if (selectedProperty == p && ImGui.beginPopup("##key")) {
            if (selectedKey != null) {
                ImGui.textColored(EditorStyle.pack(Theme.TEXT_MUTED), "Curve to the next key");
                for (String easing : EASINGS) {
                    if (ImGui.selectable(easing, easing.equals(selectedKey.easing))) {
                        selectedKey.easing = easing;
                        clipChanged = true;
                    }
                }
                ImGui.separator();
                if (ImGui.selectable("Delete key")) {
                    clip.removeKey(p, selectedKey);
                    selectedKey = null;
                    clipChanged = true;
                }
            }
            ImGui.endPopup();
        }
    }

    private Key hoveredKey(Property p, float left, float right, double span, float top, float row, ImVec2 mouse) {
        if (mouse.y < top || mouse.y > top + row) return null;
        Key best = null;
        float bestDistance = row * 0.45f;
        for (Key key : clip.tracks.get(p)) {
            float x = (float) (left + (right - left) * key.time / span);
            float d = Math.abs(mouse.x - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = key;
            }
        }
        return best;
    }

    private void drawEvents(float left, float right, double span, float top, float row, ImDrawList draw, ImVec2 mouse) {
        for (AnimationClip.Event e : clip.events) {
            float x = (float) (left + (right - left) * e.time / span);
            boolean hovered = Math.abs(mouse.x - x) < row * 0.4f && mouse.y >= top && mouse.y <= top + row;
            int color = EditorStyle.pack(e == selectedEvent ? Theme.WARNING : hovered ? Theme.ACCENT_HOVER : Theme.TEXT_MUTED);
            draw.addRectFilled(x - 1, top + 3, x + 1, top + row - 3, color);
            draw.addTriangleFilled(x + 1, top + 3, x + row * 0.45f, top + row * 0.3f, x + 1, top + row * 0.55f, color);
            if (hovered) {
                ImGui.setTooltip(e.function + "() at " + trim(e.time) + " s");
                if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                    selectedEvent = e;
                    selectedKey = null;
                }
            }
        }
    }

    /** The selected key's (or event's) details, to type exact numbers. */
    private void drawSelection(Entity entity) {
        if (selectedEvent != null && clip.events.contains(selectedEvent)) {
            ImGui.alignTextToFramePadding();
            ImGui.textColored(EditorStyle.pack(Theme.TEXT_MUTED), "Event at");
            ImGui.sameLine();
            ImGui.setNextItemWidth(80);
            float[] at = {(float) selectedEvent.time};
            if (ImGui.dragFloat("##eventTime", at, 0.01f, 0, (float) clip.length, "%.2f s")) {
                selectedEvent.time = Math.max(0, Math.min(clip.length, at[0]));
                clipChanged = true;
            }
            ImGui.sameLine();
            ImGui.textColored(EditorStyle.pack(Theme.TEXT_MUTED), "calls");
            ImGui.sameLine();
            ImGui.setNextItemWidth(180);
            eventName.set(selectedEvent.function);
            if (ImGui.inputText("##eventName", eventName)) {
                String name = eventName.get().replaceAll("[^A-Za-z0-9_]", "");
                if (!name.isEmpty()) {
                    selectedEvent.function = name;
                    clipChanged = true;
                }
            }
            ImGui.sameLine();
            ImGui.textColored(EditorStyle.pack(Theme.TEXT_FAINT), "()  in " + entity.name + "'s scripts");
            return;
        }
        if (selectedKey == null || selectedProperty == null || !clip.tracks.containsKey(selectedProperty)
                || !clip.tracks.get(selectedProperty).contains(selectedKey)) return;
        ImGui.alignTextToFramePadding();
        ImGui.textColored(EditorStyle.pack(Theme.TEXT_MUTED), selectedProperty.label + " key at");
        ImGui.sameLine();
        ImGui.setNextItemWidth(80);
        float[] at = {(float) selectedKey.time};
        if (ImGui.dragFloat("##keyTime", at, 0.01f, 0, 600, "%.2f s")) {
            selectedKey.time = Math.max(0, at[0]);
            AnimationClip.sort(clip.tracks.get(selectedProperty));
            clipChanged = true;
        }
        ImGui.sameLine();
        ImGui.textColored(EditorStyle.pack(Theme.TEXT_MUTED), "is");
        ImGui.sameLine();
        ImGui.setNextItemWidth(140);
        boolean changed = false;
        if (selectedKey.value instanceof Double v) {
            float[] value = {v.floatValue()};
            if (ImGui.dragFloat("##keyValue", value, selectedProperty.name().startsWith("ROTATION") ? 0.5f : 0.02f)) {
                selectedKey.value = (double) value[0];
                changed = true;
            }
        } else if (selectedKey.value instanceof Color c) {
            float[] rgba = {c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f};
            if (ImGui.colorEdit4("##keyColor", rgba, ImGuiColorEditFlags.DisplayHex | ImGuiColorEditFlags.AlphaBar)) {
                selectedKey.value = new Color(rgba[0], rgba[1], rgba[2], rgba[3]);
                changed = true;
            }
        } else if (selectedKey.value instanceof Boolean on) {
            if (ImGui.checkbox("##keyOn", on)) {
                selectedKey.value = !on;
                changed = true;
            }
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        if (ImGui.beginCombo("##keyEasing", selectedKey.easing)) {
            for (String easing : EASINGS) {
                if (ImGui.selectable(easing, easing.equals(selectedKey.easing))) {
                    selectedKey.easing = easing;
                    clipChanged = true;
                }
            }
            ImGui.endCombo();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("How it moves from this key to the next: step jumps, the others glide");
        if (changed) {
            clipChanged = true;
            pose(entity);
        }
    }

    // ---------------------------------------------------------------- clip files

    private void loadClip(String path) {
        clipPath = path;
        selectedKey = null;
        selectedEvent = null;
        Path file = ctx.project.root().resolve(path);
        try {
            clip = AnimationClip.load(file);
            problem = "";
        } catch (IOException | RuntimeException e) {
            clip = null;
            problem = Files.exists(file) ? "Couldn't read " + path + ": " + e.getMessage() : path + " doesn't exist yet.";
        }
        time = 0;
        clipChanged = false;
    }

    private void saveClip() {
        if (!clipChanged || clip == null || clipPath.isBlank()) return;
        try {
            clip.save(ctx.project.root().resolve(clipPath));
            clipChanged = false;
        } catch (IOException e) {
            ctx.log.error("Couldn't save the animation " + clipPath + ": " + e.getMessage());
            clipChanged = false; // said once
        }
    }

    /** A new, empty clip named after the object, played by its Animation Player (added if needed). */
    private void createClip(Entity entity, AnimationPlayer player) {
        if (previewed != null) endPreview();
        String base = "assets/animations/" + AudioPanel.safeName(entity.name);
        String path = base + ".anim";
        for (int n = 2; Files.exists(ctx.project.root().resolve(path)); n++) path = base + "-" + n + ".anim";
        AnimationClip fresh = new AnimationClip();
        fresh.length = 1;
        try {
            fresh.save(ctx.project.root().resolve(path));
        } catch (IOException e) {
            ctx.log.error("Couldn't make the animation: " + e.getMessage());
            return;
        }
        if (player == null) player = entity.add(new AnimationPlayer());
        player.clip = path;
        ctx.markDirty();
        ctx.assetsChanged();
        ctx.log.info("Made " + path + ": turn on Record and move " + entity.name + " to animate it");
        recording = true;
    }

    // ---------------------------------------------------------------- helpers

    private static void setColor(Entity entity, Color c) {
        if (c == null) return;
        MeshRenderer mesh = entity.get(MeshRenderer.class);
        if (mesh != null) mesh.color = c;
        SpriteRenderer sprite = entity.get(SpriteRenderer.class);
        if (sprite != null) sprite.tint = c;
    }

    private static double round(double t) {
        return Math.round(t * 1000) / 1000.0;
    }

    /** A step between ruler marks that leaves about 'marks' of them (0.05, 0.1, 0.25, 0.5, 1, 2, 5...). */
    private static double niceStep(double span, float marks) {
        double raw = span / Math.max(1, marks);
        double[] nice = {0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60};
        for (double n : nice) if (n >= raw) return n;
        return 120;
    }

    private static String trim(double t) {
        String s = String.format("%.2f", t);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String describe(Object value) {
        if (value instanceof Double d) return trim(d);
        if (value instanceof Color c) return sev7n.scene.Json.color(c);
        return String.valueOf(value);
    }
}
