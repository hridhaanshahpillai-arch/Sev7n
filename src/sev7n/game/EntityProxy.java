package sev7n.game;

import sev7n.lang.SevenError;
import sev7n.lang.runtime.NativeFunction;
import sev7n.lang.runtime.SevenObject;
import sev7n.lang.runtime.Style;
import sev7n.lang.runtime.Values;
import sev7n.math.Vec3;
import sev7n.scene.Camera;
import sev7n.scene.Component;
import sev7n.scene.Entity;
import sev7n.scene.MeshRenderer;
import sev7n.scene.Rigidbody;
import sev7n.scene.SpriteAnimator;
import sev7n.scene.SpriteRenderer;
import sev7n.scene.Tilemap;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * 'entity' in scripts (and the 'other' in onCollide).
 *   entity.name  entity.active  entity.tags  entity.transform  entity.position
 *   entity.color  entity.mesh  entity.texture  entity.style = someStyle
 *   entity.hasTag(t)  entity.addTag(t)  entity.removeTag(t)  entity.destroy()
 *   entity.addBehaviour("Name")  entity.behaviour("Name")
 *   physics:   entity.velocity  entity.grounded  entity.push(v)
 *   sprites:   entity.frame  entity.flipX  entity.flipY  entity.play("run")  entity.animation  entity.animationDone
 *   others:    entity.camera  entity.tile(x, y)  entity.setTile(x, y, tile)
 *
 * There is one proxy per entity, so 'other == player' works.
 */
final class EntityProxy implements SevenObject {

    private final Entity entity;
    private final Game game;
    private final TransformProxy transform;

    EntityProxy(Entity entity, Game game) {
        this.entity = entity;
        this.game = game;
        this.transform = new TransformProxy(entity.transform);
    }

    Entity entity() {
        return entity;
    }

    TransformProxy transform() {
        return transform;
    }

    @Override
    public String typeName() {
        return "entity";
    }

    @Override
    public Object getProperty(String name, int line) {
        return switch (name) {
            case "name" -> entity.name;
            case "active" -> entity.active;
            case "tags" -> new ArrayList<Object>(entity.tags);
            case "transform" -> transform;
            case "position" -> entity.transform.position;
            case "color" -> renderer() == null ? null : renderer().color;
            case "mesh" -> renderer() == null ? null : renderer().mesh;
            case "texture" -> texture();
            // physics
            case "velocity" -> requireBody(line).velocity;
            case "grounded" -> requireBody(line).grounded;
            case "push" -> new NativeFunction(name, 1, 1, args -> {
                requireBody(args.line()).velocity.set(requireBody(args.line()).velocity.plus(args.vec3(0)));
                return null;
            });
            // sprites & animation
            case "frame" -> (double) requireSprite(line).frame;
            case "flipX" -> requireSprite(line).flipX;
            case "flipY" -> requireSprite(line).flipY;
            case "play" -> new NativeFunction(name, 1, 1, args -> {
                play(args.string(0), args.line());
                return null;
            });
            case "animation" -> requireAnimator(line).current();
            case "animationDone" -> requireAnimator(line).finished();
            // camera & tilemap
            case "camera" -> entity.get(Camera.class) == null ? null : new CameraProxy(entity.get(Camera.class));
            case "tile" -> new NativeFunction(name, 2, 2, args -> {
                int tile = requireTilemap(args.line()).tile(args.integer(0), args.integer(1));
                return tile == Tilemap.EMPTY ? null : (Object) (double) tile;
            });
            case "setTile" -> new NativeFunction(name, 3, 3, args -> {
                Object tile = args.get(2);
                requireTilemap(args.line()).setTile(args.integer(0), args.integer(1), tile == null ? Tilemap.EMPTY : args.integer(2));
                return null;
            });
            case "hasTag" -> new NativeFunction(name, 1, 1, args -> entity.hasTag(args.string(0)));
            case "addTag" -> new NativeFunction(name, 1, 1, args -> {
                if (!entity.hasTag(args.string(0))) entity.tags.add(args.string(0));
                return null;
            });
            case "removeTag" -> new NativeFunction(name, 1, 1, args -> entity.tags.remove(args.string(0)));
            case "destroy" -> new NativeFunction(name, 0, 0, args -> {
                game.destroy(entity);
                return null;
            });
            case "addBehaviour" -> new NativeFunction(name, 1, 1, args -> game.addBehaviour(entity, args.string(0), args.line()));
            // none if the entity doesn't have that behaviour
            case "behaviour" -> new NativeFunction(name, 1, 1, args -> game.findBehaviour(entity, args.string(0)));
            default -> throw noSuchProperty(name, line);
        };
    }

