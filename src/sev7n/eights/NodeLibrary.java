package sev7n.eights;

import sev7n.math.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static sev7n.eights.NodeType.Category.*;
import static sev7n.eights.ValueType.*;

/**
 * Every node type in Eights, and the Seven code each one becomes.
 * To add a node: define it here with builder(...) (or one of the shortcuts: action, actionOut, value),
 * and it shows up in the editor's menu.
 *
 * Eights should be able to do everything Seven can: when a feature is added to Seven, add its node here.
 */
public final class NodeLibrary {

    public static final String ON_START = "event.start";
    public static final String ON_UPDATE = "event.update";
    public static final String ON_COLLIDE = "event.collide";
    public static final String KEY_PRESSED = "event.keyPressed";
    public static final String KEY_HELD = "event.keyHeld";
    public static final String GET_VARIABLE = "variable.get";
    public static final String SET_VARIABLE = "variable.set";

    // (these come before the static block below, which uses them)
    private static final Pin TARGET = new Pin("Target", ENTITY, null);
    private static final Color WHITE = new Color(0xF8F8F2);

    private static final Map<String, NodeType> TYPES = new LinkedHashMap<>();
    /** Nodes are built once every one is defined, so a shortcut's result can still get .keywords(...). */
    private static final List<NodeType.Builder> PENDING = new ArrayList<>();

    static {
        events();
        flow();
        objects();
        physics();
        input();
        characters();
        hud();
        sound();
        scenes();
        world();
        values();
        math();
        text();
        variables();
        for (NodeType.Builder builder : PENDING) {
            NodeType type = builder.build();
            if (TYPES.put(type.id, type) != null) throw new IllegalStateException("two nodes with the id " + type.id);
        }
        PENDING.clear();
    }

    private NodeLibrary() { }

    public static NodeType get(String id) {
        return TYPES.get(id);
    }

    public static List<NodeType> all() {
        return Collections.unmodifiableList(new ArrayList<>(TYPES.values()));
    }

    private static void add(NodeType.Builder builder) {
        PENDING.add(builder);
    }

    // ---------------------------------------------------------------- shortcuts

    /** A data input: its name, type and the value it has until something is typed or wired in. */
    private record Pin(String name, ValueType type, Object value) { }

    private static Pin pin(String name, ValueType type, Object value) {
        return new Pin(name, type, value);
    }

    /** "{0}.addTag({1})" with each {i} replaced by data input i's code. 'first' is the index of data input 0. */
    private static String fill(String template, Compiler.Context code, Node n, int first, int count) {
        String out = template;
        for (int i = count - 1; i >= 0; i--) out = out.replace("{" + i + "}", code.input(n, first + i));
        return out;
    }

    /** A node that does something and carries on: exec in, data inputs, exec out. */
    private static NodeType.Builder action(String id, String title, NodeType.Category category, String description, String template, Pin... inputs) {
        NodeType.Builder b = NodeType.builder(id, title, category).describe(description).execIn();
        for (Pin p : inputs) b.in(p.name, p.type, p.value);
        b.execOut("");
        b.emit((code, n) -> {
            code.line(fill(template, code, n, 1, inputs.length), n);
            code.follow(n, 0);
        });
        add(b);
        return b;
    }

    /** An action whose result can be used later (what Spawn made, what a slider is set to). */
    private static NodeType.Builder actionOut(String id, String title, NodeType.Category category, String description, String template,
                                              String outName, ValueType outType, Pin... inputs) {
        NodeType.Builder b = NodeType.builder(id, title, category).describe(description).execIn();
        for (Pin p : inputs) b.in(p.name, p.type, p.value);
        b.execOut("").out(outName, outType);
        b.fields(n -> List.of("var _n" + n.id + " = none"));
        b.emit((code, n) -> {
            code.line(code.temp(n) + " = " + fill(template, code, n, 1, inputs.length), n);
            code.follow(n, 0);
        });
        b.value((code, n, out) -> code.temp(n));
        add(b);
        return b;
    }

    /** A node that just gives information: data inputs, one data output. */
    private static NodeType.Builder value(String id, String title, NodeType.Category category, String description,
                                          String outName, ValueType outType, String template, Pin... inputs) {
        NodeType.Builder b = NodeType.builder(id, title, category).describe(description);
        for (Pin p : inputs) b.in(p.name, p.type, p.value);
        b.out(outName, outType);
        b.value((code, n, out) -> fill(template, code, n, 0, inputs.length));
        add(b);
        return b;
    }

    private static NodeType.EventFunction function(String name, String params) {
        return new NodeType.EventFunction(name, params, "");
    }

    // ---------------------------------------------------------------- events: where chains start

    private static void events() {
        add(NodeType.builder(ON_START, "On Start", EVENTS).describe("Runs once when Play starts")
                .execOut(""));
        add(NodeType.builder(ON_UPDATE, "On Update", EVENTS).describe("Runs every frame")
                .execOut("").out("Delta Time", NUMBER)
                .value((code, n, out) -> "_dt"));
        add(NodeType.builder("event.lateUpdate", "On Late Update", EVENTS)
                .describe("Every frame after everything has moved: cameras that follow, HUDs that track things")
                .execOut("").out("Delta Time", NUMBER)
                .event((code, n) -> new NodeType.EventFunction("lateUpdate", "dt", "_dt = dt"))
                .value((code, n, out) -> "dt"));
        add(NodeType.builder("event.fixedUpdate", "On Fixed Update", EVENTS)
                .describe("Exactly 60 times a second, in step with physics: steady forces, multiplayer-ready logic")
                .execOut("").out("Delta Time", NUMBER)
                .event((code, n) -> new NodeType.EventFunction("fixedUpdate", "dt", "_dt = dt"))
                .value((code, n, out) -> "dt"));
        add(NodeType.builder(ON_COLLIDE, "On Collide", EVENTS).describe("When this object's collider starts touching another")
                .execOut("").out("Other", ENTITY)
                .event((code, n) -> function("onCollide", "other"))
                .value((code, n, out) -> "other"));
        add(NodeType.builder("event.collideEnd", "On Collide End", EVENTS).describe("When this object's collider stops touching another (leaving a zone, stepping off)")
                .execOut("").out("Other", ENTITY).keywords("exit leave stop touching trigger")
                .event((code, n) -> function("onCollideEnd", "other"))
                .value((code, n, out) -> "other"));
        add(NodeType.builder(KEY_PRESSED, "On Key Pressed", EVENTS).describe("The frame a key goes down")
                .in("Key", STRING, "space").execOut("")
                .inUpdate((code, n) -> code.block("if keyPressed(" + code.input(n, 0) + ") {", n, 0)));
        add(NodeType.builder("event.keyReleased", "On Key Released", EVENTS).describe("The frame a key comes back up")
                .in("Key", STRING, "space").execOut("")
                .inUpdate((code, n) -> code.block("if keyReleased(" + code.input(n, 0) + ") {", n, 0)));
        add(NodeType.builder(KEY_HELD, "While Key Held", EVENTS).describe("Every frame while a key is down")
                .in("Key", STRING, "w").execOut("")
                .inUpdate((code, n) -> code.block("if key(" + code.input(n, 0) + ") {", n, 0)));
        add(NodeType.builder("event.mouseClicked", "On Mouse Click", EVENTS).describe("The frame a mouse button is clicked (0 = left, 1 = right)")
                .in("Button", NUMBER, 0.0).execOut("")
                .inUpdate((code, n) -> code.block("if mouseClicked(" + code.input(n, 0) + ") {", n, 0)));
        add(NodeType.builder("event.every", "Every N Seconds", EVENTS).describe("Runs again and again, this many seconds apart (spawners, ticking clocks)")
                .in("Seconds", NUMBER, 1.0).execOut("").keywords("timer repeat interval tick")
                .fields(n -> List.of("var _t" + n.id + " = 0"))
                .inUpdate((code, n) -> {
                    String timer = "_t" + n.id;
                    code.line(timer + " += dt", n);
                    code.line("if " + timer + " >= " + code.input(n, 0) + " {", n);
                    code.indent();
                    code.line(timer + " = 0", n);
                    code.follow(n, 0);
                    code.dedent();
                    code.line("}", n);
                }));
        add(NodeType.builder("event.message", "On Message", EVENTS)
                .describe("When something calls this name with Send Message (or a script does entity.send(\"name\", ...)). Up to 3 values come with it")
                .in("Name", STRING, "onHit").execOut("").out("Value 1", ANY).out("Value 2", ANY).out("Value 3", ANY)
                .keywords("send call function custom event receive")
                .event((code, n) -> {
                    String name = Compiler.identifier(code.typed(n, 0));
                    if (name == null || List.of("start", "update", "lateUpdate", "fixedUpdate").contains(name)) name = "message_" + n.id;
                    return function(name, "_v1 = none, _v2 = none, _v3 = none");
                })
                .value((code, n, out) -> "_v" + out));
        // NPCs
        add(NodeType.builder("event.see", "On See", EVENTS).describe("An NPC sees something it notices (its NPC component's Notices tag)")
                .execOut("").out("Seen", ENTITY).event((code, n) -> function("onSee", "other")).value((code, n, out) -> "other"));
        add(NodeType.builder("event.lose", "On Lose Sight", EVENTS).describe("An NPC can't see something any more")
                .execOut("").out("Lost", ENTITY).event((code, n) -> function("onLose", "other")).value((code, n, out) -> "other"));
        add(NodeType.builder("event.hear", "On Hear", EVENTS).describe("An NPC hears a noise (Make Noise, or a gunshot)")
                .execOut("").out("Where", VEC3).out("Source", ENTITY)
                .event((code, n) -> function("onHear", "position, source"))
                .value((code, n, out) -> out == 1 ? "position" : "source"));
        add(NodeType.builder("event.reach", "On Reach", EVENTS).describe("An NPC catches up with what it was chasing or following")
                .execOut("").out("Target", ENTITY).event((code, n) -> function("onReach", "target")).value((code, n, out) -> "target"));
        add(NodeType.builder("event.arrive", "On Arrive", EVENTS).describe("An NPC gets to where it was sent")
                .execOut("").event((code, n) -> function("onArrive", "")));
        add(NodeType.builder("event.routine", "On Routine", EVENTS).describe("An NPC's daily routine moves to its next activity")
                .execOut("").out("Activity", STRING).out("Place", STRING)
                .event((code, n) -> function("onRoutine", "activity, place"))
                .value((code, n, out) -> out == 1 ? "activity" : "place"));
    }

