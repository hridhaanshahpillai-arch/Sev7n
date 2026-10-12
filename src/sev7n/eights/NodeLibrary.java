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
 * To add a node: define it here with builder(...), and it shows up in the editor's menu.
 */
public final class NodeLibrary {

    public static final String ON_START = "event.start";
    public static final String ON_UPDATE = "event.update";
    public static final String ON_COLLIDE = "event.collide";
    public static final String KEY_PRESSED = "event.keyPressed";
    public static final String KEY_HELD = "event.keyHeld";
    public static final String GET_VARIABLE = "variable.get";
    public static final String SET_VARIABLE = "variable.set";

    private static final Map<String, NodeType> TYPES = new LinkedHashMap<>();

    static {
        events();
        flow();
        actions();
        values();
        math();
        variables();
    }

    private NodeLibrary() { }

    public static NodeType get(String id) {
        return TYPES.get(id);
    }

    public static List<NodeType> all() {
        return Collections.unmodifiableList(new ArrayList<>(TYPES.values()));
    }

    private static void add(NodeType.Builder builder) {
        NodeType type = builder.build();
        TYPES.put(type.id, type);
    }

    // ---------------------------------------------------------------- events: where chains start

    private static void events() {
        add(NodeType.builder(ON_START, "On Start", EVENTS).describe("Runs once when Play starts")
                .execOut(""));
        add(NodeType.builder(ON_UPDATE, "On Update", EVENTS).describe("Runs every frame")
                .execOut("").out("Delta Time", NUMBER)
                .value((code, n, out) -> "_dt"));
        add(NodeType.builder(ON_COLLIDE, "On Collide", EVENTS).describe("When this object's Box Collider starts touching another")
                .execOut("").out("Other", ENTITY)
                .value((code, n, out) -> "other"));
        add(NodeType.builder(KEY_PRESSED, "On Key Pressed", EVENTS).describe("The frame a key goes down")
                .in("Key", STRING, "space").execOut(""));
        add(NodeType.builder(KEY_HELD, "While Key Held", EVENTS).describe("Every frame while a key is down")
                .in("Key", STRING, "w").execOut(""));
    }

    // ---------------------------------------------------------------- flow

    private static void flow() {
        add(NodeType.builder("flow.branch", "Branch", FLOW).describe("If the condition is true, go one way, otherwise the other")
                .execIn().in("Condition", BOOL, true).execOut("True").execOut("False")
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
                .execIn().in("Times", NUMBER, 3.0).execOut("Loop").out("Index", NUMBER).execOut("Completed")
                .emit((code, n) -> {
                    code.block("for " + loopVar(n) + " in 0.." + code.input(n, 1) + " {", n, 0);
                    code.follow(n, 2);
                })
                .value((code, n, out) -> loopVar(n)));
    }

    private static String loopVar(Node n) {
        return "_i" + n.id;
    }

    // ---------------------------------------------------------------- actions: things that happen

