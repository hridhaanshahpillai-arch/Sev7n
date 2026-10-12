package sev7n.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiButtonFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import sev7n.render.SpriteAtlas;
import sev7n.render.SpriteSlicer;
import sev7n.render.Texture;
import sev7n.scene.Entity;
import sev7n.scene.SpriteFrame;
import sev7n.scene.SpriteRenderer;
import sev7n.ui.Theme;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Cuts a sprite sheet into exact frames.
 *
 *   Select (V)     click a frame; drag it to move, drag its corners or edges to resize
 *   Rectangle (R)  drag to draw a new frame
 *   Polygon (P)    click corner points; straight edges at any angle (hold Shift for
 *                  straight or 45° diagonal edges); click the first point or press Enter to finish
 *   Pivot (O)      click where the selected frame stands (frames are lined up by it)
 *
 *   wheel zooms · right- or middle-drag pans · arrows nudge · Delete removes · Ctrl+Z undoes
 *
 * Frames belong to the sprite being edited and are saved with the scene.
 */
final class SpriteEditorPanel {

    static final String TITLE = "Sprite Editor";

    private enum Tool {
        SELECT("Select", "V", ImGuiKey.V, "Click a frame; drag to move, drag corners or edges to resize"),
        RECTANGLE("Rectangle", "R", ImGuiKey.R, "Drag to draw a rectangular frame"),
        POLYGON("Polygon", "P", ImGuiKey.P, "Click corner points (any angle; Shift = straight or 45°). Click the first point or press Enter to finish"),
        PIVOT("Pivot", "O", ImGuiKey.O, "Click where the selected frame stands; frames are lined up by their pivots");

        final String label;
        final String key;
        final int keyCode;
        final String help;

        Tool(String label, String key, int keyCode, String help) {
            this.label = label;
            this.key = key;
            this.keyCode = keyCode;
            this.help = help;
        }
    }

    private enum Drag { NONE, MOVE, RESIZE, PIVOT, CREATE }

    private static final int MAX_UNDO = 100;
    private static final float HANDLE = 5;          // half-size of resize handles, in screen pixels

    private final EditorContext ctx;
    private final ImBoolean open = new ImBoolean(false);
    private long entityId = -1;
    private boolean focusRequested;
    private boolean fitRequested;

    private Tool tool = Tool.SELECT;
    private int selected = -1;
    private float zoom = 1;
    private float panX;
    private float panY;
    private final Deque<List<SpriteFrame>> undo = new ArrayDeque<>();

    // dragging
    private Drag drag = Drag.NONE;
    private int handle;                 // 0..7: corners and edges, clockwise from the top left
    private SpriteFrame dragStart;
    private int dragFromX;
    private int dragFromY;
    private int mouseCornerX;           // the pixel corner under the mouse
    private int mouseCornerY;
    private final List<Integer> polygon = new ArrayList<>();

    // grid slicing
    private boolean gridByCount = true;
    private final int[] gridCount = {4, 4};
    private final int[] gridCell = {32, 32};
    private final int[] gridOffset = {0, 0};
    private final int[] gridGap = {0, 0};
    private boolean gridSkipEmpty = true;

    // animation preview
    private boolean previewPlaying = true;
    private final float[] previewFps = {10};
    private double previewTime;

    SpriteEditorPanel(EditorContext ctx) {
        this.ctx = ctx;
    }

    void open(Entity entity) {
        if (entity == null || !entity.has(SpriteRenderer.class)) {
            ctx.log.warning("Select an object with a Sprite Renderer first, then open the Sprite Editor");
            return;
        }
        if (entity.id() != entityId) {
            undo.clear();
            selected = -1;
            fitRequested = true;
        }
        entityId = entity.id();
        polygon.clear();
        open.set(true);
        focusRequested = true;
    }

    private SpriteRenderer target() {
        Entity entity = entityId < 0 ? null : ctx.scene().findById(entityId);
        return entity == null ? null : entity.get(SpriteRenderer.class);
    }