    // ---------------------------------------------------------------- flow

    private static void flow() {
        add(NodeType.builder("flow.branch", "Branch", FLOW).describe("If the condition is true, go one way, otherwise the other")
                .execIn().in("Condition", BOOL, true).execOut("True").execOut("False").keywords("if else condition")
                .emit((code, n) -> {
                    code.line("if " + code.input(n, 1) + " {", n);
                    code.indent();
                    code.follow(n, 0);
                    code.dedent();
                    if (code.has(n, 1)) {
                        code.line("} else {", n);
                        code.indent();
                        code.follow(n, 1);
                        code.dedent();
                    }
                    code.line("}", n);
                }));
        add(NodeType.builder("flow.repeat", "Repeat", FLOW).describe("Runs Loop a number of times, then Completed")
                .execIn().in("Times", NUMBER, 3.0).execOut("Loop").out("Index", NUMBER).execOut("Completed").keywords("for loop times")
                .emit((code, n) -> {
                    code.block("for " + loopVar(n) + " in 0.." + code.input(n, 1) + " {", n, 0);
                    code.follow(n, 2);
                })
                .value((code, n, out) -> loopVar(n)));
        add(NodeType.builder("flow.forEach", "For Each", FLOW).describe("Runs Loop once for every item in a list, then Completed")
                .execIn().in("List", LIST, null).execOut("Loop").out("Item", ANY).out("Index", NUMBER).execOut("Completed").keywords("loop list every all")
                .emit((code, n) -> {
                    code.line("var _k" + n.id + " = 0", n);
                    code.line("for " + loopVar(n) + " in " + code.input(n, 1) + " {", n);
                    code.indent();
                    code.follow(n, 0);
                    code.line("_k" + n.id + " += 1", n);
                    code.dedent();
                    code.line("}", n);
                    code.follow(n, 3);
                })
                .value((code, n, out) -> out == 1 ? loopVar(n) : "_k" + n.id));
        add(NodeType.builder("flow.while", "While", FLOW).describe("Runs Loop again and again as long as the condition is true, then Completed")
                .execIn().in("Condition", BOOL, false).execOut("Loop").execOut("Completed").keywords("loop until")
                .emit((code, n) -> {
                    code.block("while " + code.input(n, 1) + " {", n, 0);
                    code.follow(n, 1);
                }));
        add(NodeType.builder("flow.sequence", "Sequence", FLOW).describe("Runs Then 1, then Then 2, then Then 3, in order")
                .execIn().execOut("Then 1").execOut("Then 2").execOut("Then 3").keywords("order steps")
                .emit((code, n) -> {
                    for (int i = 0; i < 3; i++) code.follow(n, i);
                }));
        add(NodeType.builder("flow.doOnce", "Do Once", FLOW).describe("Lets the chain through the first time only")
                .execIn().execOut("").keywords("first time single")
                .fields(n -> List.of("var _once" + n.id + " = false"))
                .emit((code, n) -> {
                    code.line("if not _once" + n.id + " {", n);
                    code.indent();
                    code.line("_once" + n.id + " = true", n);
                    code.follow(n, 0);
                    code.dedent();
                    code.line("}", n);
                }));
        add(NodeType.builder("flow.flipFlop", "Flip Flop", FLOW).describe("Goes to A, then B, then A again... (a light switch)")
                .execIn().execOut("A").execOut("B").out("Is A", BOOL).keywords("toggle switch alternate")
                .fields(n -> List.of("var _ff" + n.id + " = false"))
                .emit((code, n) -> {
                    String flag = "_ff" + n.id;
                    code.line(flag + " = not " + flag, n);
                    code.line("if " + flag + " {", n);
                    code.indent();
                    code.follow(n, 0);
                    code.dedent();
                    code.line("} else {", n);
                    code.indent();
                    code.follow(n, 1);
                    code.dedent();
                    code.line("}", n);
                })
                .value((code, n, out) -> "_ff" + n.id));
        add(NodeType.builder("flow.cooldown", "Cooldown", FLOW).describe("Lets the chain through at most once every so many seconds (a gun's fire rate)")
                .execIn().in("Seconds", NUMBER, 0.5).execOut("").execOut("Blocked").keywords("rate limit wait")
                .fields(n -> List.of("var _cd" + n.id + " = -1000"))
                .emit((code, n) -> {
                    code.line("if time() - _cd" + n.id + " >= " + code.input(n, 1) + " {", n);
                    code.indent();
                    code.line("_cd" + n.id + " = time()", n);
                    code.follow(n, 0);
                    code.dedent();
                    if (code.has(n, 1)) {
                        code.line("} else {", n);
                        code.indent();
                        code.follow(n, 1);
                        code.dedent();
                    }
                    code.line("}", n);
                }));
        add(NodeType.builder("flow.delay", "Delay", FLOW)
                .describe("Waits, then carries on. While it's waiting, it ignores being run again. "
                        + "Values from the event before the Delay (like Other) aren't there afterwards: keep them in variables")
                .execIn().in("Seconds", NUMBER, 1.0).execOut("Completed").keywords("wait timer later after")
                .fields(n -> List.of("var _d" + n.id + " = 0"))
                .emit((code, n) -> {
                    code.line("if _d" + n.id + " <= 0 {", n);
                    code.indent();
                    code.line("_d" + n.id + " = max(" + code.input(n, 1) + ", 0.0001)", n);
                    code.dedent();
                    code.line("}", n);
                })
                .everyFrame((code, n) -> {
                    String timer = "_d" + n.id;
                    code.line("if " + timer + " > 0 {", n);
                    code.indent();
                    code.line(timer + " -= dt", n);
                    code.line("if " + timer + " <= 0 {", n);
                    code.indent();
                    code.line(timer + " = 0", n);
                    code.follow(n, 0);
                    code.dedent();
                    code.line("}", n);
                    code.dedent();
                    code.line("}", n);
                }));
        add(NodeType.builder("flow.stop", "Stop", FLOW).describe("Ends this event here (nothing after it runs)")
                .execIn().keywords("return exit end")
                .emit((code, n) -> code.line("return", n)));
    }

    private static String loopVar(Node n) {
        return "_i" + n.id;
    }

    // ---------------------------------------------------------------- objects: things that happen to objects

