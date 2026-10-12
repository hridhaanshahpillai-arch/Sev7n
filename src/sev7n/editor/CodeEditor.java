package sev7n.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import sev7n.ui.Theme;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * A small code editor widget for Seven scripts, drawn with ImGui:
 * syntax colors, line numbers, indent guides, current/error line highlights,
 * selection, clipboard, undo/redo, auto-indent, Tab = 4 spaces, Ctrl+/ comments.
 *
 * Text is kept as a list of lines. Positions are (line, column), both 0-based.
 */
final class CodeEditor {

    private static final String INDENT = "    ";
    private static final int MAX_UNDO = 300;
    private static final float LINE_SPACING = 1.4f;
    private static final String MEASURE = "M".repeat(100);

    private record Position(int line, int col) implements Comparable<Position> {
        @Override
        public int compareTo(Position o) {
            return line != o.line ? Integer.compare(line, o.line) : Integer.compare(col, o.col);
        }
    }

    private record Snapshot(String text, Position caret) { }

    private enum EditKind { TYPING, OTHER }

    private final List<StringBuilder> lines = new ArrayList<>();
    private final Set<String> builtins;
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();

    private Position caret = new Position(0, 0);
    private Position anchor = null;         // other end of the selection, or null
    private int desiredCol = 0;             // column to aim for when moving up/down
    private int errorLine = -1;
    private int version = 0;
    private int savedVersion = 0;
    private EditKind lastEdit = null;
    private int[][] colors;
    private boolean scrollToCaret = false;
    private boolean focusRequested = false;
    private boolean focused = false;
    private boolean selectingWithMouse = false;
    private boolean readOnly = false;
    private long lastInputNanos = System.nanoTime();

    // layout of the last frame, used to map the mouse to text positions
    private float originX;
    private float originY;
    private float charWidth = 8;
    private float lineHeight = 18;

    CodeEditor(String text, Set<String> builtins) {
        this.builtins = builtins;
        setText(text);
    }

    // ---------------------------------------------------------------- public API