    @Override
    public void setProperty(String name, Object value, int line) {
        switch (name) {
            case "name" -> entity.name = requireType(value, String.class, name, line);
            case "active" -> entity.active = Values.isTruthy(value);
            case "position" -> entity.transform.position.set(requireType(value, Vec3.class, name, line));
            case "color" -> requireRenderer(line).color = requireType(value, Color.class, name, line);
            case "mesh" -> requireRenderer(line).mesh = requireType(value, String.class, name, line);
            case "texture" -> setTexture(value == null ? "" : requireType(value, String.class, name, line), line);
            case "style" -> applyStyle(requireType(value, Style.class, name, line), line);
            case "velocity" -> requireBody(line).velocity.set(requireType(value, Vec3.class, name, line));
            case "frame" -> requireSprite(line).frame = requireType(value, Double.class, name, line).intValue();
            case "flipX" -> requireSprite(line).flipX = Values.isTruthy(value);
            case "flipY" -> requireSprite(line).flipY = Values.isTruthy(value);
            default -> throw new SevenError("entity." + name + " can't be changed", line);
        }
    }

    /** Styles are CSS-like: 'color' (or 'fill') and 'mesh' are applied to the entity. */
    private void applyStyle(Style style, int line) {
        MeshRenderer renderer = requireRenderer(line);
        renderer.color = style.color("color", style.color("fill", renderer.color));
        if (style.get("mesh") instanceof String mesh) renderer.mesh = mesh;
    }

    /** The picture on this entity's Sprite Renderer or Mesh Renderer, or none. */
    private String texture() {
        SpriteRenderer sprite = entity.get(SpriteRenderer.class);
        if (sprite != null) return sprite.texture;
        return renderer() == null ? null : renderer().texture;
    }

    private void setTexture(String path, int line) {
        SpriteRenderer sprite = entity.get(SpriteRenderer.class);
        if (sprite != null) sprite.texture = path;
        else requireRenderer(line).texture = path;
    }

    private void play(String clip, int line) {
        SpriteAnimator animator = requireAnimator(line);
        if (!animator.play(clip)) {
            List<String> names = animator.clips.stream().map(c -> c.name).toList();
            throw new SevenError("'" + entity.name + "' has no animation called '" + clip + "' (it has " + names + ")", line);
        }
    }

    private Rigidbody requireBody(int line) {
        return require(Rigidbody.class, "a Rigidbody", line);
    }

    private SpriteRenderer requireSprite(int line) {
        return require(SpriteRenderer.class, "a Sprite Renderer", line);
    }

    private SpriteAnimator requireAnimator(int line) {
        return require(SpriteAnimator.class, "a Sprite Animator", line);
    }

    private Tilemap requireTilemap(int line) {
        return require(Tilemap.class, "a Tilemap", line);
    }

    private <T extends Component> T require(Class<T> type, String description, int line) {
        T component = entity.get(type);
        if (component == null) throw new SevenError("'" + entity.name + "' has no " + description.substring(description.indexOf(' ') + 1)
                + ". Add " + description + " to it in the Inspector", line);
        return component;
    }

    private MeshRenderer renderer() {
        return entity.get(MeshRenderer.class);
    }

    private MeshRenderer requireRenderer(int line) {
        MeshRenderer renderer = renderer();
        if (renderer == null) throw new SevenError("'" + entity.name + "' has no Mesh Renderer", line);
        return renderer;
    }

    private static <T> T requireType(Object value, Class<T> type, String property, int line) {
        if (type.isInstance(value)) return type.cast(value);
        String expected = type == Vec3.class ? "vec3" : type == Color.class ? "color"
                : type == Double.class ? "number" : type.getSimpleName().toLowerCase();
        throw new SevenError("entity." + property + " must be a " + expected + ", not " + Values.typeName(value), line);
    }

    @Override
    public String toString() {
        return "<entity " + entity.name + ">";
    }
}