    private static void objects() {
        add(NodeType.builder("action.print", "Print", ACTIONS).describe("Writes to the Console")
                .execIn().in("Value", ANY, "Hello from Eights").execOut("").keywords("log debug console")
                .emit((code, n) -> {
                    code.line("print(" + code.input(n, 1) + ")", n);
                    code.follow(n, 0);
                }));
        action("action.move", "Move", ACTIONS, "Moves in a direction, at Speed units per second (use in On Update)",
                "{0}.transform.position += {1} * {2} * _dt", TARGET, pin("Direction", VEC3, new Vec3(0, 0, -1)), pin("Speed", NUMBER, 5.0));
        action("action.rotate", "Rotate", ACTIONS, "Turns by degrees per second (use in On Update)",
                "{0}.transform.rotation += {1} * _dt", TARGET, pin("Degrees / sec", VEC3, new Vec3(0, 90, 0)));
        action("action.setPosition", "Set Position", ACTIONS, "Puts an object somewhere",
                "{0}.transform.position = {1}", TARGET, pin("Position", VEC3, new Vec3())).keywords("teleport place");
        action("action.translate", "Translate", ACTIONS, "Moves an object by an amount, once",
                "{0}.transform.position += {1}", TARGET, pin("By", VEC3, new Vec3(0, 1, 0)));
        action("action.setRotation", "Set Rotation", ACTIONS, "Turns an object to exact angles (degrees around X, Y, Z)",
                "{0}.transform.rotation = {1}", TARGET, pin("Rotation", VEC3, new Vec3()));
        action("action.setScale", "Set Scale", ACTIONS, "Changes an object's size",
                "{0}.transform.scale = {1}", TARGET, pin("Scale", VEC3, new Vec3(1, 1, 1))).keywords("size grow shrink");
        action("action.lookAt", "Look At", ACTIONS, "Turns an object to face a point",
                "{0}.transform.lookAt({1})", TARGET, pin("Point", VEC3, new Vec3())).keywords("face aim turn");
        action("action.setColor", "Set Color", ACTIONS, "Changes an object's color",
                "{0}.color = {1}", TARGET, pin("Color", COLOR, new Color(0xBF9EEE))).keywords("tint");
        action("action.setTexture", "Set Texture", ACTIONS, "Changes an object's picture (an imported texture path)",
                "{0}.texture = {1}", TARGET, pin("Texture", STRING, "assets/textures/"));
        action("action.setMesh", "Set Mesh", ACTIONS, "Changes an object's shape: cube, sphere, plane, cylinder or a model path",
                "{0}.mesh = {1}", TARGET, pin("Mesh", STRING, "sphere")).keywords("shape model");
        action("action.setActive", "Set Active", ACTIONS, "Hides an object and pauses its scripts (off), or brings it back (on)",
                "{0}.active = {1}", TARGET, pin("Active", BOOL, false)).keywords("hide show enable disable visible");
        action("action.setName", "Set Name", ACTIONS, "Renames an object", "{0}.name = {1}", TARGET, pin("Name", STRING, "Thing"));
        action("action.addTag", "Add Tag", ACTIONS, "Gives an object a tag", "{0}.addTag({1})", TARGET, pin("Tag", STRING, "tag"));
        action("action.removeTag", "Remove Tag", ACTIONS, "Takes a tag off an object", "{0}.removeTag({1})", TARGET, pin("Tag", STRING, "tag"));
        add(NodeType.builder("action.spawn", "Spawn", ACTIONS).describe("Creates a new object (cube, sphere, plane, cylinder or a model path)")
                .execIn().in("Mesh", STRING, "sphere").in("Position", VEC3, new Vec3(0, 1, 0)).execOut("").out("Spawned", ENTITY)
                .keywords("create instantiate make new")
                .fields(n -> List.of("var _n" + n.id + " = none"))
                .emit((code, n) -> {
                    code.line(code.temp(n) + " = spawn(" + code.input(n, 1) + ", " + code.input(n, 2) + ")", n);
                    code.follow(n, 0);
                })
                .value((code, n, out) -> code.temp(n)));
        action("action.destroy", "Destroy", ACTIONS, "Removes an object at the end of the frame", "{0}.destroy()", TARGET)
                .keywords("delete remove kill");
        add(NodeType.builder("action.send", "Send Message", ACTIONS)
                .describe("Calls a function on another object's scripts and graphs (an On Message node with this name, or a script's func), with up to 3 values")
                .execIn().in("Target", ENTITY, null).in("Name", STRING, "onHit").in("Value 1", ANY, 0.0).in("Value 2", ANY, 0.0).in("Value 3", ANY, 0.0)
                .execOut("").keywords("call function event notify")
                .emit((code, n) -> {
                    List<String> values = new ArrayList<>();
                    for (int i = 3; i <= 5; i++) values.add(code.input(n, i));
                    // trailing values nobody wired or typed are left off, so a script's func onHit(damage) still fits
                    while (!values.isEmpty() && !code.wired(n, values.size() + 2) && Double.valueOf(0.0).equals(code.typed(n, values.size() + 2))) {
                        values.remove(values.size() - 1);
                    }
                    String args = values.isEmpty() ? "" : ", " + String.join(", ", values);
                    code.line(code.input(n, 1) + ".send(" + code.input(n, 2) + args + ")", n);
                    code.follow(n, 0);
                }));
        action("action.setParent", "Set Parent", ACTIONS, "Makes an object follow another (a sword in a hand), staying where it is; none lets it go",
                "{0}.parent = {1}", TARGET, pin("Parent", ENTITY, null)).keywords("attach child hierarchy hold carry");
        action("action.clearParent", "Detach", ACTIONS, "Stops an object following its parent", "{0}.parent = none", TARGET)
                .keywords("unparent drop release");
        value("value.parent", "Get Parent", ACTIONS, "The object this one follows, or none", "Parent", ENTITY, "{0}.parent", TARGET);
        value("value.children", "Get Children", ACTIONS, "The objects that follow this one", "Children", LIST, "{0}.children", TARGET);
        action("action.addBehaviour", "Add Behaviour", ACTIONS, "Attaches a script or graph to an object while playing",
                "{0}.addBehaviour({1})", TARGET, pin("Behaviour", STRING, "Spin")).keywords("script component attach");
        action("action.setTile", "Set Tile", ACTIONS, "Changes one cell of a Tilemap (-1 clears it)",
                "{0}.setTile({1}, {2}, {3} < 0 ? none : {3})", TARGET, pin("X", NUMBER, 0.0), pin("Y", NUMBER, 0.0), pin("Tile", NUMBER, 0.0));
        value("value.tile", "Get Tile", ACTIONS, "Which tile is in one cell of a Tilemap (-1 if it's empty)", "Tile", NUMBER,
                "({0}.tile({1}, {2}) ?? -1)", TARGET, pin("X", NUMBER, 0.0), pin("Y", NUMBER, 0.0));
        setter("action.flipX", "Set Flip X", "Mirrors a sprite left-right (face the other way)", "Flip", BOOL, true, ".flipX = ");
        setter("action.flipY", "Set Flip Y", "Mirrors a sprite upside down", "Flip", BOOL, true, ".flipY = ");
        setter("action.setFrame", "Set Frame", "Shows one frame of a sprite sheet", "Frame", NUMBER, 0.0, ".frame = ");
    }

    /** An action that sets one property of the target: "target.property = value". */
    private static void setter(String id, String title, String description, String input, ValueType type, Object fallback, String assignment) {
        action(id, title, ACTIONS, description, "{0}" + assignment + "{1}", TARGET, pin(input, type, fallback));
    }

    // ---------------------------------------------------------------- physics

    private static void physics() {
        action("action.setVelocity", "Set Velocity", PHYSICS, "Sets how fast a Rigidbody moves (units per second)",
                "{0}.velocity = {1}", TARGET, pin("Velocity", VEC3, new Vec3(0, 5, 0))).keywords("speed");
        action("action.push", "Push", PHYSICS, "Adds to a Rigidbody's velocity: a jump, a knock-back",
                "{0}.push({1})", TARGET, pin("Amount", VEC3, new Vec3(0, 6, 0))).keywords("jump");
        action("action.addForce", "Add Force", PHYSICS, "A steady push, every frame you run it (an engine, wind); heavier things move less",
                "{0}.addForce({1})", TARGET, pin("Force", VEC3, new Vec3(0, 10, 0)));
        action("action.addImpulse", "Add Impulse", PHYSICS, "A sudden kick (an explosion, a hit); heavier things move less",
                "{0}.addImpulse({1})", TARGET, pin("Impulse", VEC3, new Vec3(0, 5, 0))).keywords("knockback explosion hit");
        action("action.setKinematic", "Set Kinematic", PHYSICS, "On: only scripts move it (moving platforms). Off: physics moves it",
                "{0}.kinematic = {1}", TARGET, pin("Kinematic", BOOL, true));
        action("action.setMass", "Set Mass", PHYSICS, "How heavy a Rigidbody is", "{0}.mass = {1}", TARGET, pin("Mass", NUMBER, 1.0)).keywords("weight");
        action("action.setGravity", "Set Gravity", PHYSICS, "Changes gravity for everything (normally 0, -9.81, 0)",
                "setGravity({0})", pin("Gravity", VEC3, new Vec3(0, -9.81, 0)));
        value("value.gravity", "Get Gravity", PHYSICS, "Gravity for everything", "Gravity", VEC3, "gravity()");
        value("value.velocity", "Get Velocity", PHYSICS, "How fast a Rigidbody is moving", "Velocity", VEC3, "{0}.velocity", TARGET);
        value("value.grounded", "Is Grounded", PHYSICS, "True while a Rigidbody stands on something", "Grounded", BOOL, "{0}.grounded", TARGET)
                .keywords("on ground floor");
        value("value.mass", "Get Mass", PHYSICS, "How heavy a Rigidbody is", "Mass", NUMBER, "{0}.mass", TARGET);
        value("value.raycast", "Raycast", PHYSICS,
                "The first solid thing along a line (use Break Hit to see what, where and how far), or none. Ignore leaves one object out (usually yourself)",
                "Hit", ANY, "raycast({0}, {1}, {2}, {3})",
                pin("From", VEC3, new Vec3()), pin("Direction", VEC3, new Vec3(0, 0, -1)), pin("Distance", NUMBER, 100.0), pin("Ignore", ENTITY, null))
                .keywords("ray line of sight shoot hitscan");
        add(NodeType.builder("value.breakHit", "Break Hit", PHYSICS).describe("What a Raycast found: whether it hit, the object, the point, the surface's direction and how far")
                .in("Hit", ANY, null).out("Did Hit", BOOL).out("Object", ENTITY).out("Point", VEC3).out("Normal", VEC3).out("Distance", NUMBER)
                .value((code, n, out) -> {
                    String hit = code.input(n, 0);
                    return switch (out) {
                        case 0 -> "(" + hit + " != none)";
                        case 1 -> "(" + hit + " == none ? none : " + hit + ".entity)";
                        case 2 -> "(" + hit + " == none ? vec3() : " + hit + ".point)";
                        case 3 -> "(" + hit + " == none ? vec3() : " + hit + ".normal)";
                        default -> "(" + hit + " == none ? -1 : " + hit + ".distance)";
                    };
                }));
        value("value.overlapSphere", "Overlap Sphere", PHYSICS, "Every object whose collider is inside a ball (explosions, pickups in reach)",
                "Objects", LIST, "overlapSphere({0}, {1})", pin("Center", VEC3, new Vec3()), pin("Radius", NUMBER, 2.0)).keywords("nearby area radius");
        value("value.solidAt", "Is Solid At", PHYSICS, "True if a solid collider or tile is at a point", "Solid", BOOL, "solidAt({0})",
                pin("Point", VEC3, new Vec3()));
    }

    // ---------------------------------------------------------------- input