    void draw() {
        if (!open.get()) return;
        float scale = ImGui.getFontSize() / 15f;
        ImGui.setNextWindowSize(1040 * scale, 660 * scale, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowPos(ImGui.getMainViewport().getCenterX(), ImGui.getMainViewport().getCenterY(),
                ImGuiCond.FirstUseEver, 0.5f, 0.5f);
        if (focusRequested) {
            ImGui.setNextWindowFocus();
            focusRequested = false;
        }
        if (!ImGui.begin(TITLE, open, ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            ImGui.end();
            return;
        }
        SpriteRenderer sprite = target();
        Texture sheet = sprite == null ? null : ctx.texture(sprite.texture, true, sprite.removeBackground);
        if (sprite == null || sheet == null) {
            Widgets.hint(sprite == null
                    ? "Select an object with a Sprite Renderer, then click Open Sprite Editor in the Inspector."
                    : "This sprite has no picture yet. Pick one in the Inspector.");
            ImGui.end();
            return;
        }
        boolean editable = !ctx.playing() || ctx.live();

        drawToolbar(sprite, editable);
        float sidebar = 270 * scale;
        ImGui.beginChild("##canvas", ImGui.getContentRegionAvailX() - sidebar - ImGui.getStyle().getItemSpacingX(), 0, false,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        drawCanvas(sprite, sheet, editable);
        ImGui.endChild();
        ImGui.sameLine();
        ImGui.beginChild("##side", 0, 0, true);
        drawSidebar(sprite, editable);
        ImGui.endChild();
        drawGridPopup(sprite);
        if (editable && ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows) && !ImGui.getIO().getWantTextInput()) {
            handleKeys(sprite);
        }
        ImGui.end();
    }

    // ---------------------------------------------------------------- toolbar

    private void drawToolbar(SpriteRenderer sprite, boolean editable) {
        for (Tool t : Tool.values()) {
            boolean active = t == tool;
            if (active) accentButtonColors();
            if (ImGui.button(t.label + "##tool")) setTool(t);
            if (active) ImGui.popStyleColor(3);
            if (ImGui.isItemHovered()) ImGui.setTooltip(t.help + " (" + t.key + ")");
            ImGui.sameLine();
        }
        ImGui.sameLine(0, 16);
        if (!editable) ImGui.beginDisabled();
        if (ImGui.button("Find frames")) {
            remember(sprite);
            if (findFrames(ctx, sprite)) selected = -1;
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Find each separate picture on the sheet automatically");
        ImGui.sameLine();
        if (ImGui.button("Grid...")) ImGui.openPopup("grid-slice");
        if (ImGui.isItemHovered()) ImGui.setTooltip("Cut the sheet into equal cells");
        ImGui.sameLine();
        if (ImGui.button("Undo") && !undo.isEmpty()) undo(sprite);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Ctrl+Z");
        if (!editable) ImGui.endDisabled();
        ImGui.sameLine(0, 16);
        if (ImGui.button("-")) zoomAround(zoom / 1.5f, Float.NaN, Float.NaN);
        ImGui.sameLine();
        if (ImGui.button(Math.round(zoom * 100) + "%")) zoomAround(1, Float.NaN, Float.NaN);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Actual size");
        ImGui.sameLine();
        if (ImGui.button("+")) zoomAround(zoom * 1.5f, Float.NaN, Float.NaN);
        ImGui.sameLine();
        if (ImGui.button("Fit")) fitRequested = true;
        if (!editable) {
            ImGui.sameLine(0, 16);
            ImGui.textColored(EditorStyle.pack(Theme.ERROR), "Stop playing (or turn on Live Mode) to edit frames");
        }
    }

    private static void accentButtonColors() {
        ImGui.pushStyleColor(ImGuiCol.Button, EditorStyle.pack(Theme.ACCENT));
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, EditorStyle.pack(Theme.ACCENT_HOVER));
        ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(Theme.BLACK));
    }

    private void setTool(Tool t) {
        tool = t;
        polygon.clear();
        drag = Drag.NONE;
    }

    // ---------------------------------------------------------------- canvas

    private float canvasX, canvasY, canvasWidth, canvasHeight;

    private void drawCanvas(SpriteRenderer sprite, Texture sheet, boolean editable) {
        ImVec2 origin = ImGui.getCursorScreenPos();
        canvasX = origin.x;
        canvasY = origin.y;
        canvasWidth = Math.max(10, ImGui.getContentRegionAvailX());
        canvasHeight = Math.max(10, ImGui.getContentRegionAvailY());
        ImGui.invisibleButton("##area", canvasWidth, canvasHeight,
                ImGuiButtonFlags.MouseButtonLeft | ImGuiButtonFlags.MouseButtonRight | ImGuiButtonFlags.MouseButtonMiddle);
        boolean hovered = ImGui.isItemHovered();

        if (fitRequested) {
            float fit = Math.min(canvasWidth / sheet.width, canvasHeight / sheet.height) * 0.92f;
            zoom = Math.max(0.05f, Math.min(64, fit));
            panX = (canvasWidth - sheet.width * zoom) / 2;
            panY = (canvasHeight - sheet.height * zoom) / 2;
            fitRequested = false;
        }

        ImVec2 mouse = ImGui.getMousePos();
        float sheetX = (mouse.x - canvasX - panX) / zoom;
        float sheetY = (mouse.y - canvasY - panY) / zoom;
        mouseCornerX = clamp(Math.round(sheetX), 0, sheet.width);
        mouseCornerY = clamp(Math.round(sheetY), 0, sheet.height);

        ImDrawList draw = ImGui.getWindowDrawList();
        draw.pushClipRect(canvasX, canvasY, canvasX + canvasWidth, canvasY + canvasHeight, true);
        draw.addRectFilled(canvasX, canvasY, canvasX + canvasWidth, canvasY + canvasHeight, EditorStyle.pack(Theme.BLACK));
        float left = canvasX + panX;
        float top = canvasY + panY;
        drawCheckerboard(draw, left, top, sheet.width * zoom, sheet.height * zoom);
        draw.addImage(sheet.id, left, top, left + sheet.width * zoom, top + sheet.height * zoom);
        if (zoom >= 8) drawPixelGrid(draw, left, top, sheet);
        drawFrames(draw, sprite, left, top);
        drawToolPreview(draw, left, top);
        String status = (hovered ? "x " + (int) Math.floor(sheetX) + "  y " + (int) Math.floor(sheetY) + "   " : "")
                + sprite.frames.size() + " frames   " + sheet.width + " × " + sheet.height + " px";
        draw.addText(canvasX + 8, canvasY + canvasHeight - ImGui.getTextLineHeight() - 6, EditorStyle.pack(Theme.TEXT_FAINT), status);
        draw.popClipRect();

        handleNavigation(hovered);
        if (editable) handleMouse(sprite, hovered, sheetX, sheetY);
    }

    private void drawCheckerboard(ImDrawList draw, float x, float y, float width, float height) {
        float size = 10;
        int dark = EditorStyle.pack(Theme.SURFACE);
        int light = EditorStyle.pack(Theme.SURFACE_HI);
        draw.addRectFilled(x, y, x + width, y + height, dark);
        float startX = Math.max(x, canvasX);
        float startY = Math.max(y, canvasY);
        float endX = Math.min(x + width, canvasX + canvasWidth);
        float endY = Math.min(y + height, canvasY + canvasHeight);
        for (float cy = y + (float) Math.floor((startY - y) / size) * size; cy < endY; cy += size) {
            for (float cx = x + (float) Math.floor((startX - x) / size) * size; cx < endX; cx += size) {
                if ((Math.round((cx - x) / size) + Math.round((cy - y) / size)) % 2 == 0) continue;
                draw.addRectFilled(cx, cy, Math.min(cx + size, x + width), Math.min(cy + size, y + height), light);
            }
        }
    }

    private void drawPixelGrid(ImDrawList draw, float left, float top, Texture sheet) {
        int color = EditorStyle.pack(EditorStyle.withAlpha(Theme.TEXT, 28));
        int firstX = Math.max(0, (int) ((canvasX - left) / zoom));
        int lastX = Math.min(sheet.width, (int) ((canvasX + canvasWidth - left) / zoom) + 1);
        int firstY = Math.max(0, (int) ((canvasY - top) / zoom));
        int lastY = Math.min(sheet.height, (int) ((canvasY + canvasHeight - top) / zoom) + 1);
        for (int px = firstX; px <= lastX; px++) draw.addLine(left + px * zoom, top + firstY * zoom, left + px * zoom, top + lastY * zoom, color);
        for (int py = firstY; py <= lastY; py++) draw.addLine(left + firstX * zoom, top + py * zoom, left + lastX * zoom, top + py * zoom, color);
    }

    private void drawFrames(ImDrawList draw, SpriteRenderer sprite, float left, float top) {
        for (int i = 0; i < sprite.frames.size(); i++) {
            SpriteFrame f = sprite.frames.get(i);
            boolean isSelected = i == selected;
            int color = EditorStyle.pack(isSelected ? Theme.ACCENT : EditorStyle.withAlpha(Theme.TEXT, 120));
            Widgets.outline(draw, f, left, top, zoom, color, isSelected ? 2 : 1);
            draw.addText(left + f.x * zoom + 3, top + f.y * zoom + 2, color, String.valueOf(i));
        }
        if (selected >= 0 && selected < sprite.frames.size()) {
            SpriteFrame f = sprite.frames.get(selected);
            float px = left + f.pivotX * zoom;
            float py = top + f.pivotY * zoom;
            draw.addCircleFilled(px, py, 5, EditorStyle.pack(Theme.BLACK));
            draw.addCircle(px, py, 5, EditorStyle.pack(Theme.SUCCESS), 0, 2);
            if (tool == Tool.SELECT && f.polygon == null) {
                for (int h = 0; h < 8; h++) {
                    float[] at = handlePosition(f, h, left, top);
                    draw.addRectFilled(at[0] - HANDLE, at[1] - HANDLE, at[0] + HANDLE, at[1] + HANDLE, EditorStyle.pack(Theme.ACCENT));
                    draw.addRect(at[0] - HANDLE, at[1] - HANDLE, at[0] + HANDLE, at[1] + HANDLE, EditorStyle.pack(Theme.BLACK));
                }
            }
        }
    }

    /** What the current tool is about to make: the rectangle being dragged, or the polygon being clicked. */
    private void drawToolPreview(ImDrawList draw, float left, float top) {
        int accent = EditorStyle.pack(Theme.ACCENT_HOVER);
        if (drag == Drag.CREATE) {
            draw.addRect(left + Math.min(dragFromX, mouseCornerX) * zoom, top + Math.min(dragFromY, mouseCornerY) * zoom,
                    left + Math.max(dragFromX, mouseCornerX) * zoom, top + Math.max(dragFromY, mouseCornerY) * zoom, accent, 0, 0, 2);
        }
        if (tool == Tool.POLYGON && !polygon.isEmpty()) {
            int[] next = polygonCursor();
            for (int i = 0; i + 3 < polygon.size(); i += 2) {
                draw.addLine(left + polygon.get(i) * zoom, top + polygon.get(i + 1) * zoom,
                        left + polygon.get(i + 2) * zoom, top + polygon.get(i + 3) * zoom, accent, 2);
            }
            int last = polygon.size() - 2;
            draw.addLine(left + polygon.get(last) * zoom, top + polygon.get(last + 1) * zoom,
                    left + next[0] * zoom, top + next[1] * zoom, EditorStyle.pack(EditorStyle.withAlpha(Theme.ACCENT_HOVER, 150)), 1.5f);
            for (int i = 0; i < polygon.size(); i += 2) {
                draw.addCircleFilled(left + polygon.get(i) * zoom, top + polygon.get(i + 1) * zoom, i == 0 ? 6 : 4, accent);
            }
        }
    }

    /** Where the next polygon point would go: the pixel corner under the mouse, snapped to 0/45/90° with Shift. */
    private int[] polygonCursor() {
        int x = mouseCornerX;
        int y = mouseCornerY;
        if (ImGui.getIO().getKeyShift() && polygon.size() >= 2) {
            int lastX = polygon.get(polygon.size() - 2);
            int lastY = polygon.get(polygon.size() - 1);
            int dx = x - lastX;
            int dy = y - lastY;
            if (Math.abs(dx) > 2 * Math.abs(dy)) dy = 0;
            else if (Math.abs(dy) > 2 * Math.abs(dx)) dx = 0;
            else {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                dx = Integer.signum(dx) * d;
                dy = Integer.signum(dy) * d;
            }
            x = lastX + dx;
            y = lastY + dy;
        }
        return new int[] {x, y};
    }

    // ---------------------------------------------------------------- mouse

    private void handleNavigation(boolean hovered) {
        ImGuiIO io = ImGui.getIO();
        if (hovered && io.getMouseWheel() != 0) {
            zoomAround(zoom * (float) Math.pow(1.2, io.getMouseWheel()), ImGui.getMousePos().x, ImGui.getMousePos().y);
        }
        boolean panning = (ImGui.isMouseDown(1) || ImGui.isMouseDown(2)) && (hovered || ImGui.isItemActive());
        if (panning) {
            panX += io.getMouseDeltaX();
            panY += io.getMouseDeltaY();
        }
    }

    /** Zooms keeping the sheet point under (screenX, screenY) in place (the canvas middle if NaN). */
    private void zoomAround(float newZoom, float screenX, float screenY) {
        newZoom = Math.max(0.05f, Math.min(64, newZoom));
        if (Float.isNaN(screenX)) {
            screenX = canvasX + canvasWidth / 2;
            screenY = canvasY + canvasHeight / 2;
        }
        float sheetX = (screenX - canvasX - panX) / zoom;
        float sheetY = (screenY - canvasY - panY) / zoom;
        zoom = newZoom;
        panX = screenX - canvasX - sheetX * zoom;
        panY = screenY - canvasY - sheetY * zoom;
    }

    private void handleMouse(SpriteRenderer sprite, boolean hovered, float sheetX, float sheetY) {
        List<SpriteFrame> frames = sprite.frames;
        if (hovered && ImGui.isMouseClicked(0)) {
            switch (tool) {
                case SELECT -> startSelectDrag(sprite, sheetX, sheetY);
                case RECTANGLE -> {
                    drag = Drag.CREATE;
                    dragFromX = mouseCornerX;
                    dragFromY = mouseCornerY;
                }
                case POLYGON -> addPolygonPoint(sprite);
                case PIVOT -> {
                    if (selected >= 0 && selected < frames.size()) {
                        remember(sprite);
                        frames.get(selected).pivotX = mouseCornerX;
                        frames.get(selected).pivotY = mouseCornerY;
                        drag = Drag.PIVOT;
                        ctx.markDirty();
                    }
                }
            }
        }
        if (drag != Drag.NONE && ImGui.isMouseDown(0)) dragTo(frames);
        if (drag != Drag.NONE && !ImGui.isMouseDown(0)) finishDrag(sprite);
    }

    private void startSelectDrag(SpriteRenderer sprite, float sheetX, float sheetY) {
        List<SpriteFrame> frames = sprite.frames;
        float left = canvasX + panX;
        float top = canvasY + panY;
        ImVec2 mouse = ImGui.getMousePos();
        if (selected >= 0 && selected < frames.size()) {
            SpriteFrame f = frames.get(selected);
            // the pivot dot, then the resize handles, take priority over picking another frame
            if (Math.hypot(mouse.x - (left + f.pivotX * zoom), mouse.y - (top + f.pivotY * zoom)) <= 7) {
                remember(sprite);
                drag = Drag.PIVOT;
                return;
            }
            if (f.polygon == null) {
                for (int h = 0; h < 8; h++) {
                    float[] at = handlePosition(f, h, left, top);
                    if (Math.abs(mouse.x - at[0]) <= HANDLE + 2 && Math.abs(mouse.y - at[1]) <= HANDLE + 2) {
                        remember(sprite);
                        drag = Drag.RESIZE;
                        handle = h;
                        dragStart = f.copy();
                        return;
                    }
                }
            }
        }
        selected = frameAt(frames, sheetX, sheetY);
        if (selected >= 0) {
            remember(sprite);
            drag = Drag.MOVE;
            dragStart = frames.get(selected).copy();
            dragFromX = mouseCornerX;
            dragFromY = mouseCornerY;
        }
    }

    private void dragTo(List<SpriteFrame> frames) {
        if (drag == Drag.CREATE || selected < 0 || selected >= frames.size()) return;
        SpriteFrame f = frames.get(selected);
        switch (drag) {
            case MOVE -> {
                SpriteFrame moved = dragStart.copy();
                moved.move(mouseCornerX - dragFromX, mouseCornerY - dragFromY);
                frames.set(selected, moved);
            }
            case RESIZE -> frames.set(selected, resized(dragStart, handle, mouseCornerX, mouseCornerY));
            case PIVOT -> {
                f.pivotX = mouseCornerX;
                f.pivotY = mouseCornerY;
            }
            default -> { }
        }
    }

    private void finishDrag(SpriteRenderer sprite) {
        if (drag == Drag.CREATE) {
            int x0 = Math.min(dragFromX, mouseCornerX), x1 = Math.max(dragFromX, mouseCornerX);
            int y0 = Math.min(dragFromY, mouseCornerY), y1 = Math.max(dragFromY, mouseCornerY);
            if (x1 - x0 >= 1 && y1 - y0 >= 1) {
                remember(sprite);
                sprite.frames.add(SpriteFrame.rectangle(x0, y0, x1 - x0, y1 - y0));
                selected = sprite.frames.size() - 1;
            }
        }
        drag = Drag.NONE;
        ctx.markDirty();
    }

    private void addPolygonPoint(SpriteRenderer sprite) {
        int[] point = polygonCursor();
        if (polygon.size() >= 6) {
            float firstX = canvasX + panX + polygon.get(0) * zoom;
            float firstY = canvasY + panY + polygon.get(1) * zoom;
            if (Math.hypot(ImGui.getMousePos().x - firstX, ImGui.getMousePos().y - firstY) <= 8 || ImGui.isMouseDoubleClicked(0)) {
                finishPolygon(sprite);
                return;
            }
        }
        int size = polygon.size();
        if (size >= 2 && polygon.get(size - 2) == point[0] && polygon.get(size - 1) == point[1]) return; // same spot twice
        polygon.add(point[0]);
        polygon.add(point[1]);
    }

    private void finishPolygon(SpriteRenderer sprite) {
        if (polygon.size() < 6) return;
        remember(sprite);
        int[] points = polygon.stream().mapToInt(Integer::intValue).toArray();
        sprite.frames.add(SpriteFrame.polygon(points));
        selected = sprite.frames.size() - 1;
        polygon.clear();
        ctx.markDirty();
    }

    /** The smallest frame under a sheet point, or -1. */
    private static int frameAt(List<SpriteFrame> frames, float x, float y) {
        int best = -1;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < frames.size(); i++) {
            SpriteFrame f = frames.get(i);
            long area = (long) f.width * f.height;
            if (f.contains(x, y) && area < bestArea) {
                best = i;
                bestArea = area;
            }
        }
        return best;
    }

