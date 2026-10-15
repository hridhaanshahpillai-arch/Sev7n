package sev7n.editor;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.extension.imnodes.ImNodes;
import imgui.extension.imnodes.ImNodesEditorContext;
import imgui.extension.imnodes.ImNodesStyle;
import imgui.extension.imnodes.flag.ImNodesAttributeFlags;
import imgui.extension.imnodes.flag.ImNodesCol;
import imgui.extension.imnodes.flag.ImNodesMiniMapLocation;
import imgui.extension.imnodes.flag.ImNodesPinShape;
import imgui.extension.imnodes.flag.ImNodesStyleVar;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiTabItemFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import sev7n.eights.Compiler;
import sev7n.eights.Graph;
import sev7n.eights.GraphFile;
import sev7n.eights.Link;
import sev7n.eights.Node;
import sev7n.eights.NodeLibrary;
import sev7n.eights.NodeType;
import sev7n.eights.PinDef;
import sev7n.eights.ValueType;
import sev7n.eights.Variable;
import sev7n.math.Vec3;
import sev7n.project.Project;
import sev7n.ui.Theme;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eights: Sev7n's visual scripting (like Unreal Blueprints).
 *
 *   right-click the canvas   add a node        drag pin to pin      connect
 *   drag a wire into space   add + connect     drag from an input   unplug
 *   Delete                   remove selection  middle-drag          pan
 *
 * White wires are "then do this"; colored wires carry values.
 */
final class EightsPanel {

    static final String TITLE = "Eights";
    private static final float SIDEBAR_WIDTH = 200;
    private static final ArrayDeque<Integer> NO_TYPING = new ArrayDeque<>(); // the code view is read-only

    /** One open graph tab. */
    private static final class OpenGraph {
        final Path file;
        final String name;
        final Graph graph;
        final ImNodesEditorContext editor = ImNodes.editorContextCreate();
        final Set<Integer> placed = new HashSet<>();            // nodes whose position was given to imnodes
        final Map<Integer, ImVec2> placeAtScreen = new HashMap<>(); // new nodes to drop where the mouse was
        final Map<String, ImString> textBuffers = new HashMap<>();
        int savedVersion;
        boolean showCode;
        CodeEditor codeView;
        int codeVersion = -1;
        int errorNode = -1;
        int errorVersion = -1;
        int moveToNode = -1;

        OpenGraph(Path file, Graph graph) {
            this.file = file;
            this.name = Project.behaviourName(file);
            this.graph = graph;
            this.savedVersion = graph.version();
        }

        boolean modified() {
            return graph.version() != savedVersion;
        }
    }

    private final EditorContext ctx;
    private final Map<Path, OpenGraph> open = new LinkedHashMap<>();
    private final ImString search = new ImString(64);
    private Path tabToSelect;
    private boolean focusRequested;
    private boolean focused;

    // add-node menu state
    private boolean openAddMenu;
    private final ImVec2 addMenuAt = new ImVec2();
    private int pendingPin = -1; // a wire dropped in empty space: connect the new node to this pin
    private int menuNode = -1;   // the node a right-click menu is for

    EightsPanel(EditorContext ctx) {
        this.ctx = ctx;
    }

