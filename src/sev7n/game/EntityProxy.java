package sev7n.game;

import sev7n.lang.SevenError;
import sev7n.lang.runtime.NativeFunction;
import sev7n.lang.runtime.SevenObject;
import sev7n.lang.runtime.Style;
import sev7n.lang.runtime.Values;
import sev7n.math.Vec3;
import sev7n.scene.Animator;
import sev7n.scene.Camera;
import sev7n.scene.Component;
import sev7n.scene.Entity;
import sev7n.scene.Light;
import sev7n.scene.MeshRenderer;
import sev7n.scene.ParticleEmitter;
import sev7n.scene.Ragdoll;
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
 *   entity.addBehaviour("Name")  entity.behaviour("Name")  entity.send("onHit", 25)
 *   physics:   entity.velocity  entity.grounded  entity.push(v)  entity.addForce(v)  entity.addImpulse(v)
 *              entity.mass  entity.kinematic
 *   sprites:   entity.frame  entity.flipX  entity.flipY  entity.play("run")  entity.animation  entity.animationDone
 *   models:    entity.play("Walk", [fade])  entity.animation  entity.animationDone  entity.animationSpeed  entity.animations
 *   ragdolls:  entity.ragdoll([push])  entity.getUp()  entity.ragdolling
 *   effects:   entity.emit(n)  entity.playEffect()  entity.stopEffect()  entity.effectDone
 *   NPCs:      entity.moveTo(place)  entity.follow(e)  entity.chase(e)  entity.flee(e)  entity.stay()  entity.resume()
 *              entity.state  entity.activity  entity.target  entity.arrived  entity.canSee(e)  entity.seen
 *              entity.remember(e, "event", [opinion])  entity.remembers(e, "event")  entity.opinion(e)  entity.setOpinion(e, n)
 *              entity.knows(e)  entity.lastSeen(e)  entity.forget(e)
 *   light:     entity.lightIntensity  entity.lightColor  entity.lightAmbient
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

    /** Everything after "entity." (for "did you mean ...?"). */
    private static final List<String> PROPERTIES = List.of("name", "active", "tags", "transform", "position", "color", "mesh", "texture", "style",
            "velocity", "grounded", "push", "addForce", "addImpulse", "mass", "kinematic", "frame", "flipX", "flipY", "play", "animation",
            "animationDone", "animationSpeed", "animations", "ragdoll", "getUp", "ragdolling", "ragdollResting", "emit", "playEffect", "stopEffect", "effectDone",
            "moveTo", "follow", "chase", "flee", "stay", "resume", "state", "activity", "target", "arrived", "seen", "canSee", "remember",
            "remembers", "opinion", "setOpinion", "knows", "lastSeen", "forget", "lightIntensity", "lightColor", "lightAmbient", "camera",
            "tile", "setTile", "hasTag", "addTag", "removeTag", "destroy", "send", "addBehaviour", "behaviour", "parent", "children");

    @Override
    public java.util.Collection<String> propertyNames() {
        return PROPERTIES;
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
            // a steady push (call it every frame, like an engine), and a sudden one (a kick or explosion); heavier things move less
            case "addForce" -> new NativeFunction(name, 1, 1, args -> {
                Rigidbody body = requireBody(args.line());
                body.force.set(body.force.plus(args.vec3(0)));
                return null;
            });
            case "addImpulse" -> new NativeFunction(name, 1, 1, args -> {
                Rigidbody body = requireBody(args.line());
                body.velocity.set(body.velocity.plus(args.vec3(0).times(body.inverseMass())));
                return null;
            });
            case "mass" -> requireBody(line).mass;
            case "kinematic" -> requireBody(line).kinematic;
            // sprites & animation
            case "frame" -> (double) requireSprite(line).frame;
            case "flipX" -> requireSprite(line).flipX;
            case "flipY" -> requireSprite(line).flipY;
            // play("Run") or play("Run", 0.1): a 3D model's animation (with an Animator) or sprite frames (a Sprite Animator)
            case "play" -> new NativeFunction(name, 1, 2, args -> {
                play(args.string(0), args.has(1) ? args.number(1) : -1, args.line());
                return null;
            });
            case "animation" -> entity.has(Animator.class) ? entity.get(Animator.class).current() : requireAnimator(line).current();
            case "animationDone" -> entity.has(Animator.class) ? entity.get(Animator.class).done() : requireAnimator(line).finished();
            case "animationSpeed" -> requireModelAnimator(line).speed;
            case "animations" -> new ArrayList<Object>(requireModelAnimator(line).available());
            // ragdolls: go limp (optionally knocked by a push), and stand back up
            case "ragdoll" -> new NativeFunction(name, 0, 1, args -> {
                Ragdoll ragdoll = requireRagdoll(args.line());
                if (!ragdoll.active && args.has(0)) {
                    Vec3 push = args.vec3(0);
                    ragdoll.pushX = push.x;
                    ragdoll.pushY = push.y;
                    ragdoll.pushZ = push.z;
                }
                ragdoll.active = true;
                return null;
            });
            case "getUp" -> new NativeFunction(name, 0, 0, args -> {
                requireRagdoll(args.line()).active = false;
                return null;
            });
            case "ragdolling" -> requireRagdoll(line).active;
            // true once a limp body has stopped moving (landed and lying still): a good moment to getUp()
            case "ragdollResting" -> {
                requireRagdoll(line);
                yield game.ragdollResting(entity);
            }
            // particle effects
            case "emit" -> new NativeFunction(name, 1, 1, args -> {
                requireEmitter(args.line()).emit(args.integer(0), entity.transform);
                return null;
            });
            case "playEffect" -> new NativeFunction(name, 0, 0, args -> {
                requireEmitter(args.line()).play();
                return null;
            });
            case "stopEffect" -> new NativeFunction(name, 0, 0, args -> {
                requireEmitter(args.line()).stop();
                return null;
            });
            case "effectDone" -> requireEmitter(line).done();
            // NPCs
            case "moveTo" -> new NativeFunction(name, 1, 1, args -> {
                if (args.get(0) instanceof EntityProxy other) requireBrain(args.line()).moveTo(other.entity());
                else requireBrain(args.line()).moveTo(args.vec3(0));
                return null;
            });
            case "follow" -> order(name, NpcBrain::follow);
            case "chase" -> order(name, NpcBrain::chase);
            case "flee" -> order(name, NpcBrain::flee);
            case "stay" -> new NativeFunction(name, 0, 0, args -> {
                requireBrain(args.line()).stay();
                return null;
            });
            case "resume" -> new NativeFunction(name, 0, 0, args -> {
                requireBrain(args.line()).resume();
                return null;
            });
            case "state" -> requireBrain(line).state();
            case "activity" -> requireBrain(line).activity();
            case "target" -> requireBrain(line).focus() == null ? null : game.proxy(requireBrain(line).focus());
            case "arrived" -> requireBrain(line).arrived();
            case "seen" -> {
                List<Object> seen = new ArrayList<>();
                for (Entity e : requireBrain(line).visible) seen.add(game.proxy(e));
                yield seen;
            }
            case "canSee" -> new NativeFunction(name, 1, 1, args -> requireBrain(args.line()).canSee(game.scene(), other(args, 0)));
            case "remember" -> new NativeFunction(name, 2, 3, args -> {
                requireBrain(args.line()).remember(other(args, 0), args.string(1), args.has(2) ? args.number(2) : 0);
                return null;
            });
            case "remembers" -> new NativeFunction(name, 2, 2, args -> {
                NpcBrain.Memory memory = requireBrain(args.line()).memories.get(other(args, 0));
                return memory == null ? 0.0 : memory.events.getOrDefault(args.string(1), 0.0);
            });
            case "opinion" -> new NativeFunction(name, 1, 1, args -> {
                NpcBrain.Memory memory = requireBrain(args.line()).memories.get(other(args, 0));
                return memory == null ? 0.0 : memory.opinion;
            });
            case "setOpinion" -> new NativeFunction(name, 2, 2, args -> {
                requireBrain(args.line()).memoryOf(other(args, 0)).opinion = Math.max(-100, Math.min(100, args.number(1)));
                return null;
            });
            case "knows" -> new NativeFunction(name, 1, 1, args -> requireBrain(args.line()).memories.containsKey(other(args, 0)));
            case "lastSeen" -> new NativeFunction(name, 1, 1, args -> {
                NpcBrain.Memory memory = requireBrain(args.line()).memories.get(other(args, 0));
                return memory == null || memory.lastSeen == null ? null : memory.lastSeen.copy();
            });
            case "forget" -> new NativeFunction(name, 1, 1, args -> {
                requireBrain(args.line()).forget(other(args, 0));
                return null;
            });
            // light (a sun that sets, a flickering lamp)
            case "lightIntensity" -> require(Light.class, "a Directional Light", line).intensity;
            case "lightColor" -> require(Light.class, "a Directional Light", line).color;
            case "lightAmbient" -> require(Light.class, "a Directional Light", line).ambient;
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
            // parents and children: a child follows its parent around
            case "parent" -> {
                Entity parent = entity.parent < 0 ? null : game.scene().findById(entity.parent);
                yield parent == null ? null : game.proxy(parent);
            }
            case "children" -> {
                List<Object> children = new ArrayList<>();
                for (Entity child : sev7n.scene.Hierarchy.children(game.scene(), entity)) children.add(game.proxy(child));
                yield children;
            }
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
            // send("onHit", 25): calls that function (with those values) on every behaviour this object has that has it
            case "send" -> new NativeFunction(name, 1, -1, args -> {
                Object[] rest = new Object[args.size() - 1];
                for (int i = 1; i < args.size(); i++) rest[i - 1] = args.get(i);
                game.send(entity, args.string(0), rest);
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
            case "mass" -> requireBody(line).mass = Math.max(0.001, requireType(value, Double.class, name, line));
            case "kinematic" -> requireBody(line).kinematic = Values.isTruthy(value);
            case "frame" -> requireSprite(line).frame = requireType(value, Double.class, name, line).intValue();
            case "lightIntensity" -> require(Light.class, "a Directional Light", line).intensity = Math.max(0, requireType(value, Double.class, name, line));
            case "lightColor" -> require(Light.class, "a Directional Light", line).color = requireType(value, Color.class, name, line);
            case "lightAmbient" -> require(Light.class, "a Directional Light", line).ambient = Math.max(0, requireType(value, Double.class, name, line));
            case "animationSpeed" -> requireModelAnimator(line).speed = requireType(value, Double.class, name, line);
            case "flipX" -> requireSprite(line).flipX = Values.isTruthy(value);
            case "flipY" -> requireSprite(line).flipY = Values.isTruthy(value);
            // entity.parent = other (it follows other from now on, staying where it is) or none
            case "parent" -> {
                Entity parent = value == null ? null : requireType(value, EntityProxy.class, name, line).entity();
                if (!sev7n.scene.Hierarchy.setParent(game.scene(), entity, parent)) {
                    throw new SevenError(entity.name + " can't be a child of " + parent.name + ": " + parent.name + " is already inside " + entity.name, line);
                }
            }
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

    private void play(String clip, double fade, int line) {
        Animator model = entity.get(Animator.class);
        if (model != null) {
            boolean found = fade >= 0 ? model.play(clip, fade) : model.play(clip);
            if (!found) throw new SevenError("'" + entity.name + "' has no animation called '" + clip + "' (it has " + model.available() + ")", line);
            NpcBrain brain = game.brain(entity);
            if (brain != null) brain.hold(clip); // an NPC doesn't switch back to walking until it's done
            return;
        }
        SpriteAnimator animator = requireAnimator(line);
        if (!animator.play(clip)) {
            List<String> names = animator.clips.stream().map(c -> c.name).toList();
            throw new SevenError("'" + entity.name + "' has no animation called '" + clip + "' (it has " + names + ")", line);
        }
    }

    private NativeFunction order(String name, java.util.function.BiConsumer<NpcBrain, Entity> give) {
        return new NativeFunction(name, 1, 1, args -> {
            give.accept(requireBrain(args.line()), other(args, 0));
            return null;
        });
    }

    private static Entity other(sev7n.lang.runtime.Args args, int index) {
        if (args.get(index) instanceof EntityProxy proxy) return proxy.entity();
        throw args.error("expected an entity, not " + Values.typeName(args.get(index)));
    }

    private NpcBrain requireBrain(int line) {
        NpcBrain brain = game.brain(entity);
        if (brain == null) throw new SevenError("'" + entity.name + "' has no NPC. Add an NPC to it in the Inspector", line);
        return brain;
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

    private Animator requireModelAnimator(int line) {
        return require(Animator.class, "an Animator", line);
    }

    private ParticleEmitter requireEmitter(int line) {
        return require(ParticleEmitter.class, "a Particle Effect", line);
    }

    private Ragdoll requireRagdoll(int line) {
        return require(Ragdoll.class, "a Ragdoll", line);
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
