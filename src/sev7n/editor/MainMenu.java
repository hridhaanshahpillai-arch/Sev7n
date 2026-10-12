package sev7n.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import sev7n.Sev7n;
import sev7n.ui.Theme;

/** The menu bar, with Play / Pause in the middle (like Unity). */
final class MainMenu {

    /** Things only the app itself can do. */
    interface Actions {
        void togglePlay();

        void quit();

        void resetLayout();

        void focusSelection();

        /** Opens the Guides window, on the guide in this file (e.g. "SEVEN.md") if given. */
        void openGuide(String fileName);

        void exportGame();

        void openSettings();
    }

    private final EditorContext ctx;
    private final Actions actions;

    MainMenu(EditorContext ctx, Actions actions) {
        this.ctx = ctx;
        this.actions = actions;
    }

    void draw() {
        if (!ImGui.beginMainMenuBar()) return;

        drawLogo();
        ImGui.sameLine(0, 16);

        if (ImGui.beginMenu("File")) {
            if (ImGui.menuItem("Save", "Ctrl+S")) ctx.saveAll();
            if (ImGui.menuItem("Import Assets...")) ProjectPanel.importWithDialog(ctx);
            if (ImGui.menuItem("Play in Window", "Ctrl+F5")) ctx.playInWindow();
            if (ImGui.menuItem("Export Game...")) actions.exportGame();
            if (ImGui.menuItem("Show Project Folder")) ProjectPanel.showInExplorer(ctx.project.root());
            ImGui.separator();
            if (ImGui.menuItem("Quit")) actions.quit();
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Edit")) {
            boolean hasSelection = ctx.selected() != null;
            if (ImGui.menuItem("Duplicate", "Ctrl+D", false, hasSelection)) ctx.duplicate(ctx.selected());
            if (ImGui.menuItem("Delete", "Del", false, hasSelection)) ctx.delete(ctx.selected());
            ImGui.separator();
            if (ImGui.menuItem("Focus Selection", "F", false, hasSelection)) actions.focusSelection();
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Create")) {
            for (String kind : EditorContext.CREATABLE) {
                if (kind.equals("Sprite") || kind.equals("Camera")) ImGui.separator();
                if (ImGui.menuItem(kind)) ctx.create(kind);
            }
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Window")) {
            if (ImGui.menuItem("Settings...", "Ctrl+,")) actions.openSettings();
            ImGui.separator();
            if (ImGui.menuItem("Reset Layout")) actions.resetLayout();
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Help")) {
            if (ImGui.menuItem("Guides", "F1")) actions.openGuide(null);
            ImGui.separator();
            if (ImGui.menuItem("Getting Started")) actions.openGuide("README.md");
            if (ImGui.menuItem("Seven Language")) actions.openGuide("SEVEN.md");
            if (ImGui.menuItem("Eights Visual Scripting")) actions.openGuide("EIGHTS.md");
            if (ImGui.menuItem("Importing Assets")) actions.openGuide("ASSETS.md");
            if (ImGui.menuItem("2D Games")) actions.openGuide("2D.md");
            if (ImGui.menuItem("Physics")) actions.openGuide("PHYSICS.md");
            if (ImGui.menuItem("Exporting a Game")) actions.openGuide("EXPORT.md");
            if (ImGui.menuItem("Settings")) actions.openGuide("SETTINGS.md");
            ImGui.separator();
            ImGui.textDisabled(Sev7n.NAME + " " + Sev7n.VERSION);
            ImGui.endMenu();
        }

        drawPlayControls();

        String status = ctx.project.name() + (ctx.anythingUnsaved() ? " *" : "")
                + (ctx.editorSettings.showFps ? "    " + Math.round(ImGui.getIO().getFramerate()) + " fps" : "");
        ImGui.sameLine(ImGui.getWindowWidth() - ImGui.calcTextSize(status).x - 16);
        ImGui.textDisabled(status);

        ImGui.endMainMenuBar();
    }

    /** "Sev7n" with a purple 7. Clicking it opens Settings. */
    private void drawLogo() {
        ImVec2 at = ImGui.getCursorScreenPos();
        float width = ImGui.calcTextSize("Sev7n").x;
        boolean clicked = ImGui.invisibleButton("##logo", width, ImGui.getFrameHeight());
        boolean hovered = ImGui.isItemHovered();
        ImDrawList draw = ImGui.getWindowDrawList();
        float y = at.y + (ImGui.getFrameHeight() - ImGui.getTextLineHeight()) / 2;
        if (hovered) draw.addRectFilled(at.x - 4, at.y, at.x + width + 4, at.y + ImGui.getFrameHeight(), EditorStyle.pack(Theme.SURFACE), 4);
        float x = at.x;
        for (String part : new String[] {"Sev", "7", "n"}) {
            int color = EditorStyle.pack(part.equals("7") ? (hovered ? Theme.ACCENT_HOVER : Theme.ACCENT) : Theme.TEXT);
            draw.addText(x, y, color, part);
            x += ImGui.calcTextSize(part).x;
        }
        if (hovered) ImGui.setTooltip("Settings: graphics, frame rate, editor");
        if (clicked) actions.openSettings();
    }

