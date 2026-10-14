package sev7n.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiColorEditFlags;
import sev7n.math.Vec3;
import sev7n.scene.SpriteFrame;
import sev7n.ui.Theme;

import java.awt.Color;
import java.util.List;

/**
 * Compact labeled fields for the Inspector: a label column on the left,
 * the control filling the rest. Each returns true when the user changed something.
 */
final class Widgets {

    private static final Color[] AXIS_COLORS = {new Color(0xEE6666), new Color(0x62E884), new Color(0x7FA8F0)};
    private static final String[] AXIS_FORMATS = {"X %.2f", "Y %.2f", "Z %.2f"};
    private static final float[] floatBuffer = new float[1];
    private static final float[] colorBuffer = new float[3];

    private Widgets() { }

    static float labelWidth() {
        return ImGui.getFontSize() * 5.5f;
    }

    /** Draws the label and positions the cursor for a control that fills the rest of the row. */
    static void label(String label) {
        ImGui.alignTextToFramePadding();
        int hidden = label.indexOf("##"); // "VSync##game": show "VSync", keep the id unique
        ImGui.textColored(EditorStyle.pack(Theme.TEXT_MUTED), hidden >= 0 ? label.substring(0, hidden) : label);
        ImGui.sameLine(labelWidth());
        ImGui.setNextItemWidth(-1);
    }

    /** Three drag fields with red/green/blue markers, like Unity's transform rows. */
    static boolean vec3(String label, Vec3 v, float speed) {
        ImGui.pushID(label);
        label(label);
        float spacing = ImGui.getStyle().getItemInnerSpacingX();
        float width = (ImGui.getContentRegionAvailX() - spacing * 2) / 3;
        double[] values = v.toArray();
        boolean changed = false;
        for (int axis = 0; axis < 3; axis++) {
            if (axis > 0) ImGui.sameLine(0, spacing);
            ImGui.setNextItemWidth(width);
            floatBuffer[0] = (float) values[axis];
            if (ImGui.dragFloat("##" + axis, floatBuffer, speed, 0, 0, AXIS_FORMATS[axis])) {
                values[axis] = floatBuffer[0];
                changed = true;
            }
            axisMarker(AXIS_COLORS[axis]);
        }
        if (changed) v.set(values[0], values[1], values[2]);
        ImGui.popID();
        return changed;
    }

    /** A thin colored bar on the left edge of the last item. */
    private static void axisMarker(Color color) {
        ImVec2 min = ImGui.getItemRectMin();
        ImVec2 max = ImGui.getItemRectMax();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(min.x, min.y + 2, min.x + 2, max.y - 2, EditorStyle.pack(color), 1);
    }

    /** Returns the new value (or the old one if unchanged). */
    static double number(String label, double value, float speed, float min, float max) {
        label(label);
        floatBuffer[0] = (float) value;
        if (ImGui.dragFloat("##" + label, floatBuffer, speed, min, max, "%.2f")) return floatBuffer[0];
        return value;
    }

    private static final int[] intBuffer = new int[1];

    /** A whole-number drag field. Returns the new value (or the old one if unchanged). */
    static int integer(String label, int value, int min, int max) {
        label(label);
        intBuffer[0] = value;
        if (ImGui.dragInt("##" + label, intBuffer, 0.1f, min, max)) return Math.max(min, Math.min(max, intBuffer[0]));
        return value;
    }

    /** A labeled checkbox with an optional tooltip. Returns the new value. */
    static boolean checkbox(String label, boolean value, String tooltip) {
        label(label);
        boolean result = value;
        if (ImGui.checkbox("##" + label, value)) result = !value;
        if (tooltip != null && ImGui.isItemHovered()) ImGui.setTooltip(tooltip);
        return result;
    }

    static Color color(String label, Color value) {
        label(label);
        colorBuffer[0] = value.getRed() / 255f;
        colorBuffer[1] = value.getGreen() / 255f;
        colorBuffer[2] = value.getBlue() / 255f;
        if (ImGui.colorEdit3("##" + label, colorBuffer, ImGuiColorEditFlags.DisplayHex)) {
            return new Color(colorBuffer[0], colorBuffer[1], colorBuffer[2]);
        }
        return value;
    }

    /** Like color(), with a see-through amount (alpha) too. */
    static Color colorWithAlpha(String label, Color value) {
        label(label);
        float[] rgba = {value.getRed() / 255f, value.getGreen() / 255f, value.getBlue() / 255f, value.getAlpha() / 255f};
        if (ImGui.colorEdit4("##" + label, rgba, ImGuiColorEditFlags.DisplayHex | ImGuiColorEditFlags.AlphaBar | ImGuiColorEditFlags.AlphaPreviewHalf)) {
            return new Color(rgba[0], rgba[1], rgba[2], rgba[3]);
        }
        return value;
    }

    private static final imgui.type.ImString textBuffer = new imgui.type.ImString(256);

    /** A one-line text field. Returns the new text (or the old one if unchanged). */
    static String text(String label, String value, String hint) {
        label(label);
        textBuffer.set(value);
        boolean changed = ImGui.inputTextWithHint("##" + label, hint, textBuffer);
        return changed ? textBuffer.get() : value;
    }

    static String choice(String label, String current, List<String> options) {
        label(label);
        String result = current;
        if (ImGui.beginCombo("##" + label, current.isEmpty() ? "(choose)" : current)) {
            for (String option : options) {
                if (ImGui.selectable(option, option.equals(current))) result = option;
            }
            ImGui.endCombo();
        }
        return result;
    }