    /** Handle positions in screen pixels: 0 top-left, 1 top, 2 top-right, 3 right, 4 bottom-right, 5 bottom, 6 bottom-left, 7 left. */
    private float[] handlePosition(SpriteFrame f, int h, float left, float top) {
        float x0 = left + f.x * zoom, x1 = left + f.right() * zoom, xm = (x0 + x1) / 2;
        float y0 = top + f.y * zoom, y1 = top + f.bottom() * zoom, ym = (y0 + y1) / 2;
        return switch (h) {
            case 0 -> new float[] {x0, y0};
            case 1 -> new float[] {xm, y0};
            case 2 -> new float[] {x1, y0};
            case 3 -> new float[] {x1, ym};
            case 4 -> new float[] {x1, y1};
            case 5 -> new float[] {xm, y1};
            case 6 -> new float[] {x0, y1};
            default -> new float[] {x0, ym};
        };
    }

    /** A rectangle frame with one corner or edge dragged to a pixel corner. A bottom-middle pivot stays at the bottom middle. */
    private static SpriteFrame resized(SpriteFrame start, int h, int cornerX, int cornerY) {
        int x0 = start.x, y0 = start.y, x1 = start.right(), y1 = start.bottom();
        if (h == 0 || h == 6 || h == 7) x0 = Math.min(cornerX, x1 - 1);
        if (h == 2 || h == 3 || h == 4) x1 = Math.max(cornerX, x0 + 1);
        if (h == 0 || h == 1 || h == 2) y0 = Math.min(cornerY, y1 - 1);
        if (h == 4 || h == 5 || h == 6) y1 = Math.max(cornerY, y0 + 1);
        SpriteFrame result = new SpriteFrame(x0, y0, x1 - x0, y1 - y0, start.pivotX, start.pivotY, start.auto);
        boolean pivotAtBottomMiddle = start.pivotX == start.x + start.width / 2 && start.pivotY == start.bottom();
        if (pivotAtBottomMiddle) {
            result.pivotX = x0 + (x1 - x0) / 2;
            result.pivotY = y1;
        }
        return result;
    }