    private static void input() {
        value("value.keyHeld", "Is Key Held", INPUT, "True while a key is down", "Held", BOOL, "key({0})", pin("Key", STRING, "space"));
        value("value.keyPressed", "Was Key Pressed", INPUT, "True only on the frame a key went down", "Pressed", BOOL, "keyPressed({0})", pin("Key", STRING, "space"));
        value("value.keyReleased", "Was Key Released", INPUT, "True only on the frame a key came up", "Released", BOOL, "keyReleased({0})", pin("Key", STRING, "space"));
        value("value.mouseDown", "Is Mouse Down", INPUT, "True while a mouse button is held (0 = left, 1 = right)", "Down", BOOL, "mouseDown({0})",
                pin("Button", NUMBER, 0.0));
        value("value.mouseClicked", "Was Mouse Clicked", INPUT, "True only on the frame a mouse button was clicked (0 = left, 1 = right)", "Clicked", BOOL,
                "mouseClicked({0})", pin("Button", NUMBER, 0.0));
        value("value.mousePosition", "Mouse Position", INPUT, "Where the mouse is in the Game view, in pixels (X and Y)", "Position", VEC3, "vec2(mouseX(), mouseY())");
        value("value.mouseMove", "Mouse Movement", INPUT, "How far the mouse moved this frame (for mouse-look; lock the mouse first)", "Movement", VEC3,
                "vec2(mouseMoveX(), mouseMoveY())").keywords("look aim delta");
        action("action.lockMouse", "Lock Mouse", INPUT, "Hides the cursor and keeps it in the game for mouse-look (off gives it back)",
                "lockMouse({0})", pin("Locked", BOOL, true)).keywords("cursor capture hide");
        value("value.mouseLocked", "Is Mouse Locked", INPUT, "True while the mouse is captured for mouse-look", "Locked", BOOL, "mouseLocked()");
        value("value.moveInput", "Move Input (WASD)", INPUT,
                "Which way the WASD or arrow keys point, on the ground (X = right, Z = back): plug it into Move or Set Velocity", "Direction", VEC3,
                "vec3((key(\"d\") or key(\"right\") ? 1 : 0) - (key(\"a\") or key(\"left\") ? 1 : 0), 0, (key(\"s\") or key(\"down\") ? 1 : 0) - (key(\"w\") or key(\"up\") ? 1 : 0))")
                .keywords("wasd arrows axis direction controls");
        value("value.moveInput2D", "Move Input 2D", INPUT, "Which way the WASD or arrow keys point, for 2D (X = right, Y = up)", "Direction", VEC3,
                "vec2((key(\"d\") or key(\"right\") ? 1 : 0) - (key(\"a\") or key(\"left\") ? 1 : 0), (key(\"w\") or key(\"up\") ? 1 : 0) - (key(\"s\") or key(\"down\") ? 1 : 0))")
                .keywords("wasd arrows axis platformer");
    }

    // ---------------------------------------------------------------- characters: animation, ragdolls, NPCs

    private static void characters() {
        action("action.play", "Play Animation", CHARACTERS, "Switches a 3D model's animation (blending over Fade seconds), or a Sprite Animator's",
                "{0}.play({1}, {2})", TARGET, pin("Animation", STRING, "Walk"), pin("Fade", NUMBER, 0.2)).keywords("animate clip");
        action("action.animationSpeed", "Set Animation Speed", CHARACTERS, "How fast a 3D model's animation plays (1 = normal)",
                "{0}.animationSpeed = {1}", TARGET, pin("Speed", NUMBER, 1.0));
        value("value.animation", "Current Animation", CHARACTERS, "The animation playing now", "Animation", STRING, "{0}.animation", TARGET);
        value("value.animationDone", "Is Animation Done", CHARACTERS, "True once an animation that doesn't loop has finished", "Done", BOOL,
                "{0}.animationDone", TARGET);
        action("action.ragdoll", "Ragdoll", CHARACTERS, "Goes limp like a real body, knocked by Push (needs a Ragdoll component)",
                "{0}.ragdoll({1})", TARGET, pin("Push", VEC3, new Vec3(0, 2, -3))).keywords("limp fall knock down");
        action("action.getUp", "Get Up", CHARACTERS, "Stands back up after ragdolling", "{0}.getUp()", TARGET).keywords("stand");
        value("value.ragdolling", "Is Ragdolling", CHARACTERS, "True while it's limp", "Ragdolling", BOOL, "{0}.ragdolling", TARGET);
        value("value.ragdollResting", "Is Ragdoll Resting", CHARACTERS, "True once a limp body has landed and stopped moving: a good moment to Get Up",
                "Resting", BOOL, "{0}.ragdollResting", TARGET).keywords("still settled landed lying");
        // NPCs
        action("action.npcMoveTo", "NPC Move To", CHARACTERS, "Walks around walls to a point", "{0}.moveTo({1})", TARGET, pin("Place", VEC3, new Vec3()))
                .keywords("walk go path navigate");
        action("action.npcMoveToObject", "NPC Go To", CHARACTERS, "Walks around walls to an object", "{0}.moveTo({1})", TARGET, pin("Object", ENTITY, null));
        action("action.npcFollow", "NPC Follow", CHARACTERS, "Walks after an object, keeping close", "{0}.follow({1})", TARGET, pin("Leader", ENTITY, null));
        action("action.npcChase", "NPC Chase", CHARACTERS, "Runs after an object", "{0}.chase({1})", TARGET, pin("Prey", ENTITY, null)).keywords("attack hunt");
        action("action.npcFlee", "NPC Flee", CHARACTERS, "Runs away from an object", "{0}.flee({1})", TARGET, pin("Danger", ENTITY, null)).keywords("run away escape");
        action("action.npcStay", "NPC Stay", CHARACTERS, "Stands still until told otherwise", "{0}.stay()", TARGET).keywords("stop wait");
        action("action.npcResume", "NPC Resume", CHARACTERS, "Goes back to what its NPC component says (wander, patrol, routine)", "{0}.resume()", TARGET);
        action("action.npcRemember", "NPC Remember", CHARACTERS, "Makes an NPC remember something about someone, changing its opinion by Opinion",
                "{0}.remember({1}, {2}, {3})", TARGET, pin("About", ENTITY, null), pin("Event", STRING, "helped me"), pin("Opinion", NUMBER, 10.0));
        action("action.npcSetOpinion", "NPC Set Opinion", CHARACTERS, "What an NPC thinks of someone, -100 (hates) to 100 (loves)",
                "{0}.setOpinion({1}, {2})", TARGET, pin("About", ENTITY, null), pin("Opinion", NUMBER, 0.0));
        action("action.npcForget", "NPC Forget", CHARACTERS, "Wipes an NPC's memory of someone", "{0}.forget({1})", TARGET, pin("About", ENTITY, null));
        value("value.npcState", "NPC State", CHARACTERS, "What it's doing: idle, moving, following, chasing, fleeing, staying, watching, searching, routine",
                "State", STRING, "{0}.state", TARGET);
        value("value.npcTarget", "NPC Target", CHARACTERS, "What it's chasing, fleeing or watching, or none", "Target", ENTITY, "{0}.target", TARGET);
        value("value.npcArrived", "NPC Arrived", CHARACTERS, "True once it got where it was going", "Arrived", BOOL, "{0}.arrived", TARGET);
        value("value.npcCanSee", "NPC Can See", CHARACTERS, "True if it can see an object right now (in range, in its view, not behind a wall)",
                "Can See", BOOL, "{0}.canSee({1})", TARGET, pin("Object", ENTITY, null)).keywords("sight vision");
        value("value.npcOpinion", "NPC Opinion", CHARACTERS, "What it thinks of someone, -100 to 100", "Opinion", NUMBER, "{0}.opinion({1})", TARGET,
                pin("About", ENTITY, null));
        value("value.npcRemembers", "NPC Remembers", CHARACTERS, "How many times it remembers something happening (0 if never)", "Times", NUMBER,
                "{0}.remembers({1}, {2})", TARGET, pin("About", ENTITY, null), pin("Event", STRING, "helped me"));
        value("value.npcSeen", "NPC Sees", CHARACTERS, "Everything it can see right now", "Seen", LIST, "{0}.seen", TARGET);
        action("action.makeNoise", "Make Noise", CHARACTERS, "A sound NPCs can hear (they come to look, or run): footsteps, gunshots",
                "makeNoise({0}, {1}, {2})", pin("Where", VEC3, new Vec3()), pin("Loudness", NUMBER, 1.0), pin("Source", ENTITY, null)).keywords("sound alert");
    }

    // ---------------------------------------------------------------- HUD & UI (draw every frame, from On Update)