    /** Dracula Soft look for the node canvas. Call once after ImNodes.createContext(). */
    static void applyStyle(float scale) {
        ImNodesStyle style = ImNodes.getStyle();
        style.setGridSpacing(24 * scale);
        style.setNodeCornerRounding(6 * scale);
        style.setNodePadding(8 * scale, 5 * scale);
        style.setNodeBorderThickness(1);
        style.setLinkThickness(2.5f * scale);
        style.setPinCircleRadius(4.5f * scale);
        style.setPinTriangleSideLength(9 * scale);
        style.setPinOffset(0);
        color(style, ImNodesCol.GridBackground, Theme.EDITOR);
        color(style, ImNodesCol.GridLine, Theme.SURFACE);
        color(style, ImNodesCol.GridLinePrimary, Theme.SURFACE_HI);
        color(style, ImNodesCol.NodeBackground, Theme.PANEL);
        color(style, ImNodesCol.NodeBackgroundHovered, Theme.SURFACE);
        color(style, ImNodesCol.NodeBackgroundSelected, Theme.SURFACE);
        color(style, ImNodesCol.NodeOutline, Theme.BLACK);
        color(style, ImNodesCol.LinkHovered, Theme.ACCENT_HOVER);
        color(style, ImNodesCol.LinkSelected, Theme.ACCENT);
        color(style, ImNodesCol.BoxSelector, EditorStyle.withAlpha(Theme.ACCENT, 40));
        color(style, ImNodesCol.BoxSelectorOutline, Theme.ACCENT);
        color(style, ImNodesCol.MiniMapBackground, EditorStyle.withAlpha(Theme.BLACK, 200));
        color(style, ImNodesCol.MiniMapOutline, Theme.SURFACE_HI);
        color(style, ImNodesCol.MiniMapNodeBackground, Theme.SURFACE_HI);
        color(style, ImNodesCol.MiniMapNodeBackgroundSelected, Theme.ACCENT);
        color(style, ImNodesCol.MiniMapLink, Theme.TEXT_FAINT);
        color(style, ImNodesCol.MiniMapCanvas, EditorStyle.withAlpha(Theme.EDITOR, 120));
        color(style, ImNodesCol.MiniMapCanvasOutline, Theme.TEXT_FAINT);
    }

    private static void color(ImNodesStyle style, int slot, Color c) {
        style.setColors(slot, EditorStyle.pack(c));
    }

    // ---------------------------------------------------------------- opening & saving

    /** Opens a graph in a tab. With line > 0 (from a Console error), highlights the node behind that line. */
    void open(Path file, int line) {
        Path key = file.toAbsolutePath().normalize();
        OpenGraph og = open.get(key);
        if (og == null) {
            try {
                og = new OpenGraph(key, GraphFile.load(key));
            } catch (IOException | IllegalArgumentException e) {
                ctx.log.error("Couldn't open " + key.getFileName() + ": " + e.getMessage());
                return;
            }
            open.put(key, og);
        }
        if (line > 0) {
            int node = Compiler.compile(og.graph, og.name).nodeAtLine(line);
            if (node >= 0) {
                og.errorNode = node;
                og.errorVersion = og.graph.version();
                og.moveToNode = node;
            }
        }
        tabToSelect = key;
        focusRequested = true;
    }

    boolean anyModified() {
        return open.values().stream().anyMatch(OpenGraph::modified);
    }

    boolean focused() {
        return focused;
    }

    void saveAll() {
        for (OpenGraph og : open.values()) {
            if (og.modified()) save(og);
        }
    }