    String text() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(lines.get(i));
        }
        return out.toString();
    }

    boolean modified() {
        return version != savedVersion;
    }

    void markSaved() {
        savedVersion = version;
    }

    boolean focused() {
        return focused;
    }

    /** Read-only editors can be scrolled, selected and copied, but not changed. */
    void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /** Replaces all the text (e.g. regenerated code), keeping the caret roughly in place. */
    void replaceText(String text) {
        Position keep = caret;
        loadLines(clean(text));
        int line = Math.min(keep.line(), lines.size() - 1);
        caret = new Position(line, Math.min(keep.col(), lines.get(line).length()));
        anchor = null;
        undoStack.clear();
        redoStack.clear();
        changed();
        savedVersion = version;
    }

    /** Moves the caret to a 1-based line, optionally marking it red as the error line. */
    void goToLine(int line, boolean markError) {
        int index = Math.max(0, Math.min(lines.size() - 1, line - 1));
        caret = new Position(index, firstNonSpace(lines.get(index).toString()));
        anchor = null;
        errorLine = markError ? index : -1;
        scrollToCaret = true;
        focusRequested = true;
    }

    /** Draws the editor filling the available space. 'typed' holds characters typed this frame. */
    void draw(String id, Deque<Integer> typed) {
        ImGui.pushFont(EditorStyle.codeFont);
        ImGui.pushStyleColor(ImGuiCol.ChildBg, EditorStyle.pack(Theme.EDITOR));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild(id, 0, 0, false, ImGuiWindowFlags.HorizontalScrollbar | ImGuiWindowFlags.NoNavInputs);
        if (focusRequested) {
            ImGui.setWindowFocus();
            focusRequested = false;
        }
        focused = ImGui.isWindowFocused();

        // ImGui rounds text widths up to whole pixels, so measure many characters for the true advance
        charWidth = ImGui.calcTextSize(MEASURE).x / MEASURE.length();
        lineHeight = (float) Math.floor(ImGui.getTextLineHeight() * LINE_SPACING);
        float gutterWidth = charWidth * (Math.max(3, String.valueOf(lines.size()).length()) + 3);
        ImVec2 windowPos = ImGui.getWindowPos();
        ImVec2 windowSize = ImGui.getWindowSize();
        float padding = charWidth;
        originX = windowPos.x - ImGui.getScrollX() + gutterWidth + padding;
        originY = windowPos.y - ImGui.getScrollY() + 4;

        // reserve the scrollable area
        int longest = lines.stream().mapToInt(StringBuilder::length).max().orElse(0);
        ImGui.dummy(gutterWidth + padding + (longest + 4) * charWidth, (lines.size() + 4) * lineHeight);

        if (focused) {
            handleKeyboard(typed);
        }
        handleMouse(windowPos, windowSize);
        if (scrollToCaret) {
            keepCaretVisible(windowSize, gutterWidth + padding);
            scrollToCaret = false;
        }
        if (colors == null) colors = SyntaxColors.colorize(lines, builtins);

        render(windowPos, windowSize, gutterWidth);

        ImGui.endChild();
        ImGui.popStyleVar();
        ImGui.popStyleColor();
        ImGui.popFont();
    }

    // ---------------------------------------------------------------- rendering

    private void render(ImVec2 windowPos, ImVec2 windowSize, float gutterWidth) {
        ImDrawList draw = ImGui.getWindowDrawList();
        int fontSize = ImGui.getFontSize();
        float textOffsetY = (lineHeight - fontSize) / 2;
        float scrollY = ImGui.getScrollY();
        int first = Math.max(0, (int) ((scrollY - 4) / lineHeight));
        int last = Math.min(lines.size() - 1, first + (int) (windowSize.y / lineHeight) + 1);
        float right = windowPos.x + windowSize.x;

        Position selStart = selectionStart();
        Position selEnd = selectionEnd();

        for (int line = first; line <= last; line++) {
            float y = originY + line * lineHeight;
            if (line == caret.line() && anchor == null) {
                draw.addRectFilled(windowPos.x, y, right, y + lineHeight, EditorStyle.pack(Theme.CURRENT_LINE));
            }
            if (line == errorLine) {
                draw.addRectFilled(windowPos.x, y, right, y + lineHeight, EditorStyle.pack(Theme.ERROR_LINE));
            }
            if (anchor != null && line >= selStart.line() && line <= selEnd.line()) {
                int from = line == selStart.line() ? selStart.col() : 0;
                int to = line == selEnd.line() ? selEnd.col() : lines.get(line).length() + 1; // +1 shows the newline
                draw.addRectFilled(originX + from * charWidth, y, originX + to * charWidth, y + lineHeight,
                        EditorStyle.pack(Theme.SELECTION));
            }
            drawIndentGuides(draw, line, y);
            drawLineText(draw, line, y + textOffsetY, fontSize);
        }

        // caret: solid while typing, blinking when idle
        long idle = System.nanoTime() - lastInputNanos;
        boolean caretVisible = idle < 500_000_000L || (idle / 530_000_000L) % 2 == 0;
        if (focused && caretVisible) {
            float x = originX + caret.col() * charWidth;
            float y = originY + caret.line() * lineHeight;
            draw.addRectFilled(x, y + 2, x + 2, y + lineHeight - 2, EditorStyle.pack(Theme.CARET));
        }

        drawGutter(draw, windowPos, windowSize, gutterWidth, first, last, textOffsetY, fontSize);
    }

    private void drawLineText(ImDrawList draw, int line, float y, int fontSize) {
        StringBuilder text = lines.get(line);
        int[] lineColors = line < colors.length ? colors[line] : new int[0];
        int start = 0;
        while (start < text.length()) {
            // draw runs of same-colored characters in one call
            int color = start < lineColors.length ? lineColors[start] : SyntaxColors.PLAIN;
            int end = start + 1;
            while (end < text.length() && (end < lineColors.length ? lineColors[end] : SyntaxColors.PLAIN) == color) end++;
            String run = text.substring(start, end);
            if (!run.isBlank()) {
                draw.addText(EditorStyle.codeFont, fontSize, originX + start * charWidth, y, color, run);
            }
            start = end;
        }
    }

    /** Faint vertical lines at each indent level. Blank lines borrow from their neighbours. */
    private void drawIndentGuides(ImDrawList draw, int line, float y) {
        int levels = indentLevels(line);
        for (int level = 0; level < levels; level++) {
            float x = originX + level * INDENT.length() * charWidth + 1;
            draw.addLine(x, y, x, y + lineHeight, EditorStyle.pack(Theme.INDENT_GUIDE));
        }
    }

    private void drawGutter(ImDrawList draw, ImVec2 windowPos, ImVec2 windowSize, float gutterWidth,
                            int first, int last, float textOffsetY, int fontSize) {
        draw.addRectFilled(windowPos.x, windowPos.y, windowPos.x + gutterWidth, windowPos.y + windowSize.y,
                EditorStyle.pack(Theme.EDITOR));
        for (int line = first; line <= last; line++) {
            float y = originY + line * lineHeight;
            String number = String.valueOf(line + 1);
            float x = windowPos.x + gutterWidth - charWidth * 1.5f - number.length() * charWidth;
            if (line == errorLine) {
                draw.addCircleFilled(windowPos.x + charWidth, y + lineHeight / 2, charWidth * 0.35f, EditorStyle.pack(Theme.ERROR));
            }
            int color = EditorStyle.pack(line == caret.line() ? Theme.TEXT : Theme.TEXT_FAINT);
            draw.addText(EditorStyle.codeFont, fontSize, x, y + textOffsetY, color, number);
        }
    }

    // ---------------------------------------------------------------- mouse

    private void handleMouse(ImVec2 windowPos, ImVec2 windowSize) {
        ImVec2 mouse = ImGui.getMousePos();
        float scrollbar = ImGui.getStyle().getScrollbarSize();
        boolean overText = ImGui.isWindowHovered()
                && mouse.x < windowPos.x + windowSize.x - scrollbar
                && mouse.y < windowPos.y + windowSize.y - scrollbar;

        if (overText) ImGui.setMouseCursor(ImGuiMouseCursor.TextInput);

        if (overText && ImGui.isMouseDoubleClicked(0)) {
            selectWordAt(positionAt(mouse));
            selectingWithMouse = false;
        } else if (overText && ImGui.isMouseClicked(0)) {
            Position clicked = positionAt(mouse);
            if (ImGui.getIO().getKeyShift()) {
                if (anchor == null) anchor = caret;
            } else {
                anchor = clicked;
            }
            moveCaret(clicked);
            selectingWithMouse = true;
        } else if (selectingWithMouse && ImGui.isMouseDown(0)) {
            moveCaret(positionAt(mouse));
            scrollToCaret = true;
        } else if (selectingWithMouse) {
            selectingWithMouse = false;
            if (anchor != null && anchor.equals(caret)) anchor = null;
        }
    }

    private Position positionAt(ImVec2 mouse) {
        int line = clamp((int) Math.floor((mouse.y - originY) / lineHeight), 0, lines.size() - 1);
        int col = clamp(Math.round((mouse.x - originX) / charWidth), 0, lines.get(line).length());
        return new Position(line, col);
    }

    private void selectWordAt(Position p) {
        String line = lines.get(p.line()).toString();
        int start = p.col();
        int end = p.col();
        while (start > 0 && isWordChar(line.charAt(start - 1))) start--;
        while (end < line.length() && isWordChar(line.charAt(end))) end++;
        anchor = new Position(p.line(), start);
        moveCaret(new Position(p.line(), end));
    }

    // ---------------------------------------------------------------- keyboard

    private void handleKeyboard(Deque<Integer> typed) {
        ImGuiIO io = ImGui.getIO();
        boolean ctrl = io.getKeyCtrl();
        boolean shift = io.getKeyShift();

        if (readOnly) {
            if (ctrl && pressed(ImGuiKey.A)) selectAll();
            if (ctrl && pressed(ImGuiKey.C)) copy();
            handleNavigation(ctrl, shift);
            return;
        }

        if (ctrl) {
            if (pressed(ImGuiKey.A)) selectAll();
            if (pressed(ImGuiKey.C)) copy();
            if (pressed(ImGuiKey.X)) cut();
            if (pressed(ImGuiKey.V)) paste();
            if (pressed(ImGuiKey.Z)) {
                if (shift) redo();
                else undo();
            }
            if (pressed(ImGuiKey.Y)) redo();
            if (pressed(ImGuiKey.Slash)) toggleComment();
            if (pressed(ImGuiKey.D)) duplicateLine();
        }

        handleNavigation(ctrl, shift);

        if (pressed(ImGuiKey.Backspace)) backspace();
        if (pressed(ImGuiKey.Delete)) deleteForward();
        if (pressed(ImGuiKey.Enter) || pressed(ImGuiKey.KeypadEnter)) newline();
        if (pressed(ImGuiKey.Tab)) {
            if (shift) outdentSelection();
            else tab();
        }

        while (!typed.isEmpty()) {
            int codePoint = typed.poll();
            if (codePoint >= 32 && codePoint != 127) typeCharacter(new String(Character.toChars(codePoint)));
        }
    }

    private void handleNavigation(boolean ctrl, boolean shift) {
        if (pressed(ImGuiKey.LeftArrow)) move(ctrl ? wordLeft() : left(), shift);
        if (pressed(ImGuiKey.RightArrow)) move(ctrl ? wordRight() : right(), shift);
        if (pressed(ImGuiKey.UpArrow)) moveVertically(-1, shift);
        if (pressed(ImGuiKey.DownArrow)) moveVertically(1, shift);
        if (pressed(ImGuiKey.PageUp)) moveVertically(-visibleLines(), shift);
        if (pressed(ImGuiKey.PageDown)) moveVertically(visibleLines(), shift);
        if (pressed(ImGuiKey.Home)) move(ctrl ? new Position(0, 0) : smartHome(), shift);
        if (pressed(ImGuiKey.End)) {
            move(ctrl ? new Position(lines.size() - 1, lines.get(lines.size() - 1).length())
                    : new Position(caret.line(), lines.get(caret.line()).length()), shift);
        }
        if (pressed(ImGuiKey.Escape)) anchor = null;
    }

    private static boolean pressed(int key) {
        return ImGui.isKeyPressed(key, true);
    }

    private int visibleLines() {
        return Math.max(1, (int) (ImGui.getWindowHeight() / lineHeight) - 1);
    }

    // ---------------------------------------------------------------- caret movement

    private void move(Position target, boolean extendSelection) {
        if (extendSelection) {
            if (anchor == null) anchor = caret;
        } else {
            anchor = null;
        }
        moveCaret(target);
        scrollToCaret = true;
    }

    private void moveCaret(Position target) {
        caret = target;
        desiredCol = target.col();
        lastInputNanos = System.nanoTime();
    }

    private void moveVertically(int delta, boolean extendSelection) {
        int line = clamp(caret.line() + delta, 0, lines.size() - 1);
        int keep = desiredCol;
        move(new Position(line, Math.min(keep, lines.get(line).length())), extendSelection);
        desiredCol = keep;
    }

    private Position left() {
        if (anchor != null && !ImGui.getIO().getKeyShift()) return selectionStart();
        if (caret.col() > 0) return new Position(caret.line(), caret.col() - 1);
        if (caret.line() > 0) return new Position(caret.line() - 1, lines.get(caret.line() - 1).length());
        return caret;
    }

    private Position right() {
        if (anchor != null && !ImGui.getIO().getKeyShift()) return selectionEnd();
        if (caret.col() < lines.get(caret.line()).length()) return new Position(caret.line(), caret.col() + 1);
        if (caret.line() < lines.size() - 1) return new Position(caret.line() + 1, 0);
        return caret;
    }

    private Position wordLeft() {
        String line = lines.get(caret.line()).toString();
        int col = caret.col();
        if (col == 0) return left();
        while (col > 0 && line.charAt(col - 1) == ' ') col--;
        if (col > 0 && isWordChar(line.charAt(col - 1))) {
            while (col > 0 && isWordChar(line.charAt(col - 1))) col--;
        } else if (col > 0) {
            col--;
        }
        return new Position(caret.line(), col);
    }

    private Position wordRight() {
        String line = lines.get(caret.line()).toString();
        int col = caret.col();
        if (col == line.length()) return right();
        if (isWordChar(line.charAt(col))) {
            while (col < line.length() && isWordChar(line.charAt(col))) col++;
        } else {
            col++;
        }
        while (col < line.length() && line.charAt(col) == ' ') col++;
        return new Position(caret.line(), col);
    }

    /** Home jumps to the first non-space character, or to column 0 if already there. */
    private Position smartHome() {
        int first = firstNonSpace(lines.get(caret.line()).toString());
        return new Position(caret.line(), caret.col() == first ? 0 : first);
    }

    private void keepCaretVisible(ImVec2 windowSize, float textLeft) {
        float caretY = caret.line() * lineHeight;
        float scrollY = ImGui.getScrollY();
        float viewHeight = windowSize.y - ImGui.getStyle().getScrollbarSize() - 8;
        if (caretY < scrollY) ImGui.setScrollY(caretY);
        else if (caretY + lineHeight > scrollY + viewHeight) ImGui.setScrollY(caretY + lineHeight - viewHeight);

        float caretX = caret.col() * charWidth;
        float scrollX = ImGui.getScrollX();
        float viewWidth = windowSize.x - textLeft - ImGui.getStyle().getScrollbarSize() - charWidth * 2;
        if (caretX < scrollX) ImGui.setScrollX(Math.max(0, caretX - charWidth * 4));
        else if (caretX > scrollX + viewWidth) ImGui.setScrollX(caretX - viewWidth + charWidth * 4);
    }

    // ---------------------------------------------------------------- editing

    private void typeCharacter(String s) {
        checkpoint(s.equals(" ") ? EditKind.OTHER : EditKind.TYPING);
        deleteSelection();
        if (s.equals("}")) {
            // typing '}' on an otherwise blank line removes one indent level
            String before = lines.get(caret.line()).substring(0, caret.col());
            if (before.isBlank() && before.length() >= INDENT.length()) {
                removeRange(new Position(caret.line(), caret.col() - INDENT.length()), caret);
            }
        }
        insert(s);
    }

    private void newline() {
        checkpoint(EditKind.OTHER);
        deleteSelection();
        String line = lines.get(caret.line()).toString();
        String before = line.substring(0, caret.col());
        String after = line.substring(caret.col());
        String indent = line.substring(0, Math.min(firstNonSpace(line), caret.col()));

        if (before.stripTrailing().endsWith("{")) {
            if (after.stripLeading().startsWith("}")) {
                // {|}  becomes  {\n    |\n}
                insert("\n" + indent + INDENT);
                Position middle = caret;
                insert("\n" + indent);
                moveCaret(middle);
            } else {
                insert("\n" + indent + INDENT);
            }
        } else {
            insert("\n" + indent);
        }
    }

    private void tab() {
        if (anchor != null && selectionStart().line() != selectionEnd().line()) {
            indentSelection();
            return;
        }
        checkpoint(EditKind.OTHER);
        deleteSelection();
        int spaces = INDENT.length() - caret.col() % INDENT.length();
        insert(" ".repeat(spaces));
    }

    private void indentSelection() {
        checkpoint(EditKind.OTHER);
        Position start = selectionStart();
        Position end = selectionEnd();
        for (int line = start.line(); line <= end.line(); line++) lines.get(line).insert(0, INDENT);
        anchor = new Position(start.line(), start.col() + INDENT.length());
        caret = new Position(end.line(), end.col() + INDENT.length());
        changed();
    }

    private void outdentSelection() {
        checkpoint(EditKind.OTHER);
        Position start = anchor == null ? caret : selectionStart();
        Position end = anchor == null ? caret : selectionEnd();
        for (int line = start.line(); line <= end.line(); line++) {
            StringBuilder text = lines.get(line);
            int remove = 0;
            while (remove < INDENT.length() && remove < text.length() && text.charAt(remove) == ' ') remove++;
            text.delete(0, remove);
            if (line == caret.line()) caret = new Position(line, Math.max(0, caret.col() - remove));
            if (anchor != null && line == anchor.line()) anchor = new Position(line, Math.max(0, anchor.col() - remove));
        }
        changed();
    }

    private void backspace() {
        checkpoint(EditKind.OTHER);
        if (deleteSelection()) return;
        if (caret.col() > 0) {
            String before = lines.get(caret.line()).substring(0, caret.col());
            // in leading whitespace, delete back to the previous indent stop
            int count = before.isBlank() ? (caret.col() - 1) % INDENT.length() + 1 : 1;
            removeRange(new Position(caret.line(), caret.col() - count), caret);
        } else if (caret.line() > 0) {
            removeRange(new Position(caret.line() - 1, lines.get(caret.line() - 1).length()), caret);
        }
    }

    private void deleteForward() {
        checkpoint(EditKind.OTHER);
        if (deleteSelection()) return;
        Position next = caret.col() < lines.get(caret.line()).length()
                ? new Position(caret.line(), caret.col() + 1)
                : caret.line() < lines.size() - 1 ? new Position(caret.line() + 1, 0) : caret;
        removeRange(caret, next);
    }

    /** Ctrl+/ adds or removes "// " on every selected line. */
    private void toggleComment() {
        checkpoint(EditKind.OTHER);
        int first = anchor == null ? caret.line() : selectionStart().line();
        int last = anchor == null ? caret.line() : selectionEnd().line();
        boolean allCommented = true;
        for (int line = first; line <= last; line++) {
            String text = lines.get(line).toString();
            if (!text.isBlank() && !text.stripLeading().startsWith("//")) allCommented = false;
        }
        for (int line = first; line <= last; line++) {
            StringBuilder text = lines.get(line);
            if (text.toString().isBlank()) continue;
            int at = firstNonSpace(text.toString());
            if (allCommented) {
                text.delete(at, text.indexOf("// ", at) == at ? at + 3 : at + 2);
            } else {
                text.insert(at, "// ");
            }
        }
        caret = new Position(caret.line(), Math.min(caret.col(), lines.get(caret.line()).length()));
        if (anchor != null) anchor = new Position(anchor.line(), Math.min(anchor.col(), lines.get(anchor.line()).length()));
        changed();
    }

    private void duplicateLine() {
        checkpoint(EditKind.OTHER);
        lines.add(caret.line() + 1, new StringBuilder(lines.get(caret.line())));
        caret = new Position(caret.line() + 1, caret.col());
        anchor = null;
        changed();
    }

    private void selectAll() {
        anchor = new Position(0, 0);
        moveCaret(new Position(lines.size() - 1, lines.get(lines.size() - 1).length()));
    }

    private void copy() {
        if (anchor != null) ImGui.setClipboardText(selectedText());
    }

    private void cut() {
        if (anchor == null) return;
        checkpoint(EditKind.OTHER);
        ImGui.setClipboardText(selectedText());
        deleteSelection();
    }

    private void paste() {
        String clip = ImGui.getClipboardText();
        if (clip == null || clip.isEmpty()) return;
        checkpoint(EditKind.OTHER);
        deleteSelection();
        insert(clean(clip));
    }

    // ---------------------------------------------------------------- undo

    private void checkpoint(EditKind kind) {
        boolean continuesTyping = kind == EditKind.TYPING && lastEdit == EditKind.TYPING && anchor == null;
        lastEdit = kind;
        lastInputNanos = System.nanoTime();
        if (continuesTyping) return;
        undoStack.push(new Snapshot(text(), caret));
        if (undoStack.size() > MAX_UNDO) undoStack.removeLast();
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(new Snapshot(text(), caret));
        restore(undoStack.pop());
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(new Snapshot(text(), caret));
        restore(redoStack.pop());
    }

    private void restore(Snapshot snapshot) {
        loadLines(snapshot.text());
        int line = Math.min(snapshot.caret().line(), lines.size() - 1);
        caret = new Position(line, Math.min(snapshot.caret().col(), lines.get(line).length()));
        anchor = null;
        lastEdit = null;
        scrollToCaret = true;
        changed();
    }

    // ---------------------------------------------------------------- low-level text changes

    private void setText(String text) {
        loadLines(clean(text));
        caret = new Position(0, 0);
        colors = null;
    }

    private void loadLines(String text) {
        lines.clear();
        for (String line : text.split("\n", -1)) lines.add(new StringBuilder(line));
    }

    /** Inserts text (possibly several lines) at the caret and moves the caret after it. */
    private void insert(String text) {
        StringBuilder line = lines.get(caret.line());
        String tail = line.substring(caret.col());
        line.setLength(caret.col());
        String[] parts = text.split("\n", -1);
        line.append(parts[0]);
        int lineIndex = caret.line();
        for (int i = 1; i < parts.length; i++) {
            lines.add(++lineIndex, new StringBuilder(parts[i]));
        }
        int col = lines.get(lineIndex).length();
        lines.get(lineIndex).append(tail);
        moveCaret(new Position(lineIndex, col));
        scrollToCaret = true;
        changed();
    }

    /** Deletes the selection, if any. Returns true if something was deleted. */
    private boolean deleteSelection() {
        if (anchor == null) return false;
        Position start = selectionStart();
        Position end = selectionEnd();
        anchor = null;
        if (start.equals(end)) return false;
        removeRange(start, end);
        return true;
    }

    private void removeRange(Position start, Position end) {
        StringBuilder first = lines.get(start.line());
        String tail = lines.get(end.line()).substring(end.col());
        first.setLength(start.col());
        first.append(tail);
        for (int i = end.line(); i > start.line(); i--) lines.remove(i);
        moveCaret(start);
        scrollToCaret = true;
        changed();
    }

    private String selectedText() {
        Position start = selectionStart();
        Position end = selectionEnd();
        if (start.line() == end.line()) return lines.get(start.line()).substring(start.col(), end.col());
        StringBuilder out = new StringBuilder(lines.get(start.line()).substring(start.col()));
        for (int i = start.line() + 1; i < end.line(); i++) out.append('\n').append(lines.get(i));
        out.append('\n').append(lines.get(end.line()), 0, end.col());
        return out.toString();
    }

    private void changed() {
        version++;
        colors = null;
        errorLine = -1;
    }

    // ---------------------------------------------------------------- helpers

    private Position selectionStart() {
        return anchor == null || caret.compareTo(anchor) < 0 ? caret : anchor;
    }

    private Position selectionEnd() {
        return anchor == null || caret.compareTo(anchor) > 0 ? caret : anchor;
    }

    private int indentLevels(int line) {
        String text = lines.get(line).toString();
        if (!text.isBlank()) return firstNonSpace(text) / INDENT.length();
        int above = 0;
        int below = 0;
        for (int i = line - 1; i >= 0; i--) {
            if (!lines.get(i).toString().isBlank()) {
                above = firstNonSpace(lines.get(i).toString()) / INDENT.length();
                break;
            }
        }
        for (int i = line + 1; i < lines.size(); i++) {
            if (!lines.get(i).toString().isBlank()) {
                below = firstNonSpace(lines.get(i).toString()) / INDENT.length();
                break;
            }
        }
        return Math.min(above, below);
    }

    private static int firstNonSpace(String text) {
        int i = 0;
        while (i < text.length() && text.charAt(i) == ' ') i++;
        return i;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Normalizes line endings and turns tabs into spaces, so every character is one column. */
    private static String clean(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", INDENT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