    /** A full-width button in the accent color, for the main action of a panel. */
    static boolean primaryButton(String label) {
        ImGui.pushStyleColor(ImGuiCol.Button, EditorStyle.pack(Theme.ACCENT));
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, EditorStyle.pack(Theme.ACCENT_HOVER));
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, EditorStyle.pack(Theme.ACCENT));
        ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(Theme.BLACK));
        boolean clicked = ImGui.button(label, -1, 0);
        ImGui.popStyleColor(4);
        return clicked;
    }

    /**
     * A picture cut into a grid (a sprite sheet or tileset), filling the panel's width,
     * with the selected cell outlined and cell numbers on hover. Returns the clicked cell, or -1.
     */
    static int gridPicture(int textureId, float aspect, int columns, int rows, int selected) {
        float width = ImGui.getContentRegionAvailX();
        float height = Math.min(width / Math.max(0.01f, aspect), width * 1.5f);
        width = height * aspect;
        ImVec2 at = ImGui.getCursorScreenPos();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(at.x, at.y, at.x + width, at.y + height, EditorStyle.pack(Theme.BLACK));
        ImGui.image(textureId, width, height);
        boolean hovered = ImGui.isItemHovered();
        boolean clicked = ImGui.isItemClicked();

        float cw = width / columns;
        float ch = height / rows;
        int lines = EditorStyle.pack(EditorStyle.withAlpha(Theme.TEXT, 50));
        for (int c = 1; c < columns; c++) draw.addLine(at.x + c * cw, at.y, at.x + c * cw, at.y + height, lines);
        for (int r = 1; r < rows; r++) draw.addLine(at.x, at.y + r * ch, at.x + width, at.y + r * ch, lines);
        if (selected >= 0 && selected < columns * rows) {
            float x = at.x + (selected % columns) * cw;
            float y = at.y + (float) (selected / columns) * ch;
            draw.addRect(x, y, x + cw, y + ch, EditorStyle.pack(Theme.ACCENT), 0, 0, 2);
        }
        if (!hovered) return -1;
        ImVec2 mouse = ImGui.getMousePos();
        int column = Math.min(columns - 1, (int) ((mouse.x - at.x) / cw));
        int row = Math.min(rows - 1, (int) ((mouse.y - at.y) / ch));
        int cell = row * columns + column;
        float x = at.x + column * cw;
        float y = at.y + row * ch;
        draw.addRect(x, y, x + cw, y + ch, EditorStyle.pack(Theme.ACCENT_HOVER), 0, 0, 1);
        ImGui.setTooltip("#" + cell);
        return clicked ? cell : -1;
    }

    /**
     * A sprite sheet with its found frames outlined and numbered, the selected one in the
     * accent color. Returns the clicked frame (the smallest one under the mouse), or -1.
     */
    static int framesPicture(int textureId, int sheetWidth, int sheetHeight, List<SpriteFrame> frames, int selected) {
        float aspect = (float) sheetWidth / sheetHeight;
        float width = ImGui.getContentRegionAvailX();
        float height = Math.min(width / aspect, width * 1.5f);
        width = height * aspect;
        float scale = width / sheetWidth;
        ImVec2 at = ImGui.getCursorScreenPos();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(at.x, at.y, at.x + width, at.y + height, EditorStyle.pack(Theme.BLACK));
        ImGui.image(textureId, width, height);
        boolean hovered = ImGui.isItemHovered();
        boolean clicked = ImGui.isItemClicked();
        ImVec2 mouse = ImGui.getMousePos();

        int under = -1;
        long underArea = Long.MAX_VALUE;
        for (int i = 0; i < frames.size(); i++) {
            SpriteFrame f = frames.get(i);
            float x0 = at.x + f.x * scale, y0 = at.y + f.y * scale;
            float x1 = x0 + f.width * scale, y1 = y0 + f.height * scale;
            boolean inside = hovered && f.contains((mouse.x - at.x) / scale, (mouse.y - at.y) / scale);
            if (inside && (long) f.width * f.height < underArea) {
                under = i;
                underArea = (long) f.width * f.height;
            }
            int color = EditorStyle.pack(i == selected ? Theme.ACCENT : EditorStyle.withAlpha(Theme.TEXT, 90));
            outline(draw, f, at.x, at.y, scale, color, i == selected ? 2 : 1);
            draw.addText(x0 + 2, y0 + 1, color, String.valueOf(i));
        }
        if (under >= 0) {
            outline(draw, frames.get(under), at.x, at.y, scale, EditorStyle.pack(Theme.ACCENT_HOVER), 1);
            ImGui.setTooltip("Frame " + under);
        }
        return clicked ? under : -1;
    }

    /** A frame's edge (its polygon, or its rectangle) drawn over a sheet shown at 'scale' from (originX, originY). */
    static void outline(ImDrawList draw, SpriteFrame f, float originX, float originY, float scale, int color, float thickness) {
        if (f.polygon == null) {
            draw.addRect(originX + f.x * scale, originY + f.y * scale, originX + f.right() * scale, originY + f.bottom() * scale,
                    color, 0, 0, thickness);
            return;
        }
        int n = f.polygon.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            draw.addLine(originX + f.polygon[i * 2] * scale, originY + f.polygon[i * 2 + 1] * scale,
                    originX + f.polygon[j * 2] * scale, originY + f.polygon[j * 2 + 1] * scale, color, thickness);
        }
    }

    /** A dim hint line. */
    static void hint(String text) {
        ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(Theme.TEXT_FAINT));
        ImGui.textWrapped(text);
        ImGui.popStyleColor();
    }
}
