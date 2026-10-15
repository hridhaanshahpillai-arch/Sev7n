package sev7n.eights;

import java.util.ArrayList;
import java.util.List;

/**
 * A kind of node, like "Branch" or "Move": its pins and how it turns into Seven code.
 * All node types live in NodeLibrary.
 */
public final class NodeType {

    /** Groups in the Add Node menu, in the order shown. */
    public enum Category {
        EVENTS("Events"), FLOW("Flow"), ACTIONS("Objects"), PHYSICS("Physics"), INPUT("Input"),
        CHARACTERS("Characters & NPCs"), HUD("HUD & UI"), SOUND("Sound"), SCENES("Scenes & Saves"),
        WORLD("World & Effects"), VALUES("Values"), MATH("Math & Logic"), TEXT("Text & Lists"), VARIABLES("Variables");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    /** Writes the Seven statements for a node that runs (has an exec input). */
    @FunctionalInterface
    public interface Statement {
        void emit(Compiler.Context code, Node node);
    }

    /** Produces the Seven expression for one of a node's data outputs. */
    @FunctionalInterface
    public interface Expression {
        String of(Compiler.Context code, Node node, int output);
    }

    /**
     * Where an event's chain runs: a behaviour function such as onSee(other).
     * 'prelude' is a line written first (e.g. "_dt = dt"), or "".
     */
    public record EventFunction(String name, String params, String prelude) { }

    @FunctionalInterface
    public interface EventPlacement {
        EventFunction of(Compiler.Context code, Node node);
    }

    /** Variables a node keeps between frames (a timer, a "done once" flag), as "var" lines; names should include the node id. */
    @FunctionalInterface
    public interface Fields {
        List<String> of(Node node);
    }

    public final String id;
    public final String title;
    public final Category category;
    public final String description;
    public final List<PinDef> inputs;
    public final List<PinDef> outputs;
    public final boolean usesVariable;
    final Statement statement;
    final Expression expression;
    /** Events that get a function of their own (On See, On Message...). */
    final EventPlacement event;
    /** Events that run inside update(dt), wrapped in a check (On Key Pressed, Every N Seconds...): writes the whole block. */
    final Statement inUpdate;
    final Fields fields;
    /** Extra code some nodes add to update(dt), wherever they are (Delay counts down there). */
    final Statement everyFrame;
    /** Search words besides the title, e.g. "if" for Branch. */
    public final String keywords;

    private NodeType(Builder b) {
        this.id = b.id;
        this.title = b.title;
        this.category = b.category;
        this.description = b.description;
        this.inputs = List.copyOf(b.inputs);
        this.outputs = List.copyOf(b.outputs);
        this.usesVariable = b.usesVariable;
        this.statement = b.statement;
        this.expression = b.expression;
        this.event = b.event;
        this.inUpdate = b.inUpdate;
        this.fields = b.fields;
        this.everyFrame = b.everyFrame;
        this.keywords = b.keywords;
    }

    public boolean isEvent() {
        return category == Category.EVENTS;
    }

    static Builder builder(String id, String title, Category category) {
        return new Builder(id, title, category);
    }

    /** Fluent setup used by NodeLibrary, e.g. builder(...).execIn().in("Value", ANY, 0.0).execOut().emit(...). */
    static final class Builder {
        private final String id;
        private final String title;
        private final Category category;
        private String description = "";
        private final List<PinDef> inputs = new ArrayList<>();
        private final List<PinDef> outputs = new ArrayList<>();
        private boolean usesVariable;
        private Statement statement;
        private Expression expression;
        private EventPlacement event;
        private Statement inUpdate;
        private Fields fields;
        private Statement everyFrame;
        private String keywords = "";

        private Builder(String id, String title, Category category) {
            this.id = id;
            this.title = title;
            this.category = category;
        }

        Builder describe(String text) {
            description = text;
            return this;
        }

        Builder execIn() {
            inputs.add(new PinDef("", ValueType.EXEC, null));
            return this;
        }

        Builder execOut(String name) {
            outputs.add(new PinDef(name, ValueType.EXEC, null));
            return this;
        }

        Builder in(String name, ValueType type, Object defaultValue) {
            inputs.add(new PinDef(name, type, defaultValue));
            return this;
        }

        Builder out(String name, ValueType type) {
            outputs.add(new PinDef(name, type, null));
            return this;
        }

        Builder variable() {
            usesVariable = true;
            return this;
        }

        Builder emit(Statement s) {
            statement = s;
            return this;
        }

        Builder value(Expression e) {
            expression = e;
            return this;
        }

        Builder event(EventPlacement placement) {
            event = placement;
            return this;
        }

        Builder inUpdate(Statement s) {
            inUpdate = s;
            return this;
        }

        Builder fields(Fields f) {
            fields = f;
            return this;
        }

        Builder everyFrame(Statement s) {
            everyFrame = s;
            return this;
        }

        Builder keywords(String words) {
            keywords = words;
            return this;
        }

        NodeType build() {
            return new NodeType(this);
        }
    }
}