    private static void actions() {
        add(NodeType.builder("action.print", "Print", ACTIONS).describe("Writes to the Console")
                .execIn().in("Value", ANY, "Hello from Eights").execOut("")
                .emit((code, n) -> {
                    code.line("print(" + code.input(n, 1) + ")", n);
                    code.follow(n, 0);
                }));
        add(NodeType.builder("action.move", "Move", ACTIONS).describe("Moves in a direction, at Speed units per second (use in On Update)")
                .execIn().in("Target", ENTITY, null).in("Direction", VEC3, new Vec3(0, 0, -1)).in("Speed", NUMBER, 5.0).execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".transform.position += " + code.input(n, 2)
                            + " * " + code.input(n, 3) + " * _dt", n);
                    code.follow(n, 0);
                }));
        add(NodeType.builder("action.rotate", "Rotate", ACTIONS).describe("Turns by degrees per second (use in On Update)")
                .execIn().in("Target", ENTITY, null).in("Degrees / sec", VEC3, new Vec3(0, 90, 0)).execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".transform.rotation += " + code.input(n, 2) + " * _dt", n);
                    code.follow(n, 0);
                }));
        add(NodeType.builder("action.setPosition", "Set Position", ACTIONS).describe("Puts an object somewhere")
                .execIn().in("Target", ENTITY, null).in("Position", VEC3, new Vec3()).execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".transform.position = " + code.input(n, 2), n);
                    code.follow(n, 0);
                }));
        add(NodeType.builder("action.lookAt", "Look At", ACTIONS).describe("Turns an object to face a point")
                .execIn().in("Target", ENTITY, null).in("Point", VEC3, new Vec3()).execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".transform.lookAt(" + code.input(n, 2) + ")", n);
                    code.follow(n, 0);
                }));
        add(NodeType.builder("action.setColor", "Set Color", ACTIONS).describe("Changes an object's color")
                .execIn().in("Target", ENTITY, null).in("Color", COLOR, new Color(0xBF9EEE)).execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".color = " + code.input(n, 2), n);
                    code.follow(n, 0);
                }));
        add(NodeType.builder("action.setTexture", "Set Texture", ACTIONS).describe("Changes an object's picture (an imported texture path)")
                .execIn().in("Target", ENTITY, null).in("Texture", STRING, "assets/textures/").execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".texture = " + code.input(n, 2), n);
                    code.follow(n, 0);
                }));
        add(NodeType.builder("action.addTag", "Add Tag", ACTIONS).describe("Gives an object a tag")
                .execIn().in("Target", ENTITY, null).in("Tag", STRING, "tag").execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".addTag(" + code.input(n, 2) + ")", n);
                    code.follow(n, 0);
                }));
        add(NodeType.builder("action.spawn", "Spawn", ACTIONS).describe("Creates a new object (cube, sphere, plane or cylinder)")
                .execIn().in("Mesh", STRING, "sphere").in("Position", VEC3, new Vec3(0, 1, 0)).execOut("").out("Spawned", ENTITY)
                .emit((code, n) -> {
                    code.line("var " + code.temp(n) + " = spawn(" + code.input(n, 1) + ", " + code.input(n, 2) + ")", n);
                    code.follow(n, 0);
                })
                .value((code, n, out) -> code.temp(n)));
        add(NodeType.builder("action.destroy", "Destroy", ACTIONS).describe("Removes an object at the end of the frame")
                .execIn().in("Target", ENTITY, null).execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".destroy()", n);
                    code.follow(n, 0);
                }));
        // physics, sprites and tilemaps
        setter("action.setVelocity", "Set Velocity", "Sets how fast a Rigidbody moves (units per second)",
                "Velocity", VEC3, new Vec3(0, 5, 0), ".velocity = ");
        add(NodeType.builder("action.push", "Push", ACTIONS).describe("Adds to a Rigidbody's velocity: a jump, a knock-back")
                .execIn().in("Target", ENTITY, null).in("Amount", VEC3, new Vec3(0, 6, 0)).execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".push(" + code.input(n, 2) + ")", n);
                    code.follow(n, 0);
                }));
        add(NodeType.builder("action.play", "Play Animation", ACTIONS).describe("Switches a Sprite Animator to another animation")
                .execIn().in("Target", ENTITY, null).in("Animation", STRING, "run").execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".play(" + code.input(n, 2) + ")", n);
                    code.follow(n, 0);
                }));
        setter("action.flipX", "Set Flip X", "Mirrors a sprite left-right (face the other way)",
                "Flip", BOOL, true, ".flipX = ");
        add(NodeType.builder("action.setTile", "Set Tile", ACTIONS).describe("Changes one cell of a Tilemap (-1 clears it)")
                .execIn().in("Target", ENTITY, null).in("X", NUMBER, 0.0).in("Y", NUMBER, 0.0).in("Tile", NUMBER, 0.0).execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + ".setTile(" + code.input(n, 2) + ", " + code.input(n, 3) + ", " + code.input(n, 4) + ")", n);
                    code.follow(n, 0);
                }));
    }

    /** An action that sets one property of the target: "target.property = value". */
    private static void setter(String id, String title, String description, String input, ValueType type, Object fallback, String assignment) {
        add(NodeType.builder(id, title, ACTIONS).describe(description)
                .execIn().in("Target", ENTITY, null).in(input, type, fallback).execOut("")
                .emit((code, n) -> {
                    code.line(code.input(n, 1) + assignment + code.input(n, 2), n);
                    code.follow(n, 0);
                }));
    }

    // ---------------------------------------------------------------- values: information to use

    private static void values() {
        add(NodeType.builder("value.self", "Self", VALUES).describe("The object this graph is attached to")
                .out("Self", ENTITY).value((code, n, out) -> "entity"));
        add(NodeType.builder("value.find", "Find", VALUES).describe("Finds an object by name")
                .in("Name", STRING, "Player").out("Entity", ENTITY)
                .value((code, n, out) -> "find(" + code.input(n, 0) + ")"));
        add(NodeType.builder("value.position", "Get Position", VALUES)
                .in("Target", ENTITY, null).out("Position", VEC3)
                .value((code, n, out) -> code.input(n, 0) + ".transform.position"));
        add(NodeType.builder("value.forward", "Get Forward", VALUES).describe("The direction an object faces")
                .in("Target", ENTITY, null).out("Forward", VEC3)
                .value((code, n, out) -> code.input(n, 0) + ".transform.forward"));
        add(NodeType.builder("value.name", "Get Name", VALUES)
                .in("Target", ENTITY, null).out("Name", STRING)
                .value((code, n, out) -> code.input(n, 0) + ".name"));
        add(NodeType.builder("value.hasTag", "Has Tag", VALUES)
                .in("Target", ENTITY, null).in("Tag", STRING, "player").out("Result", BOOL)
                .value((code, n, out) -> code.input(n, 0) + ".hasTag(" + code.input(n, 1) + ")"));
        add(NodeType.builder("value.keyHeld", "Is Key Held", VALUES)
                .in("Key", STRING, "space").out("Held", BOOL)
                .value((code, n, out) -> "key(" + code.input(n, 0) + ")"));
        add(NodeType.builder("value.makeVec3", "Make Vec3", VALUES)
                .in("X", NUMBER, 0.0).in("Y", NUMBER, 0.0).in("Z", NUMBER, 0.0).out("Vec3", VEC3)
                .value((code, n, out) -> "vec3(" + code.input(n, 0) + ", " + code.input(n, 1) + ", " + code.input(n, 2) + ")"));
        add(NodeType.builder("value.breakVec3", "Break Vec3", VALUES)
                .in("Vec3", VEC3, new Vec3()).out("X", NUMBER).out("Y", NUMBER).out("Z", NUMBER)
                .value((code, n, out) -> "(" + code.input(n, 0) + ")." + "xyz".charAt(out)));
        add(NodeType.builder("value.distance", "Distance", VALUES)
                .in("A", VEC3, new Vec3()).in("B", VEC3, new Vec3()).out("Distance", NUMBER)
                .value((code, n, out) -> "distance(" + code.input(n, 0) + ", " + code.input(n, 1) + ")"));
        add(NodeType.builder("value.random", "Random", VALUES).describe("A random number between Min and Max")
                .in("Min", NUMBER, 0.0).in("Max", NUMBER, 1.0).out("Value", NUMBER)
                .value((code, n, out) -> "random(" + code.input(n, 0) + ", " + code.input(n, 1) + ")"));
        add(NodeType.builder("value.time", "Time", VALUES).describe("Seconds since Play started")
                .out("Seconds", NUMBER).value((code, n, out) -> "time()"));
        add(NodeType.builder("value.velocity", "Get Velocity", VALUES).describe("How fast a Rigidbody is moving")
                .in("Target", ENTITY, null).out("Velocity", VEC3)
                .value((code, n, out) -> code.input(n, 0) + ".velocity"));
        add(NodeType.builder("value.grounded", "Is Grounded", VALUES).describe("True while a Rigidbody stands on something")
                .in("Target", ENTITY, null).out("Grounded", BOOL)
                .value((code, n, out) -> code.input(n, 0) + ".grounded"));
    }

    // ---------------------------------------------------------------- math & logic

    private static void math() {
        operator("math.add", "Add", "+", ANY, 0.0, 1.0, ANY);
        operator("math.subtract", "Subtract", "-", ANY, 0.0, 1.0, ANY);
        operator("math.multiply", "Multiply", "*", ANY, 1.0, 2.0, ANY);
        operator("math.divide", "Divide", "/", ANY, 1.0, 2.0, ANY);
        operator("math.greater", "Greater Than", ">", NUMBER, 0.0, 0.0, BOOL);
        operator("math.less", "Less Than", "<", NUMBER, 0.0, 0.0, BOOL);
        operator("math.equals", "Equals", "==", ANY, 0.0, 0.0, BOOL);
        operator("logic.and", "And", "and", BOOL, true, true, BOOL);
        operator("logic.or", "Or", "or", BOOL, false, false, BOOL);
        add(NodeType.builder("logic.not", "Not", MATH)
                .in("Value", BOOL, false).out("Result", BOOL)
                .value((code, n, out) -> "(not " + code.input(n, 0) + ")"));
    }

    private static void operator(String id, String title, String symbol, ValueType inType, Object a, Object b, ValueType outType) {
        add(NodeType.builder(id, title, MATH)
                .in("A", inType, a).in("B", inType, b).out("Result", outType)
                .value((code, n, out) -> "(" + code.input(n, 0) + " " + symbol + " " + code.input(n, 1) + ")"));
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
    }
}
