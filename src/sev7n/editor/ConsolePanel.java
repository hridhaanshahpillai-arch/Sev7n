package sev7n.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import sev7n.game.Log;
import sev7n.ui.Theme;

import java.awt.Color;
import java.util.List;

/** print() output, errors and engine messages. Click an error to jump to its line. */
final class ConsolePanel {

    static final String TITLE = "Console";

    private final EditorContext ctx;
    private int seenVersion = -1;

    ConsolePanel(EditorContext ctx) {
        this.ctx = ctx;
    }

    void draw() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        List<Log.Entry> entries = ctx.log.entries();
        long errors = entries.stream().filter(e -> e.level() == Log.Level.ERROR).count();

        if (ImGui.smallButton("Clear")) ctx.log.clear();
        ImGui.sameLine();
        if (errors > 0) ImGui.textColored(EditorStyle.pack(Theme.ERROR), errors + (errors == 1 ? " error" : " errors"));
        else ImGui.textDisabled("No errors");
        ImGui.separator();

        ImGui.beginChild("##log", 0, 0, false, ImGuiWindowFlags.HorizontalScrollbar);
        boolean atBottom = ImGui.getScrollY() >= ImGui.getScrollMaxY() - 4;
        for (int i = 0; i < entries.size(); i++) {
            Log.Entry entry = entries.get(i);
            ImGui.pushID(i);
            ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(colorOf(entry.level())));
            if (entry.file() != null) {
                if (ImGui.selectable(entry.text())) ctx.openScript(entry.file(), entry.line());
                ImGui.popStyleColor();
                if (ImGui.isItemHovered()) ImGui.setTooltip("Open " + entry.file() + " at line " + entry.line());
            } else {
                ImGui.textUnformatted(entry.text());
                ImGui.popStyleColor();
            }
            ImGui.popID();
        }
        if (ctx.log.version() != seenVersion) {
            if (atBottom) ImGui.setScrollHereY(1.0f); // follow new messages unless the user scrolled up
            seenVersion = ctx.log.version();
        }
        ImGui.endChild();
        ImGui.end();
    }

    private static Color colorOf(Log.Level level) {
        return switch (level) {
            case INFO -> Theme.TEXT;
            case WARNING -> new Color(0xE7EE98);
            case ERROR -> Theme.ERROR;
            case SYSTEM -> Theme.TEXT_FAINT;
        };
    }
}