    private void drawPlayControls() {
        float size = ImGui.getFrameHeight();
        float total = size * 2 + 4;
        ImGui.sameLine(ImGui.getWindowWidth() / 2 - total / 2);
        if (glyphButton("##play", ctx.playing() ? Glyph.STOP : Glyph.PLAY, ctx.playing(), size)) actions.togglePlay();
        if (ImGui.isItemHovered()) ImGui.setTooltip(ctx.playing() ? "Stop (F5)" : "Play (F5)");
        ImGui.sameLine(0, 4);
        if (glyphButton("##pause", Glyph.PAUSE, ctx.paused(), size)) ctx.togglePause();
        if (ImGui.isItemHovered()) ImGui.setTooltip("Pause");
        ImGui.sameLine(0, 8);
        if (liveButton(ctx.editorSettings.liveMode, ctx.playing(), size)) ctx.setLiveMode(!ctx.editorSettings.liveMode);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(ctx.editorSettings.liveMode
                    ? "Live Mode is on: edit your game while it plays.\nChanges you make are kept when you stop, and scripts reload as soon as you save them.\nClick to turn off (changes made while playing are thrown away on Stop)."
                    : "Live Mode is off: changes made while playing are thrown away on Stop.\nClick to turn on, to edit your game while it plays.");
        }
    }

    /** A "LIVE" pill with a dot: purple when on, glowing while the game runs. */
    private static boolean liveButton(boolean on, boolean playing, float height) {
        String text = "LIVE";
        float dot = height * 0.22f;
        float width = ImGui.calcTextSize(text).x + dot * 2 + height * 0.7f;
        boolean clicked = ImGui.invisibleButton("##live", width, height);
        ImVec2 min = ImGui.getItemRectMin();
        boolean hovered = ImGui.isItemHovered();
        ImDrawList draw = ImGui.getWindowDrawList();
        int background = EditorStyle.pack(on && playing ? Theme.ACCENT : hovered ? Theme.SELECTION : Theme.SURFACE);
        draw.addRectFilled(min.x, min.y, min.x + width, min.y + height, background, height / 2);
        if (on && !playing) draw.addRect(min.x, min.y, min.x + width, min.y + height, EditorStyle.pack(Theme.ACCENT), height / 2, 0, 1.5f);

        int ink = EditorStyle.pack(on && playing ? Theme.BLACK : on ? Theme.ACCENT : Theme.TEXT_MUTED);
        float cx = min.x + height * 0.35f + dot;
        float cy = min.y + height / 2;
        if (on) draw.addCircleFilled(cx, cy, dot, ink);
        else draw.addCircle(cx, cy, dot, ink, 12, 1.5f);
        draw.addText(cx + dot * 1.8f, min.y + (height - ImGui.getTextLineHeight()) / 2, ink, text);
        return clicked;
    }

    private enum Glyph { PLAY, STOP, PAUSE }

    /** A square button with a drawn icon (fonts don't reliably have play/stop symbols). */
    private static boolean glyphButton(String id, Glyph glyph, boolean active, float size) {
        boolean clicked = ImGui.invisibleButton(id, size, size);
        ImVec2 min = ImGui.getItemRectMin();
        boolean hovered = ImGui.isItemHovered();
        ImDrawList draw = ImGui.getWindowDrawList();
        int background = EditorStyle.pack(active ? Theme.ACCENT : hovered ? Theme.SELECTION : Theme.SURFACE);
        draw.addRectFilled(min.x, min.y, min.x + size, min.y + size, background, 4);

        int ink = EditorStyle.pack(active ? Theme.BLACK : Theme.TEXT);
        float cx = min.x + size / 2;
        float cy = min.y + size / 2;
        float s = size * 0.22f;
        switch (glyph) {
            case PLAY -> draw.addTriangleFilled(cx - s * 0.8f, cy - s, cx - s * 0.8f, cy + s, cx + s, cy, ink);
            case STOP -> draw.addRectFilled(cx - s * 0.85f, cy - s * 0.85f, cx + s * 0.85f, cy + s * 0.85f, ink, 2);
            case PAUSE -> {
                draw.addRectFilled(cx - s, cy - s, cx - s * 0.3f, cy + s, ink, 1);
                draw.addRectFilled(cx + s * 0.3f, cy - s, cx + s, cy + s, ink, 1);
            }
        }
        return clicked;
    }
}