    private static void hud() {
        Pin x = pin("X", NUMBER, 40.0), y = pin("Y", NUMBER, 40.0);
        action("hud.text", "Draw Text", HUD, "Writes text on the screen this frame (the screen is 720 tall; 0, 0 is the top left). Align: left, center or right",
                "text({0}, {1}, {2}, {3}, {4}, {5})", pin("Text", ANY, "Score: 0"), x, y, pin("Size", NUMBER, 32.0), pin("Color", COLOR, WHITE),
                pin("Align", STRING, "left")).keywords("label write score");
        action("hud.rect", "Draw Box", HUD, "A filled box on the screen this frame", "rect({0}, {1}, {2}, {3}, {4})", x, y,
                pin("Width", NUMBER, 200.0), pin("Height", NUMBER, 40.0), pin("Color", COLOR, new Color(0x44475A))).keywords("rectangle panel background");
        action("hud.bar", "Draw Bar", HUD, "A bar filled Amount of the way (0 to 1): health, stamina, loading", "bar({0}, {1}, {2}, {3}, {4}, {5})", x, y,
                pin("Width", NUMBER, 300.0), pin("Height", NUMBER, 20.0), pin("Amount", NUMBER, 0.75), pin("Color", COLOR, new Color(0x50FA7B)))
                .keywords("health progress meter");
        action("hud.image", "Draw Picture", HUD, "An imported picture on the screen this frame", "image({0}, {1}, {2}, {3}, {4})",
                pin("Picture", STRING, "assets/ui/icon.png"), x, y, pin("Width", NUMBER, 64.0), pin("Height", NUMBER, 64.0)).keywords("icon sprite");
        action("hud.line", "Draw Line", HUD, "A line on the screen this frame", "line({0}, {1}, {2}, {3}, {4}, {5})",
                pin("X1", NUMBER, 0.0), pin("Y1", NUMBER, 0.0), pin("X2", NUMBER, 100.0), pin("Y2", NUMBER, 100.0), pin("Thickness", NUMBER, 2.0),
                pin("Color", COLOR, WHITE)).keywords("crosshair");
        action("hud.circle", "Draw Circle", HUD, "A filled circle on the screen this frame", "circle({0}, {1}, {2}, {3})", x, y,
                pin("Radius", NUMBER, 10.0), pin("Color", COLOR, WHITE)).keywords("dot");
        action("hud.ring", "Draw Ring", HUD, "A circle outline on the screen this frame", "ring({0}, {1}, {2}, {3}, {4})", x, y,
                pin("Radius", NUMBER, 30.0), pin("Thickness", NUMBER, 2.0), pin("Color", COLOR, WHITE)).keywords("radar");
        action("hud.frame", "Draw Frame", HUD, "Just the outline of a box", "frame({0}, {1}, {2}, {3}, {4}, {5})", x, y,
                pin("Width", NUMBER, 200.0), pin("Height", NUMBER, 100.0), pin("Color", COLOR, WHITE), pin("Thickness", NUMBER, 2.0)).keywords("border outline");
        action("hud.brackets", "Draw Brackets", HUD, "Only the corners of a box (lock-on, targets)", "brackets({0}, {1}, {2}, {3}, {4})", x, y,
                pin("Width", NUMBER, 80.0), pin("Height", NUMBER, 80.0), pin("Color", COLOR, WHITE)).keywords("target lock on");
        action("hud.segmentBar", "Draw Segment Bar", HUD, "A bar in separate blocks (ammo, energy)", "segmentBar({0}, {1}, {2}, {3}, {4}, {5}, {6})", x, y,
                pin("Width", NUMBER, 200.0), pin("Height", NUMBER, 12.0), pin("Amount", NUMBER, 0.5), pin("Segments", NUMBER, 10.0),
                pin("Color", COLOR, new Color(0x50FA7B))).keywords("ammo energy");
        action("hud.keyIcon", "Draw Key Icon", HUD, "A keyboard key with a letter on it, for prompts like [E] Open", "keyIcon({0}, {1}, {2}, {3})",
                pin("Key", STRING, "E"), x, y, pin("Size", NUMBER, 36.0)).keywords("prompt button hint");
        action("hud.anchor", "Set Anchor", HUD,
                "Measures HUD positions from a corner, an edge or the center from now on, so it fits any screen: topLeft, top, topRight, left, center, right, bottomLeft, bottom, bottomRight",
                "anchor({0})", pin("Anchor", STRING, "bottomRight")).keywords("corner align layout position responsive");
        action("hud.glow", "Set Glow", HUD, "Everything drawn after this glows (on) or doesn't (off)", "setGlow({0})", pin("Glow", BOOL, true))
                .keywords("neon hologram bright");
        action("hud.beginWorld", "Begin World Canvas", HUD,
                "Until End World Canvas, HUD drawing goes on a card in the world at Position, facing the camera (holograms, name tags)",
                "beginWorld({0}, none, {1})", pin("Position", VEC3, new Vec3(0, 2, 0)), pin("Scale", NUMBER, 0.0025)).keywords("diegetic hologram 3d label");
        action("hud.endWorld", "End World Canvas", HUD, "Back to drawing on the screen", "endWorld()");
        value("hud.screenWidth", "Screen Width", HUD, "How wide the screen is in HUD units (the height is always 720)", "Width", NUMBER, "screenWidth()");
        value("hud.screenHeight", "Screen Height", HUD, "How tall the screen is in HUD units (always 720)", "Height", NUMBER, "screenHeight()");
        value("hud.screenCenter", "Screen Center", HUD, "The middle of the screen (X and Y)", "Center", VEC3, "vec2(screenWidth() / 2, screenHeight() / 2)");
        value("hud.worldToScreen", "World To Screen", HUD, "Where a point in the world shows on the screen (X and Y), for labels over heads",
                "Screen Point", VEC3, "(worldToScreen({0}) ?? vec2(-1000, -1000))", pin("World Point", VEC3, new Vec3())).keywords("project label");
        value("hud.textWidth", "Text Width", HUD, "How wide some text is at a size, for centering and boxes", "Width", NUMBER, "textWidth({0}, {1})",
                pin("Text", ANY, "Hello"), pin("Size", NUMBER, 32.0));
        // clickable UI
        add(NodeType.builder("ui.button", "Button", HUD).describe("A button you click. Draw it every frame; Clicked runs on the frame it's clicked")
                .execIn().in("Label", STRING, "Play").in("X", NUMBER, 540.0).in("Y", NUMBER, 400.0).in("Width", NUMBER, 200.0).in("Height", NUMBER, 56.0)
                .execOut("").execOut("Clicked").keywords("menu click ui")
                .emit((code, n) -> {
                    code.line("if button(" + code.input(n, 1) + ", " + code.input(n, 2) + ", " + code.input(n, 3) + ", "
                            + code.input(n, 4) + ", " + code.input(n, 5) + ") {", n);
                    code.indent();
                    code.follow(n, 1);
                    code.dedent();
                    code.line("}", n);
                    code.follow(n, 0);
                }));
        actionOut("ui.slider", "Slider", HUD, "A slider you drag. Draw it every frame with its current value; Value is the new one (keep it in a variable)",
                "slider({0}, {1}, {2}, {3}, {4}, {5}, {6})", "Value", NUMBER, pin("Label", STRING, "Volume"), pin("X", NUMBER, 440.0),
                pin("Y", NUMBER, 300.0), pin("Width", NUMBER, 400.0), pin("Current", NUMBER, 0.5), pin("Min", NUMBER, 0.0), pin("Max", NUMBER, 1.0));
        actionOut("ui.checkbox", "Checkbox", HUD, "A box you tick. Draw it every frame with its current state; Checked is the new one (keep it in a variable)",
                "checkbox({0}, {1}, {2}, {3})", "Checked", BOOL, pin("Label", STRING, "Fullscreen"), pin("X", NUMBER, 440.0), pin("Y", NUMBER, 360.0),
                pin("Current", BOOL, false)).keywords("toggle option");
        action("ui.panel", "Panel", HUD, "A rounded box behind buttons (menus, dialogs)", "panel({0}, {1}, {2}, {3})", pin("X", NUMBER, 440.0),
                pin("Y", NUMBER, 160.0), pin("Width", NUMBER, 400.0), pin("Height", NUMBER, 400.0)).keywords("menu window background");
        value("ui.mouseOverUi", "Is Mouse Over UI", HUD, "True while the mouse is over a button, slider or panel (so a click there doesn't shoot)",
                "Over UI", BOOL, "mouseOverUi()");
    }

    // ---------------------------------------------------------------- sound

    private static void sound() {
        action("sound.play", "Play Sound", SOUND, "Plays a .wav file once", "playSound({0}, {1})", pin("Sound", STRING, "assets/sounds/hit.wav"),
                pin("Volume", NUMBER, 1.0)).keywords("sfx effect audio");
        action("sound.playAt", "Play Sound At", SOUND, "Plays a .wav file at a place in the world: quieter further from the camera (silent past Range) and from the left or right",
                "playSoundAt({0}, {1}, {2}, {3})", pin("Sound", STRING, "assets/sounds/hit.wav"), pin("Position", VEC3, new Vec3()),
                pin("Volume", NUMBER, 1.0), pin("Range", NUMBER, 30.0)).keywords("3d spatial positional distance sfx");
        action("sound.music", "Play Music", SOUND, "Plays a .wav file on repeat (replacing any music playing)", "playMusic({0}, {1})",
                pin("Music", STRING, "assets/sounds/music.wav"), pin("Volume", NUMBER, 0.6)).keywords("song loop audio");
        action("sound.stopMusic", "Stop Music", SOUND, "Stops the music", "stopMusic()");
        action("sound.setVolume", "Set Volume", SOUND, "The volume of everything, 0 (silent) to 1", "setVolume({0})", pin("Volume", NUMBER, 1.0)).keywords("mute");
        value("sound.volume", "Volume", SOUND, "The volume of everything, 0 to 1", "Volume", NUMBER, "volume()");
    }

    // ---------------------------------------------------------------- scenes, saves, prefabs, pausing

