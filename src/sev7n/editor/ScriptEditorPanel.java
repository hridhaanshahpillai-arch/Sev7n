package sev7n.editor;

import imgui.ImGui;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiTabItemFlags;
import imgui.type.ImBoolean;
import sev7n.game.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Open scripts, one tab each. Ctrl+S saves; Play saves automatically. */
final class ScriptEditorPanel {

    static final String TITLE = "Script";

    private final Log log;
    private final Set<String> builtins;
    private final Map<Path, CodeEditor> editors = new LinkedHashMap<>();
    private final Deque<Integer> typed = new ArrayDeque<>();
    private Path tabToSelect;
    private boolean focusRequested;

    ScriptEditorPanel(Log log, Set<String> builtins) {
        this.log = log;
        this.builtins = builtins;
    }

    /** Characters typed this frame (from the window's char callback). */
    void queueCharacter(int codePoint) {
        typed.add(codePoint);
    }

    /** Opens a script in a tab. With line > 0, jumps there and marks it as an error line. */
    void open(Path file, int line) {
        Path key = file.toAbsolutePath().normalize();
        CodeEditor editor = editors.get(key);
        if (editor == null) {
            try {
                editor = new CodeEditor(Files.readString(key, StandardCharsets.UTF_8), builtins);
            } catch (IOException e) {
                log.error("Couldn't open " + key.getFileName() + ": " + e.getMessage());
                return;
            }
            editors.put(key, editor);
        }
        if (line > 0) editor.goToLine(line, true);
        tabToSelect = key;
        focusRequested = true;
    }

    boolean anyModified() {
        return editors.values().stream().anyMatch(CodeEditor::modified);
    }

    boolean editorFocused() {
        return editors.values().stream().anyMatch(CodeEditor::focused);
    }

    void saveAll() {
        editors.forEach((file, editor) -> {
            if (editor.modified()) save(file, editor);
        });
    }

    private void save(Path file, CodeEditor editor) {
        try {
            Files.writeString(file, editor.text(), StandardCharsets.UTF_8);
            editor.markSaved();
            log.system("Saved " + file.getFileName());
        } catch (IOException e) {
            log.error("Couldn't save " + file.getFileName() + ": " + e.getMessage());
        }
    }

    void draw() {
        if (focusRequested) {
            ImGui.setWindowFocus(TITLE);
            focusRequested = false;
        }
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            typed.clear();
            return;
        }
        if (editors.isEmpty()) {
            Widgets.hint("Double-click a script in the Project panel to edit it here.");
        } else if (ImGui.beginTabBar("##scripts", ImGuiTabBarFlags.Reorderable | ImGuiTabBarFlags.FittingPolicyScroll)) {
            List<Path> closed = new ArrayList<>();
            for (Map.Entry<Path, CodeEditor> entry : editors.entrySet()) {
                Path file = entry.getKey();
                CodeEditor editor = entry.getValue();
                ImBoolean keepOpen = new ImBoolean(true);
                int flags = (file.equals(tabToSelect) ? ImGuiTabItemFlags.SetSelected : 0)
                        | (editor.modified() ? ImGuiTabItemFlags.UnsavedDocument : 0);
                if (ImGui.beginTabItem(file.getFileName() + "###" + file, keepOpen, flags)) {
                    editor.draw("##code", typed);
                    ImGui.endTabItem();
                }
                if (!keepOpen.get()) closed.add(file);
            }
            ImGui.endTabBar();
            for (Path file : closed) {
                CodeEditor editor = editors.remove(file);
                if (editor.modified()) save(file, editor); // closing a tab never loses work
            }
        }
        tabToSelect = null;
        typed.clear(); // characters nobody used (no editor focused) are dropped
        ImGui.end();
    }
}