    // ---------------------------------------------------------------- keys

    private void handleKeys(SpriteRenderer sprite) {
        ImGuiIO io = ImGui.getIO();
        if (io.getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.Z, false) && !undo.isEmpty()) undo(sprite);
        if (io.getKeyCtrl()) return;
        for (Tool t : Tool.values()) {
            if (ImGui.isKeyPressed(t.keyCode, false)) setTool(t);
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            if (!polygon.isEmpty()) polygon.clear();
            else selected = -1;
        }
        if (ImGui.isKeyPressed(ImGuiKey.Enter, false) && tool == Tool.POLYGON) finishPolygon(sprite);
        if (ImGui.isKeyPressed(ImGuiKey.Backspace, false) && polygon.size() >= 2) {
            polygon.remove(polygon.size() - 1);
            polygon.remove(polygon.size() - 1);
        }
        boolean hasSelection = selected >= 0 && selected < sprite.frames.size();
        if (hasSelection && ImGui.isKeyPressed(ImGuiKey.Delete, false)) deleteSelected(sprite);
        if (hasSelection) {
            int step = io.getKeyShift() ? 10 : 1;
            int dx = (ImGui.isKeyPressed(ImGuiKey.RightArrow) ? step : 0) - (ImGui.isKeyPressed(ImGuiKey.LeftArrow) ? step : 0);
            int dy = (ImGui.isKeyPressed(ImGuiKey.DownArrow) ? step : 0) - (ImGui.isKeyPressed(ImGuiKey.UpArrow) ? step : 0);
            if (dx != 0 || dy != 0) {
                remember(sprite);
                sprite.frames.get(selected).move(dx, dy);
                ctx.markDirty();
            }
        }
    }

    // ---------------------------------------------------------------- sidebar

    private void drawSidebar(SpriteRenderer sprite, boolean editable) {
        ImGui.textUnformatted(sprite.texture.substring(sprite.texture.lastIndexOf('/') + 1));
        if (!editable) ImGui.beginDisabled();
        boolean removeBackground = Widgets.checkbox("Remove bg", sprite.removeBackground,
                "Make the background color see-through (the top-left pixel's color)");
        if (removeBackground != sprite.removeBackground) {
            sprite.removeBackground = removeBackground;
            ctx.markDirty();
        }
        ImGui.separator();

        ImGui.textDisabled("FRAMES (" + sprite.frames.size() + ")");
        ImGui.beginChild("##frames", 0, 150 * ImGui.getFontSize() / 15f, true);
        for (int i = 0; i < sprite.frames.size(); i++) {
            SpriteFrame f = sprite.frames.get(i);
            String kind = f.polygon != null ? "polygon" : f.auto ? "found" : "rectangle";
            if (ImGui.selectable(i + "   " + f.width + " × " + f.height + "   " + kind + "##f" + i, i == selected)) selected = i;
        }
        if (sprite.frames.isEmpty()) Widgets.hint("No frames yet. Draw one, use Find frames, or Grid...");
        ImGui.endChild();

        if (selected >= 0 && selected < sprite.frames.size()) drawSelectedFrame(sprite);
        if (!sprite.frames.isEmpty() && ImGui.button("Clear all frames", -1, 0)) {
            remember(sprite);
            sprite.frames.clear();
            selected = -1;
            ctx.markDirty();
        }
        if (!editable) ImGui.endDisabled();

        ImGui.separator();
        drawPreview(sprite);
        ImGui.separator();
        Widgets.hint(tool.help + ".  Wheel zooms, right-drag pans, Ctrl+Z undoes.");
    }

    private void drawSelectedFrame(SpriteRenderer sprite) {
        SpriteFrame f = sprite.frames.get(selected);
        ImGui.textDisabled("FRAME " + selected);
        int[] values = {f.x, f.y, f.width, f.height, f.pivotX, f.pivotY};
        String[] labels = {"X", "Y", "Width", "Height", "Pivot X", "Pivot Y"};
        for (int i = 0; i < values.length; i++) {
            if (f.polygon != null && (i == 2 || i == 3)) continue; // a polygon's size comes from its points
            int value = Widgets.integer(labels[i], values[i], i == 2 || i == 3 ? 1 : -10000, 10000);
            if (value == values[i]) continue;
            remember(sprite);
            switch (i) {
                case 0 -> f.move(value - f.x, 0);
                case 1 -> f.move(0, value - f.y);
                case 2 -> f.width = value;
                case 3 -> f.height = value;
                case 4 -> f.pivotX = value;
                default -> f.pivotY = value;
            }
            ctx.markDirty();
        }
        boolean first = selected == 0;
        boolean last = selected == sprite.frames.size() - 1;
        if (first) ImGui.beginDisabled();
        if (ImGui.button("Earlier")) swap(sprite, selected - 1);
        if (first) ImGui.endDisabled();
        ImGui.sameLine();
        if (last) ImGui.beginDisabled();
        if (ImGui.button("Later")) swap(sprite, selected + 1);
        ImGui.sameLine();
        if (ImGui.button("Join next")) {
            remember(sprite);
            sprite.frames.set(selected, f.joined(sprite.frames.get(selected + 1)));
            sprite.frames.remove(selected + 1);
            ctx.markDirty();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("For a frame that came out in two pieces");
        if (last) ImGui.endDisabled();
        if (ImGui.button("Trim")) trim(sprite, f);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Shrink the rectangle to the pixels inside it");
        ImGui.sameLine();
        if (ImGui.button("Duplicate")) {
            remember(sprite);
            sprite.frames.add(selected + 1, f.copy());
            selected++;
            ctx.markDirty();
        }
        ImGui.sameLine();
        if (ImGui.button("Delete")) deleteSelected(sprite);
    }

    private void swap(SpriteRenderer sprite, int other) {
        remember(sprite);
        SpriteFrame moving = sprite.frames.remove(selected);
        sprite.frames.add(other, moving);
        selected = other;
        ctx.markDirty();
    }

    private void deleteSelected(SpriteRenderer sprite) {
        remember(sprite);
        sprite.frames.remove(selected);
        selected = Math.min(selected, sprite.frames.size() - 1);
        sprite.frame = Math.min(sprite.frame, Math.max(0, sprite.frameCount() - 1));
        ctx.markDirty();
    }

    /** Shrinks a frame's rectangle to the visible pixels inside it (its polygon, if it has one). */
    private void trim(SpriteRenderer sprite, SpriteFrame f) {
        try {
            BufferedImage picture = ctx.renderer.assets().picture(sprite.texture, sprite.removeBackground);
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            for (int y = Math.max(0, f.y); y < Math.min(picture.getHeight(), f.bottom()); y++) {
                for (int x = Math.max(0, f.x); x < Math.min(picture.getWidth(), f.right()); x++) {
                    if ((picture.getRGB(x, y) >>> 24) <= 16 || !f.contains(x + 0.5, y + 0.5)) continue;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
            if (maxX < 0) return; // nothing visible
            remember(sprite);
            boolean pivotAtBottomMiddle = f.pivotX == f.x + f.width / 2 && f.pivotY == f.bottom();
            f.x = minX;
            f.y = minY;
            f.width = maxX - minX + 1;
            f.height = maxY - minY + 1;
            if (pivotAtBottomMiddle) {
                f.pivotX = f.x + f.width / 2;
                f.pivotY = f.bottom();
            }
            ctx.markDirty();
        } catch (IOException e) {
            ctx.log.warning("Couldn't read " + sprite.texture + ": " + e.getMessage());
        }
    }

    /** The frames playing in order, exactly as the game will show them. */
    private void drawPreview(SpriteRenderer sprite) {
        ImGui.textDisabled("PREVIEW");
        if (sprite.frames.isEmpty()) {
            Widgets.hint("Frames you make play here.");
            return;
        }
        if (ImGui.button(previewPlaying ? "Pause" : "Play")) previewPlaying = !previewPlaying;
        ImGui.sameLine();
        ImGui.setNextItemWidth(-1);
        ImGui.sliderFloat("##fps", previewFps, 1, 30, "%.0f frames / sec");
        if (previewPlaying) previewTime += ImGui.getIO().getDeltaTime();
        int count = sprite.frames.size();
        int index = previewPlaying ? (int) (previewTime * previewFps[0]) % count : Math.max(0, selected) % count;

        SpriteAtlas atlas = ctx.renderer.assets().spriteAtlas(sprite.texture, true, sprite.removeBackground, sprite.frames);
        if (atlas == null) return;
        float box = Math.min(ImGui.getContentRegionAvailX(), 180 * ImGui.getFontSize() / 15f);
        ImVec2 at = ImGui.getCursorScreenPos();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(at.x, at.y, at.x + box, at.y + box, EditorStyle.pack(Theme.EDITOR), 4);

        // the same lining-up as the game: the tallest frame fills the box, every frame stands on its pivot
        int tallest = sprite.frames.stream().mapToInt(f -> f.height).max().orElse(1);
        int widest = sprite.frames.stream().mapToInt(f -> f.width).max().orElse(1);
        float pixel = box * 0.8f / Math.max(tallest, widest);
        SpriteFrame f = sprite.frames.get(index);
        float groundX = at.x + box / 2;
        float groundY = at.y + box * 0.9f;
        float x0 = groundX + (f.x - f.pivotX) * pixel;
        float y0 = groundY + (f.y - f.pivotY) * pixel;
        float[] uv = atlas.uv(index);
        draw.addImage(((Texture) atlas.texture).id, x0, y0, x0 + f.width * pixel, y0 + f.height * pixel, uv[0], uv[1], uv[0] + uv[2], uv[1] + uv[3]);
        draw.addLine(at.x + 8, groundY, at.x + box - 8, groundY, EditorStyle.pack(Theme.SELECTION));
        draw.addText(at.x + 6, at.y + 4, EditorStyle.pack(Theme.TEXT_FAINT), "frame " + index);
        ImGui.dummy(box, box);
    }

    // ---------------------------------------------------------------- grid slicing

    private void drawGridPopup(SpriteRenderer sprite) {
        if (!ImGui.beginPopup("grid-slice")) return;
        ImGui.textDisabled("CUT INTO EQUAL CELLS");
        if (ImGui.radioButton("By count", gridByCount)) gridByCount = true;
        ImGui.sameLine();
        if (ImGui.radioButton("By cell size", !gridByCount)) gridByCount = false;
        ImGui.setNextItemWidth(180);
        if (gridByCount) ImGui.dragInt2("Columns, rows", gridCount, 0.1f, 1, 256);
        else ImGui.dragInt2("Cell width, height", gridCell, 0.2f, 1, 4096);
        ImGui.setNextItemWidth(180);
        ImGui.dragInt2("Offset x, y", gridOffset, 0.2f, 0, 4096);
        ImGui.setNextItemWidth(180);
        ImGui.dragInt2("Gap x, y", gridGap, 0.2f, 0, 4096);
        if (ImGui.checkbox("Skip empty cells", gridSkipEmpty)) gridSkipEmpty = !gridSkipEmpty;
        if (Widgets.primaryButton("Make frames")) {
            sliceGrid(sprite);
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private void sliceGrid(SpriteRenderer sprite) {
        try {
            BufferedImage picture = ctx.renderer.assets().picture(sprite.texture, sprite.removeBackground);
            int usableWidth = picture.getWidth() - gridOffset[0];
            int usableHeight = picture.getHeight() - gridOffset[1];
            int cellWidth = gridByCount ? Math.max(1, (usableWidth - gridGap[0] * (gridCount[0] - 1)) / gridCount[0]) : gridCell[0];
            int cellHeight = gridByCount ? Math.max(1, (usableHeight - gridGap[1] * (gridCount[1] - 1)) / gridCount[1]) : gridCell[1];
            remember(sprite);
            sprite.frames.clear();
            for (int y = gridOffset[1]; y + cellHeight <= picture.getHeight(); y += cellHeight + gridGap[1]) {
                for (int x = gridOffset[0]; x + cellWidth <= picture.getWidth(); x += cellWidth + gridGap[0]) {
                    if (gridSkipEmpty && isEmpty(picture, x, y, cellWidth, cellHeight)) continue;
                    sprite.frames.add(SpriteFrame.rectangle(x, y, cellWidth, cellHeight));
                }
            }
            selected = -1;
            sprite.frame = 0;
            ctx.markDirty();
            ctx.log.info("Cut " + sprite.frames.size() + " frames of " + cellWidth + " × " + cellHeight + " px");
        } catch (IOException e) {
            ctx.log.warning("Couldn't read " + sprite.texture + ": " + e.getMessage());
        }
    }

    private static boolean isEmpty(BufferedImage picture, int x0, int y0, int width, int height) {
        for (int y = y0; y < y0 + height; y++) {
            for (int x = x0; x < x0 + width; x++) if ((picture.getRGB(x, y) >>> 24) > 16) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- undo & shared helpers

    /** Saves the frames before a change, for Undo. */
    private void remember(SpriteRenderer sprite) {
        List<SpriteFrame> copy = new ArrayList<>();
        for (SpriteFrame f : sprite.frames) copy.add(f.copy());
        undo.push(copy);
        while (undo.size() > MAX_UNDO) undo.removeLast();
    }

    private void undo(SpriteRenderer sprite) {
        sprite.frames.clear();
        sprite.frames.addAll(undo.pop());
        selected = Math.min(selected, sprite.frames.size() - 1);
        sprite.frame = Math.min(sprite.frame, Math.max(0, sprite.frameCount() - 1));
        ctx.markDirty();
    }

    /**
     * Finds the frames on a sprite's sheet automatically (replacing any it had). A picture with
     * no see-through pixels gets Remove background turned on first, or the whole sheet would be
     * one frame. Returns false if the picture couldn't be read.
     */
    static boolean findFrames(EditorContext ctx, SpriteRenderer sprite) {
        try {
            BufferedImage picture = ctx.renderer.assets().picture(sprite.texture, false);
            if (!sprite.removeBackground && !hasSeeThroughPixels(picture)) {
                sprite.removeBackground = true;
                ctx.log.info("Turned on Remove bg, so the frames can be told apart");
            }
            if (sprite.removeBackground) picture = ctx.renderer.assets().picture(sprite.texture, true);
            List<SpriteFrame> found = SpriteSlicer.findFrames(picture);
            sprite.frames.clear();
            sprite.frames.addAll(found);
            sprite.frame = 0;
            ctx.markDirty();
            ctx.log.info("Found " + found.size() + " frames in " + sprite.texture.substring(sprite.texture.lastIndexOf('/') + 1));
            return true;
        } catch (IOException e) {
            ctx.log.warning("Couldn't read " + sprite.texture + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean hasSeeThroughPixels(BufferedImage picture) {
        if (!picture.getColorModel().hasAlpha()) return false;
        int[] argb = picture.getRGB(0, 0, picture.getWidth(), picture.getHeight(), null, 0, picture.getWidth());
        for (int p : argb) if ((p >>> 24) < 128) return true;
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
