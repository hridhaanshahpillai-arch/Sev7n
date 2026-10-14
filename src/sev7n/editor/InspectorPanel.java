package sev7n.editor;

import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImString;
import sev7n.eights.GraphFile;
import sev7n.project.AssetKind;
import sev7n.render.Texture;
import sev7n.scene.Animator;
import sev7n.scene.Behaviour;
import sev7n.scene.BoxCollider;
import sev7n.scene.Camera;
import sev7n.scene.CapsuleCollider;
import sev7n.scene.SphereCollider;
import sev7n.scene.Component;
import sev7n.scene.Components;
import sev7n.scene.Entity;
import sev7n.scene.Light;
import sev7n.render.SceneRenderer;
import sev7n.scene.MeshRenderer;
import sev7n.scene.Npc;
import sev7n.scene.Ragdoll;
import sev7n.scene.ParticleEmitter;
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
            case SphereCollider s -> drawSphereCollider(entity, s);
            case Animator a -> drawAnimator(entity, a);
            case CapsuleCollider c -> drawCapsuleCollider(entity, c);
            case ParticleEmitter p -> drawParticleEmitter(p);
            case Npc n -> drawNpc(entity, n);
            case Ragdoll r -> drawRagdoll(entity, r);
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
        double turn = m.turn;
        if (!MeshRenderer.isBuiltIn(mesh)) {
            turn = Widgets.number("Model turn", m.turn, 1f, -360, 360);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Turns the model inside the object (degrees). 180 for characters that face backwards when they walk");
        }
        boolean changed = !mesh.equals(m.mesh) || !color.equals(m.color) || !texture.equals(m.texture) || turn != m.turn;
        m.turn = turn;
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
        double mass = Widgets.number("Mass", r.mass, 0.01f, 0.001f, 10000);
        if (ImGui.isItemHovered()) ImGui.setTooltip("How heavy it is: heavier things push lighter ones aside and are harder to push");
        double gravity = Widgets.number("Gravity", r.gravityScale, 0.01f, -10, 10);
        if (ImGui.isItemHovered()) ImGui.setTooltip("1 = normal, 0 = floats (top-down games, space), 2 = falls twice as hard");
        double bounciness = Widgets.number("Bounciness", r.bounciness, 0.01f, 0, 1);
        double friction = Widgets.number("Friction", r.friction, 0.01f, 0, 1);
        if (ImGui.isItemHovered()) ImGui.setTooltip("How quickly sliding stops: 0 = ice, 1 = rubber");
        double drag = Widgets.number("Drag", r.drag, 0.01f, 0, 50);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Slows it down over time, like air resistance");
        boolean kinematic = Widgets.checkbox("Kinematic", r.kinematic,
                "Moved only by scripts: gravity and bumps don't move it, but it still pushes others (moving platforms, doors)");
        Widgets.label("Lock");
        boolean lockX = r.lockX;
        boolean lockY = r.lockY;
        boolean lockZ = r.lockZ;
        if (ImGui.checkbox("X##lock", lockX)) lockX = !lockX;
        ImGui.sameLine();
        if (ImGui.checkbox("Y##lock", lockY)) lockY = !lockY;
        ImGui.sameLine();
        if (ImGui.checkbox("Z##lock", lockZ)) lockZ = !lockZ;
        if (ImGui.isItemHovered()) ImGui.setTooltip("Never moves along a locked axis (lock Z for 2D games)");
        boolean changed = Widgets.vec3("Start velocity", r.velocity, 0.05f);
        changed |= mass != r.mass || gravity != r.gravityScale || bounciness != r.bounciness || friction != r.friction
                || drag != r.drag || kinematic != r.kinematic || lockX != r.lockX || lockY != r.lockY || lockZ != r.lockZ;
        r.mass = mass;
        r.gravityScale = gravity;
        r.bounciness = bounciness;
        r.friction = friction;
        r.drag = drag;
        r.kinematic = kinematic;
        r.lockX = lockX;
        r.lockY = lockY;
        r.lockZ = lockZ;
        if (!entity.has(BoxCollider.class) && !entity.has(SphereCollider.class) && !entity.has(CapsuleCollider.class)) {
            Widgets.hint("Add a Box, Sphere or Capsule Collider so it can land on and bump into things.");
        }
        return changed;
    }

    // ---------------------------------------------------------------- ragdoll

    private boolean drawRagdoll(Entity entity, Ragdoll r) {
        double stiffness = Widgets.number("Stiffness", r.stiffness, 0.01f, 0, 1);
        if (ImGui.isItemHovered()) ImGui.setTooltip("0 = floppy, 1 = stiff (the joints try to keep the pose it fell in)");
        double friction = Widgets.number("Friction", r.friction, 0.01f, 0, 1);
        if (ImGui.isItemHovered()) ImGui.setTooltip("How quickly it stops sliding along the ground");
        double bounciness = Widgets.number("Bounciness", r.bounciness, 0.01f, 0, 1);
        double blendOut = Widgets.number("Get up blend", r.blendOut, 0.01f, 0, 3);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Seconds to blend from lying limp back into its animation after getting up");
        boolean changed = stiffness != r.stiffness || friction != r.friction || bounciness != r.bounciness || blendOut != r.blendOut;
        r.stiffness = stiffness;
        r.friction = friction;
        r.bounciness = bounciness;
        r.blendOut = blendOut;

        MeshRenderer mesh = entity.get(MeshRenderer.class);
        if (mesh == null || ctx.renderer.model(mesh.mesh).rig == null) {
            Widgets.hint("Needs a Mesh Renderer with a rigged character model (.glb / .gltf with a skeleton).");
        } else if (r.skeleton == null) {
            Widgets.hint("This model doesn't look like a body: no hips with a spine and two legs were found.");
        } else {
            Widgets.hint(r.skeleton.size() + " bones go limp: " + String.join(", ", r.skeleton.names()) + ".");
            if (ctx.playing()) {
                ImGui.setCursorPosX(Widgets.labelWidth());
                if (ImGui.button(r.active ? "Get up" : "Go limp", -1, 0)) r.active = !r.active;
            } else {
                Widgets.hint("Scripts: entity.ragdoll() to go limp, entity.getUp() to stand. Press Play to try the button here.");
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- NPC

    private boolean drawNpc(Entity entity, Npc n) {
        Npc before = (Npc) Components.copy(n);

        sub("Moving");
        n.speed = Widgets.number("Walk speed", n.speed, 0.05f, 0, 100);
        n.runSpeed = Widgets.number("Run speed", n.runSpeed, 0.05f, 0, 100);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Speed when chasing or running away");
        n.turnSpeed = Widgets.number("Turn speed", n.turnSpeed, 1f, 0, 3600);
        if (ImGui.isItemHovered()) ImGui.setTooltip("How fast it turns to face where it's going, degrees per second");
        n.stopDistance = Widgets.number("Stop distance", n.stopDistance, 0.01f, 0.05f, 20);
        if (ImGui.isItemHovered()) ImGui.setTooltip("How close counts as \"there\"");

        sub("When nothing is happening");
        n.idle = Widgets.choice("Idle", n.idle, Npc.IDLE_MODES);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Stand still, wander around, walk between patrol points, or follow a daily routine");
        switch (n.idle) {
            case "Wander" -> n.wanderRadius = Widgets.number("Wander radius", n.wanderRadius, 0.05f, 0.5f, 500);
            case "Patrol" -> {
                n.patrol = Widgets.text("Patrol points", n.patrol, "PointA, PointB, PointC");
                if (ImGui.isItemHovered()) ImGui.setTooltip("Names of objects to walk between, in order (empty objects work well as markers)");
            }
            case "Routine" -> drawRoutine(n);
            default -> { }
        }
        if (!n.idle.equals("Stand") && !n.idle.equals("Routine")) {
            n.pause = Widgets.number("Pause", n.pause, 0.05f, 0, 600);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Seconds it waits at each spot");
        }

        sub("Senses");
        n.notices = Widgets.text("Notices tag", n.notices, "player");
        if (ImGui.isItemHovered()) ImGui.setTooltip("It sees and remembers objects with this tag");
        n.whenSeen = Widgets.choice("When seen", n.whenSeen, Npc.REACTIONS);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Ignore (scripts decide), Chase, Flee, or Watch (turn to face them)");
        n.sightRange = Widgets.number("Sight range", n.sightRange, 0.1f, 0, 1000);
        n.sightAngle = Widgets.number("Sight angle", n.sightAngle, 1f, 1, 360);
        if (ImGui.isItemHovered()) ImGui.setTooltip("How wide it can see, in degrees. 360 = all around. Walls block its view");
        n.hearing = Widgets.number("Hearing", n.hearing, 0.1f, 0, 1000);
        if (ImGui.isItemHovered()) ImGui.setTooltip("How far away it hears makeNoise(position) (times the noise's loudness)");
        n.searchTime = Widgets.number("Search time", n.searchTime, 0.1f, 0, 600);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Seconds it keeps looking where it last saw or heard something");
        n.memory = Widgets.number("Memory", n.memory, 1f, 0, 100000);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Seconds before it forgets someone it hasn't seen. 0 = never forgets");

        List<String> clips = animationNames(entity);
        if (!clips.isEmpty()) {
            sub("Animations");
            n.idleAnimation = animationChoice("Idle anim", n.idleAnimation, clips);
            n.walkAnimation = animationChoice("Walk anim", n.walkAnimation, clips);
            n.runAnimation = animationChoice("Run anim", n.runAnimation, clips);
        }
        Widgets.hint("The purple wedge in the Scene view is what it can see; the faint circle is how far it hears.");

        Map<String, Object> was = new java.util.LinkedHashMap<>();
        Map<String, Object> now = new java.util.LinkedHashMap<>();
        before.save(was);
        n.save(now);
        return !was.equals(now);
    }

    private void drawRoutine(Npc n) {
        List<String> places = new ArrayList<>();
        for (Entity e : ctx.scene().entities()) places.add(e.name);
        Npc.RoutineStep remove = null;
        for (Npc.RoutineStep step : n.routine) {
            ImGui.pushID(System.identityHashCode(step));
            ImGui.separator();
            step.hour = Widgets.number("At hour", step.hour, 0.05f, 0, 23.99f);
            if (ImGui.isItemHovered()) ImGui.setTooltip("0-24: 8 = 8 in the morning, 13.5 = half past one, 20 = 8 at night");
            step.place = Widgets.choice("Go to", step.place, places);
            step.activity = Widgets.text("And do", step.activity, "work");
            if (ImGui.isItemHovered()) ImGui.setTooltip("A word for what it's doing there: scripts read it as entity.activity");
            ImGui.setCursorPosX(Widgets.labelWidth());
            if (ImGui.smallButton("Remove step")) remove = step;
            ImGui.popID();
        }
        if (remove != null) n.routine.remove(remove);
        ImGui.separator();
        ImGui.setCursorPosX(Widgets.labelWidth());
        if (ImGui.button("+ Routine step", -1, 0)) {
            double hour = n.routine.isEmpty() ? 8 : Math.min(23, n.routine.get(n.routine.size() - 1).hour + 4);
            n.routine.add(new Npc.RoutineStep(hour, "", ""));
        }
        Widgets.hint("Games start at 8:00 and a day lasts 10 minutes. Scripts can change that with setHour(h) and setDayLength(seconds).");
    }

    /** The animations an entity can play: its model's (Animator) or its sprite clips (Sprite Animator). */
    private static List<String> animationNames(Entity entity) {
        Animator model = entity.get(Animator.class);
        if (model != null) return model.available();
        SpriteAnimator sprite = entity.get(SpriteAnimator.class);
        List<String> names = new ArrayList<>();
        if (sprite != null) for (SpriteAnimator.Clip clip : sprite.clips) names.add(clip.name);
        return names;
    }

    private static String animationChoice(String label, String current, List<String> clips) {
        List<String> options = new ArrayList<>();
        options.add("(none)");
        options.addAll(clips);
        String chosen = Widgets.choice(label, current.isEmpty() ? "(none)" : current, options);
        return chosen.equals("(none)") ? "" : chosen;
    }

    /** A small purple heading inside a component. */
    private static void sub(String title) {
        ImGui.spacing();
        ImGui.textColored(EditorStyle.pack(sev7n.ui.Theme.ACCENT), title);
    }

    private boolean drawAnimator(Entity entity, Animator a) {
        MeshRenderer mesh = entity.get(MeshRenderer.class);
        if (mesh == null) {
            Widgets.hint("Needs a Mesh Renderer with an animated model (.glb or .gltf with animations).");
            return false;
        }
        List<String> clips = a.available();
        if (clips.isEmpty()) {
            Widgets.hint(ctx.renderer.model(mesh.mesh).rig == null
                    ? "This model has no animations. Animated .glb / .gltf files (from Mixamo, Blender...) work here."
                    : "Loading the animations...");
            return false;
        }
        String shown = a.clip.isEmpty() ? clips.get(0) : a.clip;
        String clip = Widgets.choice("Animation", shown, clips);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Plays when the game starts. Scripts switch with entity.play(\"Name\")");
        double speed = Widgets.number("Speed", a.speed, 0.01f, 0, 10);
        boolean loop = Widgets.checkbox("Loop", a.loop, "Start again at the end");
        boolean playOnStart = Widgets.checkbox("Play on start", a.playOnStart, "Starts by itself when the game starts");
        double fade = Widgets.number("Blend time", a.fade, 0.01f, 0, 5);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Seconds to blend smoothly from one animation into the next");
        Widgets.hint(clips.size() + " animations. Now playing: " + (a.current().isEmpty() ? "nothing" : a.current()));
        boolean changed = !clip.equals(shown) || speed != a.speed || loop != a.loop || playOnStart != a.playOnStart || fade != a.fade;
        if (!clip.equals(shown)) {
            a.clip = clip;
            a.play(clip); // see it straight away
        }
        a.speed = speed;
        a.loop = loop;
        a.playOnStart = playOnStart;
        a.fade = fade;
        return changed;
    }

    private boolean drawSphereCollider(Entity entity, SphereCollider s) {
        double radius = Widgets.number("Radius", s.radius, 0.01f, 0.001f, 1000);
        boolean changed = Widgets.vec3("Offset", s.offset, 0.02f);
        boolean trigger = Widgets.checkbox("Trigger", s.trigger, "Never solid: things pass through, but onCollide still happens");
        changed |= radius != s.radius || trigger != s.trigger;
        s.radius = radius;
        s.trigger = trigger;
        SceneRenderer.Bounds shape = ctx.renderer.bounds(entity);
        if (shape != null) {
            ImGui.setCursorPosX(Widgets.labelWidth());
            if (ImGui.button("Fit to shape", -1, 0)) {
                s.radius = Math.max(shape.max().x - shape.min().x, Math.max(shape.max().y - shape.min().y, shape.max().z - shape.min().z)) / 2;
                s.offset.set((shape.max().x + shape.min().x) / 2, (shape.max().y + shape.min().y) / 2, (shape.max().z + shape.min().z) / 2);
                changed = true;
            }
        }
        return changed;
    }

    private boolean drawCapsuleCollider(Entity entity, CapsuleCollider c) {
        double radius = Widgets.number("Radius", c.radius, 0.01f, 0.001f, 1000);
        double height = Widgets.number("Height", c.height, 0.01f, 0.001f, 1000);
        if (ImGui.isItemHovered()) ImGui.setTooltip("The whole height, rounded ends included. Stands up along the object's Y");
        boolean changed = Widgets.vec3("Offset", c.offset, 0.02f);
        boolean trigger = Widgets.checkbox("Trigger", c.trigger, "Never solid: things pass through, but onCollide still happens");
        changed |= radius != c.radius || height != c.height || trigger != c.trigger;
        c.radius = radius;
        c.height = Math.max(height, radius * 2);
        c.trigger = trigger;
        SceneRenderer.Bounds shape = ctx.renderer.bounds(entity);
        if (shape != null) {
            ImGui.setCursorPosX(Widgets.labelWidth());
            if (ImGui.button("Fit to shape", -1, 0)) {
                c.height = shape.max().y - shape.min().y;
                c.radius = Math.min(c.height / 2, Math.max(shape.max().x - shape.min().x, shape.max().z - shape.min().z) / 2);
                c.offset.set((shape.max().x + shape.min().x) / 2, (shape.max().y + shape.min().y) / 2, (shape.max().z + shape.min().z) / 2);
                changed = true;
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- particle effects

    private boolean drawParticleEmitter(ParticleEmitter p) {
        boolean changed = false;
        Widgets.label("Preset");
        if (ImGui.beginCombo("##preset", "Start from...")) {
            for (String preset : ParticleEmitter.PRESETS) {
                if (ImGui.selectable(preset)) {
                    p.applyPreset(preset);
                    p.reset();
                    changed = true;
                }
            }
            ImGui.endCombo();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Replace every setting below with a ready-made effect, then tweak it");
        ImGui.setCursorPosX(Widgets.labelWidth());
        if (ImGui.button("Restart", -1, 0)) p.reset();
        if (ImGui.isItemHovered()) ImGui.setTooltip("Clear the particles and play it again from the start");
        Widgets.hint(p.particles().size() + " particles now. Effects play in the Scene view while you edit them.");

        // like Unity's particle modules: each part folds away
        if (section("Main", true)) {
            boolean playOnStart = Widgets.checkbox("Play on start", p.playOnStart, "Starts by itself when the game starts (scripts can use entity.playEffect())");
            boolean loop = Widgets.checkbox("Loop", p.loop, "Keep going forever; otherwise emit for Duration seconds, then stop");
            double duration = p.loop ? p.duration : Widgets.number("Duration", p.duration, 0.01f, 0, 60);
            double lifetime = Widgets.number("Lifetime", p.lifetime, 0.01f, 0.05f, 60);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Seconds each particle lives");
            double lifetimeRandom = Widgets.number("Lifetime +/-", p.lifetimeRandom, 0.01f, 0, 1);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Randomness: 0.3 = up to 30% longer or shorter");
            int maxParticles = Widgets.integer("Max particles", p.maxParticles, 1, 10000);
            boolean destroyWhenDone = Widgets.checkbox("Auto-remove", p.destroyWhenDone,
                    "Delete this object once the effect has stopped and its last particle is gone (one-off effects)");
            changed |= playOnStart != p.playOnStart || loop != p.loop || duration != p.duration || lifetime != p.lifetime
                    || lifetimeRandom != p.lifetimeRandom || maxParticles != p.maxParticles || destroyWhenDone != p.destroyWhenDone;
            p.playOnStart = playOnStart;
            p.loop = loop;
            p.duration = duration;
            p.lifetime = lifetime;
            p.lifetimeRandom = lifetimeRandom;
            p.maxParticles = maxParticles;
            p.destroyWhenDone = destroyWhenDone;
            endSection();
        }

        if (section("Emission", true)) {
            double rate = Widgets.number("Per second", p.rate, 0.5f, 0, 2000);
            if (ImGui.isItemHovered()) ImGui.setTooltip("How many particles come out every second");
            int burst = Widgets.integer("Burst", p.burst, 0, 2000);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Particles that come out all at once when it starts (explosions)");
            changed |= rate != p.rate || burst != p.burst;
            p.rate = rate;
            p.burst = burst;
            endSection();
        }

        if (section("Shape", true)) {
            Widgets.label("Shape");
            ParticleEmitter.Shape shape = p.shape;
            if (ImGui.beginCombo("##shape", shape.label)) {
                for (ParticleEmitter.Shape option : ParticleEmitter.Shape.values()) {
                    if (ImGui.selectable(option.label, option == shape)) shape = option;
                    if (ImGui.isItemHovered()) ImGui.setTooltip(option.description);
                }
                ImGui.endCombo();
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip(shape.description);
            double radius = p.radius;
            double length = p.length;
            if (shape == ParticleEmitter.Shape.CONE || shape == ParticleEmitter.Shape.SPHERE
                    || shape == ParticleEmitter.Shape.HEMISPHERE || shape == ParticleEmitter.Shape.CIRCLE) {
                radius = Widgets.number("Radius", p.radius, 0.01f, 0, 100);
            }
            if (shape == ParticleEmitter.Shape.BOX) changed |= Widgets.vec3("Box size", p.boxSize, 0.02f);
            if (shape == ParticleEmitter.Shape.LINE) length = Widgets.number("Length", p.length, 0.02f, 0, 100);
            if (shape == ParticleEmitter.Shape.CONE || shape == ParticleEmitter.Shape.BOX || shape == ParticleEmitter.Shape.LINE) {
                changed |= Widgets.vec3("Direction", p.direction, 0.02f);
            }
            double spread = Widgets.number("Spread", p.spread, 0.5f, 0, 180);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Degrees particles may stray from their direction: 0 = straight, 180 = any way");
            changed |= shape != p.shape || radius != p.radius || length != p.length || spread != p.spread;
            p.shape = shape;
            p.radius = radius;
            p.length = length;
            p.spread = spread;
            endSection();
        }

        if (section("Movement", false)) {
            double speed = Widgets.number("Speed", p.speed, 0.02f, 0, 200);
            double speedRandom = Widgets.number("Speed +/-", p.speedRandom, 0.01f, 0, 1);
            double gravity = Widgets.number("Gravity", p.gravity, 0.01f, -10, 10);
            if (ImGui.isItemHovered()) ImGui.setTooltip("1 = falls normally, 0 = floats, below 0 = rises (fire, smoke)");
            double drag = Widgets.number("Drag", p.drag, 0.01f, 0, 50);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Slows particles down, like air");
            changed |= speed != p.speed || speedRandom != p.speedRandom || gravity != p.gravity || drag != p.drag;
            p.speed = speed;
            p.speedRandom = speedRandom;
            p.gravity = gravity;
            p.drag = drag;
            endSection();
        }

        if (section("Color & Size", false)) {
            double startSize = Widgets.number("Start size", p.startSize, 0.005f, 0, 50);
            double endSize = Widgets.number("End size", p.endSize, 0.005f, 0, 50);
            Color startColor = Widgets.colorWithAlpha("Start color", p.startColor);
            Color endColor = Widgets.colorWithAlpha("End color", p.endColor);
            boolean randomColors = Widgets.checkbox("Random colors", p.randomColors, "Every particle gets its own bright color (confetti)");
            changed |= startSize != p.startSize || endSize != p.endSize || !startColor.equals(p.startColor)
                    || !endColor.equals(p.endColor) || randomColors != p.randomColors;
            p.startSize = startSize;
            p.endSize = endSize;
            p.startColor = startColor;
            p.endColor = endColor;
            p.randomColors = randomColors;
            endSection();
        }

        if (section("Rotation", false)) {
            boolean randomRotation = Widgets.checkbox("Random start", p.randomRotation, "Each particle starts turned a random way");
            double spin = Widgets.number("Spin", p.spin, 1f, 0, 3600);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Degrees per second each particle turns (either way)");
            double spinRandom = Widgets.number("Spin +/-", p.spinRandom, 0.01f, 0, 1);
            changed |= randomRotation != p.randomRotation || spin != p.spin || spinRandom != p.spinRandom;
            p.randomRotation = randomRotation;
            p.spin = spin;
            p.spinRandom = spinRandom;
            endSection();
        }

        if (section("Collision" + (p.collide ? "" : " (off)") + "###collision", false)) {
            boolean collide = Widgets.checkbox("Bounce", p.collide,
                    "Particles bounce off solid things: colliders that aren't triggers, solid tilemaps and the floor");
            boolean floor = p.floor;
            double floorHeight = p.floorHeight;
            double bounce = p.bounce;
            double lifeLoss = p.lifeLoss;
            if (collide) {
                floor = Widgets.checkbox("Floor", p.floor, "Also bounce off a flat floor at Floor height, even with no collider there");
                if (floor) floorHeight = Widgets.number("Floor height", p.floorHeight, 0.02f, -1000, 1000);
                bounce = Widgets.number("Bounciness", p.bounce, 0.01f, 0, 1);
                if (ImGui.isItemHovered()) ImGui.setTooltip("Speed kept after a bounce: 0 = stops dead, 1 = bounces right back");
                lifeLoss = Widgets.number("Life lost", p.lifeLoss, 0.01f, 0, 1);
                if (ImGui.isItemHovered()) ImGui.setTooltip("How much of its life a particle loses each hit: 1 = it disappears when it hits");
            }
            changed |= collide != p.collide || floor != p.floor || floorHeight != p.floorHeight || bounce != p.bounce || lifeLoss != p.lifeLoss;
            p.collide = collide;
            p.floor = floor;
            p.floorHeight = floorHeight;
            p.bounce = bounce;
            p.lifeLoss = lifeLoss;
            endSection();
        }

        if (section("Renderer", true)) {
            Widgets.label("Particles are");
            ParticleEmitter.Look look = p.look;
            if (ImGui.beginCombo("##look", look.label)) {
                for (ParticleEmitter.Look option : ParticleEmitter.Look.values()) {
                    if (ImGui.selectable(option.label, option == look)) look = option;
                    if (ImGui.isItemHovered()) ImGui.setTooltip(option.description);
                }
                ImGui.endCombo();
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip(look.description);
            boolean glow = p.glow;
            String texture = p.texture;
            String model = p.model;
            if (look == ParticleEmitter.Look.DOT) {
                glow = Widgets.checkbox("Glow", p.glow, "Particles add light where they overlap (fire, sparks, magic) instead of covering each other (smoke)");
                texture = textureField("Picture", p.texture);
            } else {
                List<String> models = new ArrayList<>(MeshRenderer.MESHES);
                models.addAll(ctx.project.assets(AssetKind.MODEL));
                model = acceptDrop(Widgets.choice("Model", p.model, models), AssetKind.MODEL);
            }
            changed |= look != p.look || glow != p.glow || !texture.equals(p.texture) || !model.equals(p.model);
            p.look = look;
            p.glow = glow;
            p.texture = texture;
            p.model = model;
            endSection();
        }
        return changed;
    }

    /**
     * A foldable part of a component (like Unity's particle modules). Its contents aren't
     * indented, so labels line up with the rest. Call endSection() if it returns true.
     */
    private static boolean section(String title, boolean openAtFirst) {
        ImGui.spacing();
        return ImGui.treeNodeEx(title, ImGuiTreeNodeFlags.Framed | ImGuiTreeNodeFlags.SpanAvailWidth
                | ImGuiTreeNodeFlags.NoTreePushOnOpen | (openAtFirst ? ImGuiTreeNodeFlags.DefaultOpen : 0));
    }

    private static void endSection() {
        ImGui.spacing();
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