    private void save(OpenGraph og) {
        try {
            GraphFile.save(og.graph, og.file);
            og.savedVersion = og.graph.version();
            ctx.log.system("Saved " + og.file.getFileName());
        } catch (IOException e) {
            ctx.log.error("Couldn't save " + og.file.getFileName() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- panel

    void draw() {
        if (focusRequested) {
            ImGui.setWindowFocus(TITLE);
            focusRequested = false;
        }
        if (!ImGui.begin(TITLE)) {
            focused = false;
            ImGui.end();
            return;
        }
        focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);

        if (open.isEmpty()) {
            Widgets.hint("Eights is visual scripting: connect nodes instead of typing code.\n\n"
                    + "Make a graph with + Eights in the Project panel, then attach it to an object "
                    + "like any behaviour (drag it onto the object in the Hierarchy).");
        } else if (ImGui.beginTabBar("##graphs", ImGuiTabBarFlags.Reorderable | ImGuiTabBarFlags.FittingPolicyScroll)) {
            List<Path> closed = new ArrayList<>();
            for (OpenGraph og : open.values()) {
                ImBoolean keepOpen = new ImBoolean(true);
                int flags = (og.file.equals(tabToSelect) ? ImGuiTabItemFlags.SetSelected : 0)
                        | (og.modified() ? ImGuiTabItemFlags.UnsavedDocument : 0);
                if (ImGui.beginTabItem(og.file.getFileName() + "###" + og.file, keepOpen, flags)) {
                    drawGraph(og);
                    ImGui.endTabItem();
                }
                if (!keepOpen.get()) closed.add(og.file);
            }
            ImGui.endTabBar();
            for (Path file : closed) {
                OpenGraph og = open.remove(file);
                if (og.modified()) save(og); // closing a tab never loses work
                ImNodes.editorContextFree(og.editor);
            }
        }
        tabToSelect = null;
        ImGui.end();
    }

    private void drawGraph(OpenGraph og) {
        if (og.errorNode >= 0 && og.graph.version() != og.errorVersion) og.errorNode = -1; // edited: clear the error mark

        if (ImGui.smallButton("Save")) save(og);
        ImGui.sameLine();
        if (ImGui.checkbox("Show Seven code", og.showCode)) og.showCode = !og.showCode;
        ImGui.sameLine();
        ImGui.textDisabled("   right-click: add node  ·  drag between pins: connect  ·  Del: delete  ·  middle-drag: pan");

        ImGui.beginChild("##variables", SIDEBAR_WIDTH * scale(), 0, true);
        drawVariables(og);
        ImGui.endChild();
        ImGui.sameLine();

        float canvasWidth = og.showCode ? ImGui.getContentRegionAvailX() * 0.6f : 0;
        ImGui.beginChild("##canvas", canvasWidth, 0, false, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        drawCanvas(og);
        ImGui.endChild();

        if (og.showCode) {
            ImGui.sameLine();
            drawCode(og);
        }
    }

    // ---------------------------------------------------------------- variables sidebar

    private void drawVariables(OpenGraph og) {
        ImGui.textDisabled("VARIABLES");
        Variable removed = null;
        for (int i = 0; i < og.graph.variables.size(); i++) {
            Variable v = og.graph.variables.get(i);
            ImGui.pushID("var" + i);
            ImString nameBuffer = og.textBuffers.computeIfAbsent("var-name-" + System.identityHashCode(v), k -> new ImString(v.name, 64));
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - ImGui.getFrameHeight() - 4);
            if (ImGui.inputText("##name", nameBuffer)) rename(og, v, nameBuffer.get());
            ImGui.sameLine(0, 4);
            if (ImGui.button("x", ImGui.getFrameHeight(), 0)) removed = v;
            if (ImGui.isItemHovered()) ImGui.setTooltip("Remove variable");

            ImGui.setNextItemWidth(ImGui.getFontSize() * 4.5f);
            if (ImGui.beginCombo("##type", v.type.label)) {
                for (ValueType type : ValueType.VARIABLE_TYPES) {
                    if (ImGui.selectable(type.label, type == v.type) && type != v.type) {
                        v.type = type;
                        v.value = type.defaultValue();
                        og.graph.touch();
                    }
                }
                ImGui.endCombo();
            }
            if (v.type == ValueType.VEC3) ImGui.textDisabled("starts at"); // vec3 fields need a row of their own
            else ImGui.sameLine();
            Object edited = editValue(og, "var-value-" + System.identityHashCode(v), v.type, v.value,
                    ImGui.getContentRegionAvailX());
            if (edited != null) {
                v.value = edited;
                og.graph.touch();
            }
            ImGui.separator();
            ImGui.popID();
        }
        if (removed != null) {
            og.graph.variables.remove(removed);
            og.graph.touch();
        }
        if (ImGui.button("+ Variable", -1, 0)) {
            og.graph.variables.add(new Variable(uniqueVariableName(og.graph), ValueType.NUMBER, 0.0));
            og.graph.touch();
        }
        ImGui.spacing();
        Widgets.hint("Variables belong to each object, like a behaviour's fields. "
                + "Use them with Get and Set nodes (right-click the canvas > Variables).");
    }

    private static void rename(OpenGraph og, Variable v, String newName) {
        for (Node n : og.graph.nodes()) {
            if (n.type.usesVariable && n.variable.equals(v.name)) n.variable = newName;
        }
        v.name = newName;
        og.graph.touch();
    }

    private static String uniqueVariableName(Graph graph) {
        for (int i = 1; ; i++) {
            String name = i == 1 ? "value" : "value" + i;
            if (graph.variable(name) == null) return name;
        }
    }

    // ---------------------------------------------------------------- canvas

    private void drawCanvas(OpenGraph og) {
        ImNodes.editorContextSet(og.editor);
        if (og.moveToNode >= 0) {
            ImNodes.editorContextMoveToNode(og.moveToNode);
            og.moveToNode = -1;
        }

        ImNodes.beginNodeEditor();
        ImNodes.pushAttributeFlag(ImNodesAttributeFlags.EnableLinkDetachWithDragClick);
        for (Node node : og.graph.nodes()) drawNode(og, node);
        for (Link link : og.graph.links()) {
            ImNodes.pushColorStyle(ImNodesCol.Link, EditorStyle.pack(link.type().color));
            ImNodes.link(link.id(), pinId(link.from(), link.output(), true), pinId(link.to(), link.input(), false));
            ImNodes.popColorStyle();
        }
        ImNodes.popAttributeFlag();
        ImNodes.miniMap(0.15f, ImNodesMiniMapLocation.BottomRight);
        ImNodes.endNodeEditor();

        handleWiring(og);
        trackPositions(og);
        handleMenus(og);
        if (focused && !ImGui.getIO().getWantTextInput() && ImGui.isKeyPressed(ImGuiKey.Delete, false)) deleteSelection(og);
        drawAddNodeMenu(og);
        drawNodeMenu(og);
    }

    private void drawNode(OpenGraph og, Node node) {
        if (og.placed.add(node.id)) {
            ImVec2 screen = og.placeAtScreen.remove(node.id);
            if (screen != null) ImNodes.setNodeScreenSpacePos(node.id, screen.x, screen.y);
            else ImNodes.setNodeGridSpacePos(node.id, node.x, node.y);
        }

        Color title = categoryColor(node.type.category);
        ImNodes.pushColorStyle(ImNodesCol.TitleBar, EditorStyle.pack(title));
        ImNodes.pushColorStyle(ImNodesCol.TitleBarHovered, EditorStyle.pack(title.brighter()));
        ImNodes.pushColorStyle(ImNodesCol.TitleBarSelected, EditorStyle.pack(title.brighter()));
        boolean error = node.id == og.errorNode;
        if (error) {
            ImNodes.pushColorStyle(ImNodesCol.NodeOutline, EditorStyle.pack(Theme.ERROR));
            ImNodes.pushStyleVar(ImNodesStyleVar.NodeBorderThickness, 3);
        }

        ImNodes.beginNode(node.id);
        ImNodes.beginNodeTitleBar();
        ImGui.textColored(EditorStyle.pack(Theme.BLACK), node.displayTitle());
        ImNodes.endNodeTitleBar();
        if (ImGui.isItemHovered() && !node.type.description.isEmpty()) ImGui.setTooltip(node.type.description);

        // Unreal-style header row: exec in on the left, the main exec out on the right
        float rowWidth = ImGui.getFontSize() * 7;
        boolean execIn = !node.type.inputs.isEmpty() && node.type.inputs.get(0).isExec();
        boolean execOut = !node.type.outputs.isEmpty() && node.type.outputs.get(0).isExec()
                && node.type.outputs.get(0).name().isEmpty();
        if (execIn) drawInput(og, node, 0);
        if (execOut) drawOutput(og, node, 0, rowWidth, execIn);

        if (node.type.usesVariable) drawVariablePicker(og, node);
        for (int i = execIn ? 1 : 0; i < node.type.inputs.size(); i++) drawInput(og, node, i);
        for (int o = execOut ? 1 : 0; o < node.type.outputs.size(); o++) drawOutput(og, node, o, rowWidth, false);
        ImNodes.endNode();

        if (error) {
            ImNodes.popStyleVar();
            ImNodes.popColorStyle();
        }
        ImNodes.popColorStyle();
        ImNodes.popColorStyle();
        ImNodes.popColorStyle();
    }

    private void drawVariablePicker(OpenGraph og, Node node) {
        ImGui.setNextItemWidth(ImGui.getFontSize() * 8);
        if (ImGui.beginCombo("##variable" + node.id, node.variable.isEmpty() ? "(choose)" : node.variable)) {
            for (Variable v : og.graph.variables) {
                if (ImGui.selectable(v.name, v.name.equals(node.variable))) {
                    node.variable = v.name;
                    og.graph.touch();
                }
            }
            if (og.graph.variables.isEmpty()) ImGui.textDisabled("Add one in the sidebar");
            ImGui.endCombo();
        }
    }

    private void drawInput(OpenGraph og, Node node, int input) {
        PinDef pin = node.type.inputs.get(input);
        boolean connected = og.graph.linkTo(node, input) != null;
        ImNodes.pushColorStyle(ImNodesCol.Pin, EditorStyle.pack(pin.type().color));
        ImNodes.beginInputAttribute(pinId(node, input, false), pinShape(pin, connected));
        ImGui.text(pin.name().isEmpty() ? " " : pin.name());
        if (!pin.isExec() && !connected) {
            ImGui.sameLine();
            float width = ImGui.getFontSize() * (pin.type() == ValueType.VEC3 ? 8f : 4.5f);
            Object edited = editValue(og, node.id + ":" + input, pin.type(), node.value(input), width);
            if (edited != null) {
                node.setValue(input, edited);
                og.graph.touch();
            }
        }
        ImNodes.endInputAttribute();
        ImNodes.popColorStyle();
    }

    /** Outputs are right-aligned to rowWidth. 'besideInput' puts it on the same row as the exec input. */
    private void drawOutput(OpenGraph og, Node node, int output, float rowWidth, boolean besideInput) {
        PinDef pin = node.type.outputs.get(output);
        boolean connected = og.graph.linkFrom(node, output) != null;
        String label = pin.name().isEmpty() ? " " : pin.name();
        float offset = Math.max(0, rowWidth - ImGui.calcTextSize(label).x);
        if (besideInput) ImGui.sameLine(offset);

        ImNodes.pushColorStyle(ImNodesCol.Pin, EditorStyle.pack(pin.type().color));
        ImNodes.beginOutputAttribute(pinId(node, output, true), pinShape(pin, connected));
        if (!besideInput) ImGui.indent(offset);
        ImGui.text(label);
        if (!besideInput) ImGui.unindent(offset);
        ImNodes.endOutputAttribute();
        ImNodes.popColorStyle();
    }

    private static int pinShape(PinDef pin, boolean connected) {
        if (pin.isExec()) return connected ? ImNodesPinShape.TriangleFilled : ImNodesPinShape.Triangle;
        return connected ? ImNodesPinShape.CircleFilled : ImNodesPinShape.Circle;
    }

    // ---------------------------------------------------------------- inline value fields

    /** Draws a small editor for a value. Returns the new value if the user changed it, else null. */
    private Object editValue(OpenGraph og, String id, ValueType type, Object value, float width) {
        ImGui.pushID(id);
        Object result = null;
        switch (type) {
            case NUMBER -> result = editNumber(value, width);
            case BOOL -> {
                boolean b = Boolean.TRUE.equals(value);
                if (ImGui.checkbox("##b", b)) result = !b;
            }
            case STRING -> result = editText(og, id, value, width);
            case VEC3 -> {
                Vec3 v = value instanceof Vec3 vec ? vec.copy() : new Vec3();
                double[] parts = v.toArray();
                float part = (width - 2 * ImGui.getStyle().getItemInnerSpacingX()) / 3;
                boolean changed = false;
                for (int i = 0; i < 3; i++) {
                    if (i > 0) ImGui.sameLine(0, ImGui.getStyle().getItemInnerSpacingX());
                    ImGui.setNextItemWidth(part);
                    float[] buffer = {(float) parts[i]};
                    if (ImGui.dragFloat("##" + i, buffer, 0.05f, 0, 0, "%.1f")) {
                        parts[i] = buffer[0];
                        changed = true;
                    }
                }
                if (changed) result = new Vec3(parts[0], parts[1], parts[2]);
            }
            case COLOR -> {
                Color c = value instanceof Color color ? color : new Color(0xBF9EEE);
                float[] rgb = {c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f};
                if (ImGui.colorEdit3("##c", rgb, ImGuiColorEditFlags.NoInputs | ImGuiColorEditFlags.NoLabel)) {
                    result = new Color(rgb[0], rgb[1], rgb[2]);
                }
            }
            case ENTITY -> ImGui.textDisabled(id.startsWith("var-") ? "none" : "Self");
            case LIST -> ImGui.textDisabled(id.startsWith("var-") ? "empty" : "wire a list in");
            case ANY -> {
                if (value == null) ImGui.textDisabled("wire a value in");
                else result = value instanceof String ? editText(og, id, value, width) : editNumber(value, width);
            }
            case EXEC -> { }
        }
        ImGui.popID();
        return result;
    }

    private static Object editNumber(Object value, float width) {
        float[] buffer = {value instanceof Double d ? d.floatValue() : 0};
        ImGui.setNextItemWidth(width);
        return ImGui.dragFloat("##n", buffer, 0.1f, 0, 0, "%.2f") ? (double) buffer[0] : null;
    }

    private static Object editText(OpenGraph og, String id, Object value, float width) {
        String current = value instanceof String s ? s : "";
        ImString buffer = og.textBuffers.computeIfAbsent(id, k -> new ImString(current, 128));
        if (!buffer.get().equals(current) && !ImGui.isAnyItemActive()) buffer.set(current);
        ImGui.setNextItemWidth(width);
        return ImGui.inputText("##s", buffer) ? buffer.get() : null;
    }

    // ---------------------------------------------------------------- wiring, moving, deleting

    private void handleWiring(OpenGraph og) {
        ImInt start = new ImInt();
        ImInt end = new ImInt();
        if (ImNodes.isLinkCreated(start, end)) connectPins(og, start.get(), end.get());

        ImInt linkId = new ImInt();
        if (ImNodes.isLinkDestroyed(linkId)) {
            Link link = og.graph.link(linkId.get());
            if (link != null) og.graph.remove(link);
        }

        ImInt dropped = new ImInt();
        if (ImNodes.isLinkDropped(dropped, false)) {
            // a wire let go in empty space: offer nodes that fit, Unreal-style
            pendingPin = dropped.get();
            openAddMenu = true;
        }
    }

    /** Connects two pins in whichever order the user dragged them. */
    private static void connectPins(OpenGraph og, int pinA, int pinB) {
        boolean aIsOutput = isOutput(pinA);
        if (aIsOutput == isOutput(pinB)) return;
        int out = aIsOutput ? pinA : pinB;
        int in = aIsOutput ? pinB : pinA;
        Node from = og.graph.node(pinNode(out));
        Node to = og.graph.node(pinNode(in));
        if (from != null && to != null) og.graph.connect(from, pinIndex(out), to, pinIndex(in));
    }

    private static void trackPositions(OpenGraph og) {
        boolean moved = false;
        for (Node node : og.graph.nodes()) {
            if (!og.placed.contains(node.id)) continue;
            ImVec2 position = ImNodes.getNodeGridSpacePos(node.id);
            if (Math.abs(position.x - node.x) > 0.5f || Math.abs(position.y - node.y) > 0.5f) {
                node.x = position.x;
                node.y = position.y;
                moved = true;
            }
        }
        if (moved) og.graph.touch();
    }

    private static void deleteSelection(OpenGraph og) {
        int links = ImNodes.numSelectedLinks();
        if (links > 0) {
            int[] ids = new int[links];
            ImNodes.getSelectedLinks(ids);
            for (int id : ids) {
                Link link = og.graph.link(id);
                if (link != null) og.graph.remove(link);
            }
            ImNodes.clearLinkSelection();
        }
        int nodes = ImNodes.numSelectedNodes();
        if (nodes > 0) {
            int[] ids = new int[nodes];
            ImNodes.getSelectedNodes(ids);
            for (int id : ids) {
                Node node = og.graph.node(id);
                if (node != null) og.graph.remove(node);
            }
            ImNodes.clearNodeSelection();
        }
    }

    // ---------------------------------------------------------------- menus

    private void handleMenus(OpenGraph og) {
        if (!ImGui.isMouseReleased(1) || !ImNodes.isEditorHovered()) return;
        ImInt hovered = new ImInt();
        if (ImNodes.isNodeHovered(hovered)) {
            menuNode = hovered.get();
            ImGui.openPopup("node-menu");
        } else {
            pendingPin = -1;
            openAddMenu = true;
        }
    }

    private void drawNodeMenu(OpenGraph og) {
        if (!ImGui.beginPopup("node-menu")) return;
        Node node = og.graph.node(menuNode);
        if (node == null) {
            ImGui.closeCurrentPopup();
        } else {
            ImGui.textDisabled(node.displayTitle());
            if (ImGui.menuItem("Duplicate")) duplicate(og, node);
            if (ImGui.menuItem("Delete", "Del")) og.graph.remove(node);
        }
        ImGui.endPopup();
    }

    private static void duplicate(OpenGraph og, Node original) {
        Node copy = og.graph.add(original.type, original.x + 30, original.y + 30);
        copy.variable = original.variable;
        for (int i = 0; i < original.type.inputs.size(); i++) copy.setValue(i, original.value(i));
    }

    private void drawAddNodeMenu(OpenGraph og) {
        if (openAddMenu) {
            openAddMenu = false;
            ImVec2 mouse = ImGui.getMousePos();
            addMenuAt.set(mouse.x, mouse.y);
            search.set("");
            ImGui.openPopup("add-node");
        }
        if (!ImGui.beginPopup("add-node")) return;

        if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
        ImGui.setNextItemWidth(ImGui.getFontSize() * 16);
        ImGui.inputTextWithHint("##search", "Search nodes", search);
        String query = search.get().trim().toLowerCase();

        ImGui.beginChild("##nodes", ImGui.getFontSize() * 16, ImGui.getFontSize() * 22, false);
        for (NodeType.Category category : NodeType.Category.values()) {
            List<Runnable> entries = menuEntries(og, category, query);
            if (entries.isEmpty()) continue;
            if (query.isEmpty() && pendingPin < 0) {
                if (ImGui.treeNode(category.label)) {
                    entries.forEach(Runnable::run);
                    ImGui.treePop();
                }
            } else {
                ImGui.textDisabled(category.label);
                entries.forEach(Runnable::run);
            }
        }
        ImGui.endChild();
        ImGui.endPopup();
    }

    /** The clickable entries for one category, filtered by the search text and any dropped wire. */
    private List<Runnable> menuEntries(OpenGraph og, NodeType.Category category, String query) {
        List<Runnable> entries = new ArrayList<>();
        if (category == NodeType.Category.VARIABLES) {
            for (Variable v : og.graph.variables) {
                addEntry(og, entries, NodeLibrary.get(NodeLibrary.GET_VARIABLE), "Get " + v.name, v.name, query);
                addEntry(og, entries, NodeLibrary.get(NodeLibrary.SET_VARIABLE), "Set " + v.name, v.name, query);
                if (v.type == ValueType.NUMBER) addEntry(og, entries, NodeLibrary.get("variable.add"), "Add to " + v.name, v.name, query);
            }
            return entries;
        }
        for (NodeType type : NodeLibrary.all()) {
            if (type.category == category) addEntry(og, entries, type, type.title, "", query);
        }
        return entries;
    }

    private void addEntry(OpenGraph og, List<Runnable> entries, NodeType type, String label, String variable, String query) {
        if (!query.isEmpty() && !matches(label + " " + type.keywords, query)) return;
        if (pendingPin >= 0 && compatiblePin(og, type) < 0) return;
        entries.add(() -> {
            if (ImGui.selectable(label)) {
                createNode(og, type, variable);
                ImGui.closeCurrentPopup();
            }
            if (ImGui.isItemHovered() && !type.description.isEmpty()) ImGui.setTooltip(type.description);
        });
    }

    /** Every word of the search appears somewhere in the text (so "set col" finds Set Color). */
    private static boolean matches(String text, String query) {
        String lower = text.toLowerCase();
        for (String word : query.split("\\s+")) if (!lower.contains(word)) return false;
        return true;
    }

    private void createNode(OpenGraph og, NodeType type, String variable) {
        Node node = og.graph.add(type, 0, 0);
        node.variable = variable;
        og.placeAtScreen.put(node.id, new ImVec2(addMenuAt.x, addMenuAt.y));

        if (pendingPin >= 0) {
            Node other = og.graph.node(pinNode(pendingPin));
            int pin = compatiblePin(og, type);
            if (other != null && pin >= 0) {
                if (isOutput(pendingPin)) og.graph.connect(other, pinIndex(pendingPin), node, pin);
                else og.graph.connect(node, pin, other, pinIndex(pendingPin));
            }
            pendingPin = -1;
        }
    }

    /** For a dropped wire: the first pin on a node type it could plug into, or -1. */
    private int compatiblePin(OpenGraph og, NodeType type) {
        Node other = og.graph.node(pinNode(pendingPin));
        if (other == null) return -1;
        if (isOutput(pendingPin)) {
            ValueType from = other.type.outputs.get(pinIndex(pendingPin)).type();
            for (int i = 0; i < type.inputs.size(); i++) {
                if (ValueType.compatible(from, type.inputs.get(i).type())) return i;
            }
        } else {
            ValueType to = other.type.inputs.get(pinIndex(pendingPin)).type();
            for (int o = 0; o < type.outputs.size(); o++) {
                if (ValueType.compatible(type.outputs.get(o).type(), to)) return o;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- generated code view

    private void drawCode(OpenGraph og) {
        if (og.codeView == null) {
            og.codeView = new CodeEditor("", ctx.builtinNames);
            og.codeView.setReadOnly(true);
        }
        if (og.codeVersion != og.graph.version()) {
            og.codeView.replaceText(Compiler.compile(og.graph, og.name).source());
            og.codeVersion = og.graph.version();
        }
        ImGui.beginChild("##code");
        ImGui.textDisabled("Seven code made from this graph (read-only)");
        og.codeView.draw("##generated", NO_TYPING);
        ImGui.endChild();
    }

    // ---------------------------------------------------------------- pins & colors

    /** imnodes needs one number per pin: node id, then 0-499 for inputs or 500-999 for outputs. */
    private static int pinId(Node node, int index, boolean output) {
        return node.id * 1000 + (output ? 500 : 0) + index;
    }

    private static int pinNode(int pinId) {
        return pinId / 1000;
    }

    private static boolean isOutput(int pinId) {
        return pinId % 1000 >= 500;
    }

    private static int pinIndex(int pinId) {
        return pinId % 1000 % 500;
    }

    private static Color categoryColor(NodeType.Category category) {
        return switch (category) {
            case EVENTS -> new Color(0xEE6666);
            case FLOW -> new Color(0xA9ABB6);
            case ACTIONS -> new Color(0x97E1F1);
            case PHYSICS -> new Color(0x6FD8C0);
            case INPUT -> new Color(0xF286C4);
            case CHARACTERS -> new Color(0x9EB8FF);
            case HUD -> new Color(0xD0B8F4);
            case SOUND -> new Color(0xFFA8B8);
            case SCENES -> new Color(0xB5F4C6);
            case WORLD -> new Color(0xF4E7A1);
            case VALUES -> new Color(0x62E884);
            case MATH -> new Color(0xE7EE98);
            case TEXT -> new Color(0xD8D8E0);
            case VARIABLES -> Theme.ACCENT;
        };
    }

    private static float scale() {
        return ImGui.getFontSize() / 15f;
    }
}