    private static void scenes() {
        action("scene.load", "Load Scene", SCENES, "Switches to another scene (\"Main\", or one in the scenes folder), fading to black for Fade seconds (0 = at once)",
                "loadScene({0}, {1})", pin("Scene", STRING, "Level2"), pin("Fade", NUMBER, 0.0)).keywords("level change next transition");
        action("scene.reload", "Reload Scene", SCENES, "Starts this scene again (values from Set Value are kept)", "reloadScene({0})", pin("Fade", NUMBER, 0.0))
                .keywords("retry respawn reset level");
        value("scene.names", "Scene Names", SCENES, "Every scene in the project, Main first (level select menus)", "Scenes", LIST, "sceneNames()");
        value("scene.name", "Scene Name", SCENES, "Which scene is playing", "Scene", STRING, "sceneName()");
        action("scene.setValue", "Set Value", SCENES, "Keeps a value that lasts across scenes and goes into saves (score, coins, unlocked levels)",
                "setValue({0}, {1})", pin("Name", STRING, "coins"), pin("Value", ANY, 0.0)).keywords("global persist store");
        value("scene.getValue", "Get Value", SCENES, "A value kept with Set Value, or Fallback if it was never set", "Value", ANY, "getValue({0}, {1})",
                pin("Name", STRING, "coins"), pin("Fallback", ANY, 0.0)).keywords("global persist load");
        action("scene.save", "Save Game", SCENES, "Saves everything (objects, script variables, values) under a name", "saveGame({0})",
                pin("Save", STRING, "save")).keywords("checkpoint");
        action("scene.loadSave", "Load Game", SCENES, "Goes back to a save (if there is one)", "if hasSave({0}) { loadGame({0}) }", pin("Save", STRING, "save"))
                .keywords("continue checkpoint");
        value("scene.hasSave", "Has Save", SCENES, "True if there's a save with that name", "Exists", BOOL, "hasSave({0})", pin("Save", STRING, "save"));
        action("scene.deleteSave", "Delete Save", SCENES, "Removes a save", "deleteSave({0})", pin("Save", STRING, "save"));
        actionOut("scene.spawnPrefab", "Spawn Prefab", SCENES, "A copy of a saved object (assets/prefabs), scripts and all, at Position",
                "spawnPrefab({0}, {1})", "Spawned", ENTITY, pin("Prefab", STRING, "Enemy"), pin("Position", VEC3, new Vec3())).keywords("instantiate create clone");
        action("scene.restart", "Restart", SCENES, "Starts the game again from the beginning", "restart()").keywords("reset retry");
        action("scene.quit", "Quit", SCENES, "Closes the game (stops Play in the editor)", "quit()").keywords("exit close");
        action("scene.timeScale", "Set Time Scale", SCENES, "How fast the world runs: 0 pauses it (menus), 0.3 is slow motion, 1 is normal",
                "setTimeScale({0})", pin("Scale", NUMBER, 0.0)).keywords("pause slow motion bullet time");
        value("scene.getTimeScale", "Time Scale", SCENES, "How fast the world runs (1 = normal)", "Scale", NUMBER, "timeScale()");
    }

    // ---------------------------------------------------------------- world: effects, tweens, the clock, lights, cameras

    private static void world() {
        actionOut("world.spawnEffect", "Spawn Effect", WORLD, "A particle effect at a point: Fire, Smoke, Sparks, Magic, Explosion, Rain, Snow, Confetti, Blood",
                "spawnEffect({0}, {1})", "Effect", ENTITY, pin("Effect", STRING, "Explosion"), pin("Position", VEC3, new Vec3())).keywords("particles vfx");
        action("world.emit", "Emit Particles", WORLD, "Bursts some particles from an object's Particle Emitter", "{0}.emit({1})", TARGET, pin("Count", NUMBER, 20.0));
        action("world.playEffect", "Play Effect", WORLD, "Starts an object's Particle Emitter", "{0}.playEffect()", TARGET);
        action("world.stopEffect", "Stop Effect", WORLD, "Stops an object's Particle Emitter", "{0}.stopEffect()", TARGET);
        action("world.tween", "Tween", WORLD,
                "Moves a property smoothly to a value over some seconds: position, rotation, scale, color, tint, size. Easing: linear, outBounce, inOutQuad...",
                "tween({0}, {1}, {2}, {3}, {4})", TARGET, pin("Property", STRING, "position"), pin("To", ANY, null), pin("Seconds", NUMBER, 1.0),
                pin("Easing", STRING, "inOutQuad")).keywords("animate smooth ease slide lerp");
        action("world.tweenPosition", "Tween Position", WORLD, "Slides an object smoothly to a point", "tween({0}, \"position\", {1}, {2}, {3})", TARGET,
                pin("To", VEC3, new Vec3(0, 2, 0)), pin("Seconds", NUMBER, 1.0), pin("Easing", STRING, "inOutQuad")).keywords("move smooth slide");
        action("world.tweenScale", "Tween Scale", WORLD, "Grows or shrinks an object smoothly", "tween({0}, \"scale\", {1}, {2}, {3})", TARGET,
                pin("To", VEC3, new Vec3(2, 2, 2)), pin("Seconds", NUMBER, 0.5), pin("Easing", STRING, "outBack")).keywords("grow shrink pop");
        action("world.tweenColor", "Tween Color", WORLD, "Fades an object to a color smoothly", "tween({0}, \"color\", {1}, {2})", TARGET,
                pin("To", COLOR, new Color(0xFF5555)), pin("Seconds", NUMBER, 0.5)).keywords("fade flash");
        action("world.stopTweens", "Stop Tweens", WORLD, "Stops an object's tweens where they are", "stopTweens({0})", TARGET);
        value("world.hour", "Hour", WORLD, "The game clock, 0 to 24 (it starts at 8:00)", "Hour", NUMBER, "hour()").keywords("time of day clock");
        action("world.setHour", "Set Hour", WORLD, "Sets the game clock (0 to 24)", "setHour({0})", pin("Hour", NUMBER, 12.0));
        action("world.setDayLength", "Set Day Length", WORLD, "How many real seconds a whole game day takes", "setDayLength({0})", pin("Seconds", NUMBER, 600.0));
        action("world.lightIntensity", "Set Light Brightness", WORLD, "How bright a Directional Light is", "{0}.lightIntensity = {1}", TARGET,
                pin("Brightness", NUMBER, 1.0)).keywords("sun intensity day night");
        action("world.lightColor", "Set Light Color", WORLD, "A Directional Light's color", "{0}.lightColor = {1}", TARGET, pin("Color", COLOR, new Color(0xFFF1D6)))
                .keywords("sun");
        action("world.lightAmbient", "Set Light Ambient", WORLD, "How bright the shadowed side is", "{0}.lightAmbient = {1}", TARGET, pin("Ambient", NUMBER, 0.25));
        action("world.cameraFov", "Set Field Of View", WORLD, "How wide a Camera sees, in degrees (zoom in with less)", "{0}.camera.fieldOfView = {1}", TARGET,
                pin("Degrees", NUMBER, 60.0)).keywords("zoom aim scope");
        action("world.cameraBackground", "Set Sky Color", WORLD, "A Camera's background color (and fog's)", "{0}.camera.background = {1}", TARGET,
                pin("Color", COLOR, new Color(0x9CC9E0))).keywords("background sky fog");
    }

    // ---------------------------------------------------------------- values: information to use

    private static void values() {
        value("value.self", "Self", VALUES, "The object this graph is attached to", "Self", ENTITY, "entity").keywords("this me");
        value("value.find", "Find", VALUES, "Finds an object by name (none if there isn't one)", "Entity", ENTITY, "find({0})", pin("Name", STRING, "Player"))
                .keywords("get object by name");
        value("value.findAll", "Find All With Tag", VALUES, "Every object with a tag", "Objects", LIST, "findAll({0})", pin("Tag", STRING, "enemy"))
                .keywords("tagged list group");
        value("value.isValid", "Is Valid", VALUES, "True if it's something (not none): check Find and Raycast results", "Valid", BOOL, "({0} != none)",
                pin("Value", ANY, null)).keywords("exists null none");
        value("value.position", "Get Position", VALUES, "Where an object is", "Position", VEC3, "{0}.transform.position", TARGET);
        value("value.rotation", "Get Rotation", VALUES, "An object's angles in degrees (X, Y, Z)", "Rotation", VEC3, "{0}.transform.rotation", TARGET);
        value("value.scale", "Get Scale", VALUES, "An object's size", "Scale", VEC3, "{0}.transform.scale", TARGET);
        value("value.forward", "Get Forward", VALUES, "The direction an object faces", "Forward", VEC3, "{0}.transform.forward", TARGET);
        value("value.right", "Get Right", VALUES, "The direction to an object's right", "Right", VEC3, "{0}.transform.right", TARGET);
        value("value.up", "Get Up", VALUES, "The direction above an object (tilts with it)", "Up", VEC3, "{0}.transform.up", TARGET);
        value("value.name", "Get Name", VALUES, "An object's name", "Name", STRING, "{0}.name", TARGET);
        value("value.color", "Get Color", VALUES, "An object's color", "Color", COLOR, "{0}.color", TARGET);
        value("value.active", "Is Active", VALUES, "False if an object is hidden", "Active", BOOL, "{0}.active", TARGET);
        value("value.hasTag", "Has Tag", VALUES, "True if an object has a tag", "Result", BOOL, "{0}.hasTag({1})", TARGET, pin("Tag", STRING, "player"));
        add(NodeType.builder("value.getField", "Get Script Variable", VALUES)
                .describe("Reads a variable of a script or graph on an object (type the behaviour's name and the variable's name)")
                .in("Target", ENTITY, null).in("Behaviour", STRING, "Player").in("Variable", STRING, "health").out("Value", ANY)
                .keywords("field property other script")
                .value((code, n, out) -> {
                    String field = Compiler.identifier(code.typed(n, 2));
                    if (field == null) return "none";
                    return code.input(n, 0) + ".behaviour(" + code.input(n, 1) + ")." + field;
                }));
        add(NodeType.builder("action.setField", "Set Script Variable", VALUES)
                .describe("Changes a variable of a script or graph on an object (type the behaviour's name and the variable's name)")
                .execIn().in("Target", ENTITY, null).in("Behaviour", STRING, "Player").in("Variable", STRING, "health").in("Value", ANY, 0.0).execOut("")
                .keywords("field property other script")
                .emit((code, n) -> {
                    String field = Compiler.identifier(code.typed(n, 3));
                    if (field == null) code.line("// Set Script Variable: type a variable name", n);
                    else code.line(code.input(n, 1) + ".behaviour(" + code.input(n, 2) + ")." + field + " = " + code.input(n, 4), n);
                    code.follow(n, 0);
                }));
        value("value.number", "Number", VALUES, "A number you type", "Number", NUMBER, "{0}", pin("Value", NUMBER, 0.0)).keywords("constant literal");
        value("value.text", "Text", VALUES, "Text you type", "Text", STRING, "{0}", pin("Value", STRING, "")).keywords("string constant literal");
        value("value.bool", "True / False", VALUES, "A tick box: true or false", "Value", BOOL, "{0}", pin("Value", BOOL, true)).keywords("boolean constant");
        value("value.colorValue", "Color", VALUES, "A color you pick", "Color", COLOR, "{0}", pin("Color", COLOR, new Color(0xBF9EEE)));
        value("value.makeVec3", "Make Vec3", VALUES, "A vec3 from X, Y and Z", "Vec3", VEC3, "vec3({0}, {1}, {2})",
                pin("X", NUMBER, 0.0), pin("Y", NUMBER, 0.0), pin("Z", NUMBER, 0.0)).keywords("vector point direction");
        add(NodeType.builder("value.breakVec3", "Break Vec3", VALUES).describe("The X, Y and Z of a vec3")
                .in("Vec3", VEC3, new Vec3()).out("X", NUMBER).out("Y", NUMBER).out("Z", NUMBER).keywords("split vector")
                .value((code, n, out) -> "(" + code.input(n, 0) + ")." + "xyz".charAt(out)));
        value("value.makeColor", "Make Color", VALUES, "A color from red, green and blue (0 to 255) and Alpha (255 = solid)", "Color", COLOR,
                "rgb({0}, {1}, {2}, {3})", pin("Red", NUMBER, 255.0), pin("Green", NUMBER, 255.0), pin("Blue", NUMBER, 255.0), pin("Alpha", NUMBER, 255.0))
                .keywords("rgb rgba");
        value("value.hsv", "Make Color HSV", VALUES, "A color from hue (0 to 360 round the rainbow), saturation and brightness (0 to 1)", "Color", COLOR,
                "hsv({0}, {1}, {2})", pin("Hue", NUMBER, 0.0), pin("Saturation", NUMBER, 1.0), pin("Brightness", NUMBER, 1.0)).keywords("rainbow hue");
        value("value.lerpColor", "Blend Colors", VALUES, "A color T of the way from A to B", "Color", COLOR, "lerp({0}, {1}, {2})",
                pin("A", COLOR, new Color(0x50FA7B)), pin("B", COLOR, new Color(0xFF5555)), pin("T", NUMBER, 0.5)).keywords("mix lerp fade");
        value("value.distance", "Distance", VALUES, "How far apart two points are", "Distance", NUMBER, "distance({0}, {1})",
                pin("A", VEC3, new Vec3()), pin("B", VEC3, new Vec3()));
        value("value.random", "Random", VALUES, "A random number between Min and Max", "Value", NUMBER, "random({0}, {1})",
                pin("Min", NUMBER, 0.0), pin("Max", NUMBER, 1.0));
        value("value.randomInt", "Random Whole Number", VALUES, "A random whole number from Min to Max (both included)", "Value", NUMBER, "randint({0}, {1})",
                pin("Min", NUMBER, 1.0), pin("Max", NUMBER, 6.0)).keywords("dice integer randint");
        value("value.chance", "Chance", VALUES, "True some of the time: 0.25 = a quarter of the time", "Result", BOOL, "chance({0})",
                pin("Probability", NUMBER, 0.5)).keywords("random maybe percent");
        value("value.randomDirection", "Random Direction", VALUES, "A random direction (length 1)", "Direction", VEC3, "randomDirection()");
        value("value.time", "Time", VALUES, "Seconds since Play started", "Seconds", NUMBER, "time()").keywords("clock elapsed");
        value("value.deltaTime", "Delta Time", VALUES, "Seconds since the last frame (multiply speeds by it)", "Seconds", NUMBER, "_dt").keywords("dt frame");
    }

    // ---------------------------------------------------------------- math & logic

    private static void math() {
        operator("math.add", "Add", "+", ANY, 0.0, 1.0, ANY);
        operator("math.subtract", "Subtract", "-", ANY, 0.0, 1.0, ANY);
        operator("math.multiply", "Multiply", "*", ANY, 1.0, 2.0, ANY);
        operator("math.divide", "Divide", "/", ANY, 1.0, 2.0, ANY);
        operator("math.modulo", "Remainder", "%", NUMBER, 7.0, 3.0, NUMBER);
        operator("math.greater", "Greater Than", ">", NUMBER, 0.0, 0.0, BOOL);
        operator("math.less", "Less Than", "<", NUMBER, 0.0, 0.0, BOOL);
        operator("math.greaterEqual", "At Least", ">=", NUMBER, 0.0, 0.0, BOOL);
        operator("math.lessEqual", "At Most", "<=", NUMBER, 0.0, 0.0, BOOL);
        operator("math.equals", "Equals", "==", ANY, 0.0, 0.0, BOOL);
        operator("math.notEquals", "Not Equal", "!=", ANY, 0.0, 1.0, BOOL);
        operator("logic.and", "And", "and", BOOL, true, true, BOOL);
        operator("logic.or", "Or", "or", BOOL, false, false, BOOL);
        value("logic.not", "Not", MATH, "True becomes false, false becomes true", "Result", BOOL, "(not {0})", pin("Value", BOOL, false));
        value("logic.select", "Select", MATH, "A if the condition is true, otherwise B", "Result", ANY, "({0} ? {1} : {2})",
                pin("Condition", BOOL, true), pin("A", ANY, 1.0), pin("B", ANY, 0.0)).keywords("if ternary choose pick");
        function1("math.abs", "Abs", "abs", "The number without its minus sign");
        function1("math.round", "Round", "round", "The nearest whole number");
        function1("math.floor", "Floor", "floor", "Rounds down");
        function1("math.ceil", "Ceil", "ceil", "Rounds up");
        function1("math.sqrt", "Square Root", "sqrt", "The square root");
        function1("math.sin", "Sin", "sin", "Sine of an angle in radians (waves and bobbing: Sin of Time)");
        function1("math.cos", "Cos", "cos", "Cosine of an angle in radians");
        function1("math.sign", "Sign", "sign", "-1, 0 or 1");
        value("math.negate", "Negate", MATH, "Flips the sign (works on vec3s too)", "Result", ANY, "(-{0})", pin("Value", ANY, 1.0));
        value("math.power", "Power", MATH, "A to the power of B", "Result", NUMBER, "pow({0}, {1})", pin("A", NUMBER, 2.0), pin("B", NUMBER, 2.0));
        value("math.min", "Min", MATH, "The smaller of two numbers", "Result", NUMBER, "min({0}, {1})", pin("A", NUMBER, 0.0), pin("B", NUMBER, 1.0));
        value("math.max", "Max", MATH, "The bigger of two numbers", "Result", NUMBER, "max({0}, {1})", pin("A", NUMBER, 0.0), pin("B", NUMBER, 1.0));
        value("math.clamp", "Clamp", MATH, "Keeps a number between Min and Max", "Result", NUMBER, "clamp({0}, {1}, {2})",
                pin("Value", NUMBER, 0.0), pin("Min", NUMBER, 0.0), pin("Max", NUMBER, 1.0)).keywords("limit range");
        value("math.lerp", "Lerp", MATH, "T of the way from A to B (numbers or vec3s)", "Result", ANY, "lerp({0}, {1}, {2})",
                pin("A", ANY, 0.0), pin("B", ANY, 10.0), pin("T", NUMBER, 0.5)).keywords("blend mix interpolate");
        value("math.remap", "Remap", MATH, "Moves a number from one range to another (health 0..100 to a bar 0..1)", "Result", NUMBER,
                "remap({0}, {1}, {2}, {3}, {4})", pin("Value", NUMBER, 50.0), pin("From Min", NUMBER, 0.0), pin("From Max", NUMBER, 100.0),
                pin("To Min", NUMBER, 0.0), pin("To Max", NUMBER, 1.0)).keywords("map range convert");
        value("math.moveTowards", "Move Towards", MATH, "A step from Current toward Target, never past it (numbers or vec3s)", "Result", ANY,
                "moveTowards({0}, {1}, {2})", pin("Current", ANY, 0.0), pin("Target", ANY, 10.0), pin("Step", NUMBER, 1.0)).keywords("approach");
        value("math.ease", "Ease", MATH, "An easing curve at T (0 to 1): linear, inQuad, outBounce, inOutBack...", "Result", NUMBER, "ease({0}, {1})",
                pin("Curve", STRING, "outBounce"), pin("T", NUMBER, 0.5)).keywords("curve tween smooth");
        value("math.noise", "Noise", MATH, "Smooth randomness from 0 to 1 that changes gently as X and Y change (terrain, flicker, wobble)", "Result", NUMBER,
                "noise({0}, {1})", pin("X", NUMBER, 0.0), pin("Y", NUMBER, 0.0)).keywords("perlin random smooth");
        value("math.pingPong", "Ping Pong", MATH, "Goes from 0 up to Length and back down, again and again, as T grows", "Result", NUMBER,
                "pingPong({0}, {1})", pin("T", NUMBER, 0.0), pin("Length", NUMBER, 1.0)).keywords("back forth oscillate");
        value("math.atan2", "Angle Of", MATH, "The angle in degrees of a direction on the ground (X, Z), for turning to face it", "Degrees", NUMBER,
                "deg(atan2({0}.x, {0}.z))", pin("Direction", VEC3, new Vec3(0, 0, 1))).keywords("atan2 heading yaw");
        value("math.deltaAngle", "Angle Difference", MATH, "The shortest turn from angle A to angle B, in degrees (-180 to 180)", "Degrees", NUMBER,
                "deltaAngle({0}, {1})", pin("A", NUMBER, 0.0), pin("B", NUMBER, 90.0));
        value("math.pi", "Pi", MATH, "3.14159...", "Pi", NUMBER, "PI");
        // vectors
        value("math.normalize", "Normalize", MATH, "The same direction with length 1", "Direction", VEC3, "({0}).normalized()",
                pin("Vec3", VEC3, new Vec3(1, 0, 0))).keywords("direction unit");
        value("math.length", "Length", MATH, "How long a vec3 is (a speed, a distance)", "Length", NUMBER, "({0}).length", pin("Vec3", VEC3, new Vec3(1, 0, 0)))
                .keywords("magnitude size");
        value("math.dot", "Dot", MATH, "How much two directions point the same way (1 = same, 0 = sideways, -1 = opposite)", "Result", NUMBER,
                "({0}).dot({1})", pin("A", VEC3, new Vec3(0, 0, -1)), pin("B", VEC3, new Vec3(0, 0, -1)));
        value("math.cross", "Cross", MATH, "A direction at right angles to both", "Result", VEC3, "({0}).cross({1})",
                pin("A", VEC3, new Vec3(1, 0, 0)), pin("B", VEC3, new Vec3(0, 1, 0)));
        value("math.directionTo", "Direction To", MATH, "Which way to go from one point to another (length 1)", "Direction", VEC3,
                "(({1}) - ({0})).normalized()", pin("From", VEC3, new Vec3()), pin("To", VEC3, new Vec3(0, 0, -1))).keywords("toward aim");
        value("math.rotateVec", "Rotate Vector", MATH, "A direction turned some degrees around an axis", "Result", VEC3, "({0}).rotate({1}, {2})",
                pin("Vec3", VEC3, new Vec3(0, 0, -1)), pin("Axis", VEC3, new Vec3(0, 1, 0)), pin("Degrees", NUMBER, 90.0));
    }

    private static void operator(String id, String title, String symbol, ValueType inType, Object a, Object b, ValueType outType) {
        add(NodeType.builder(id, title, MATH).keywords(symbol)
                .in("A", inType, a).in("B", inType, b).out("Result", outType)
                .value((code, n, out) -> "(" + code.input(n, 0) + " " + symbol + " " + code.input(n, 1) + ")"));
    }

    private static void function1(String id, String title, String function, String description) {
        value(id, title, MATH, description, "Result", NUMBER, function + "({0})", pin("Value", NUMBER, 0.0));
    }

    // ---------------------------------------------------------------- text & lists

    private static void text() {
        value("text.join", "Join Text", TEXT, "A then B as one piece of text (numbers too): \"Score: \" + 10", "Text", STRING, "(str({0}) + str({1}))",
                pin("A", ANY, "Score: "), pin("B", ANY, 0.0)).keywords("concat combine append string");
        value("text.toText", "To Text", TEXT, "Any value as text", "Text", STRING, "str({0})", pin("Value", ANY, 0.0)).keywords("string convert");
        value("text.toNumber", "To Number", TEXT, "Text like \"42\" as a number", "Number", NUMBER, "num({0})", pin("Text", STRING, "42")).keywords("parse convert");
        value("text.fixed", "Format Number", TEXT, "A number with exactly this many decimals: 3.14159 -> \"3.14\"", "Text", STRING, "fixed({0}, {1})",
                pin("Number", NUMBER, 3.14159), pin("Decimals", NUMBER, 2.0)).keywords("decimal round string");
        value("text.upper", "Upper Case", TEXT, "TEXT IN CAPITALS", "Text", STRING, "upper({0})", pin("Text", STRING, "hello"));
        value("text.lower", "Lower Case", TEXT, "text in small letters", "Text", STRING, "lower({0})", pin("Text", STRING, "HELLO"));
        value("text.contains", "Contains", TEXT, "True if text has a piece in it, or a list has an item", "Result", BOOL, "({1} in {0})",
                pin("Text or List", ANY, "hello"), pin("Piece", ANY, "ell")).keywords("includes has in find");
        value("text.length", "Length Of", TEXT, "How many letters in text, or items in a list", "Length", NUMBER, "len({0})", pin("Text or List", ANY, "hello"))
                .keywords("count size len");
        value("text.replace", "Replace Text", TEXT, "Text with every Find swapped for With", "Text", STRING, "replace({0}, {1}, {2})",
                pin("Text", STRING, "Hello NAME"), pin("Find", STRING, "NAME"), pin("With", STRING, "Bo"));
        value("text.split", "Split Text", TEXT, "Text cut into a list at each Separator: \"a,b\" -> [a, b]", "List", LIST, "split({0}, {1})",
                pin("Text", STRING, "a,b,c"), pin("Separator", STRING, ","));
        add(NodeType.builder("list.make", "Make List", TEXT).describe("A list of the things wired in (or of all four typed values, if nothing is wired)")
                .in("A", ANY, 1.0).in("B", ANY, 2.0).in("C", ANY, 3.0).in("D", ANY, 4.0).out("List", LIST).keywords("array new")
                .value((code, n, out) -> {
                    List<String> items = new ArrayList<>();
                    for (int i = 0; i < 4; i++) if (code.wired(n, i)) items.add(code.input(n, i));
                    if (items.isEmpty()) for (int i = 0; i < 4; i++) items.add(code.input(n, i));
                    return "[" + String.join(", ", items) + "]";
                }));
        value("list.empty", "Empty List", TEXT, "A new list with nothing in it", "List", LIST, "[]");
        value("list.get", "Get Item", TEXT, "The item at a position (0 is the first, -1 the last)", "Item", ANY, "{0}[{1}]", pin("List", LIST, null),
                pin("Index", NUMBER, 0.0)).keywords("index element");
        value("list.random", "Random Item", TEXT, "A random item from a list (none if it's empty)", "Item", ANY, "(len({0}) > 0 ? choose({0}) : none)",
                pin("List", LIST, null)).keywords("choose pick");
        value("list.indexOf", "Index Of", TEXT, "Where an item is in a list (-1 if it isn't)", "Index", NUMBER, "indexOf({0}, {1})", pin("List", LIST, null),
                pin("Item", ANY, 0.0)).keywords("find position");
        value("list.join", "Join List", TEXT, "A list's items as one piece of text, with Separator between", "Text", STRING, "join({0}, {1})",
                pin("List", LIST, null), pin("Separator", STRING, ", "));
        action("list.add", "Add Item", TEXT, "Puts an item on the end of a list", "push({0}, {1})", pin("List", LIST, null), pin("Item", ANY, 0.0))
                .keywords("push append insert");
        action("list.remove", "Remove Item", TEXT, "Takes the first matching item out of a list", "removeItem({0}, {1})", pin("List", LIST, null),
                pin("Item", ANY, 0.0)).keywords("delete erase");
        action("list.removeAt", "Remove At", TEXT, "Takes out the item at a position (0 is the first)", "if len({0}) > 0 { remove({0}, {1}) }",
                pin("List", LIST, null), pin("Index", NUMBER, 0.0));
        action("list.set", "Set Item", TEXT, "Changes the item at a position", "{0}[{1}] = {2}", pin("List", LIST, null), pin("Index", NUMBER, 0.0),
                pin("Item", ANY, 0.0));
        action("list.clear", "Clear List", TEXT, "Empties a list", "clear({0})", pin("List", LIST, null));
        action("list.shuffle", "Shuffle List", TEXT, "Mixes a list into a random order", "shuffle({0})", pin("List", LIST, null));
        action("list.sort", "Sort List", TEXT, "Puts a list of numbers or text in order", "sort({0})", pin("List", LIST, null));
    }

    // ---------------------------------------------------------------- variables

    private static void variables() {
        add(NodeType.builder(GET_VARIABLE, "Get Variable", VARIABLES).variable()
                .out("Value", ANY)
                .value((code, n, out) -> {
                    String name = code.variable(n);
                    return name == null ? "none" : name;
                }));
        add(NodeType.builder(SET_VARIABLE, "Set Variable", VARIABLES).variable()
                .execIn().in("Value", ANY, 0.0).execOut("")
                .emit((code, n) -> {
                    String name = code.variable(n);
                    if (name == null) code.line("// Set Variable: no variable chosen", n);
                    else code.line(name + " = " + code.input(n, 1), n);
                    code.follow(n, 0);
                }));
        add(NodeType.builder("variable.add", "Add To Variable", VARIABLES).variable()
                .describe("Adds to a number variable (use a negative number to take away): score + 10, health - 25")
                .execIn().in("Amount", NUMBER, 1.0).execOut("").keywords("increment increase plus count")
                .emit((code, n) -> {
                    String name = code.variable(n);
                    if (name == null) code.line("// Add To Variable: no variable chosen", n);
                    else code.line(name + " += " + code.input(n, 1), n);
                    code.follow(n, 0);
                }));
    }
}
