package sev7n.editor;

import sev7n.scene.Scene;
import sev7n.scene.SceneSerializer;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Undo and redo for scene edits (Ctrl+Z, Ctrl+Y).
 *
 * It keeps whole snapshots of the scene (as saved JSON), taken once an edit is finished: a
 * drag in the Inspector or with the gizmo is one step, not one per frame. Simple, and it
 * catches every kind of edit, whichever panel made it.
 */
final class UndoHistory {

    private static final int MAX_STEPS = 100;

    /** A scene as it was, and what was selected then. */
    private record Step(String scene, long selected) { }

    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();
    private Step current;

    /** Starts afresh (a scene was opened): no history, and this is the starting point. */
    void reset(Scene scene, long selected) {
        undo.clear();
        redo.clear();
        current = new Step(SceneSerializer.toJson(scene), selected);
    }

    /** If the scene changed since the last step, records the change. Call when no edit is in progress. */
    boolean record(Scene scene, long selected) {
        String now = SceneSerializer.toJson(scene);
        if (current == null) {
            current = new Step(now, selected);
            return false;
        }
        if (now.equals(current.scene)) {
            current = new Step(current.scene, selected); // the selection alone isn't an undo step
            return false;
        }
        undo.push(current);
        while (undo.size() > MAX_STEPS) undo.removeLast();
        redo.clear();
        current = new Step(now, selected);
        return true;
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    /** The scene one step back (and the selection then), or null if there's nothing to undo. */
    Restored undo() {
        if (undo.isEmpty()) return null;
        redo.push(current);
        current = undo.pop();
        return restored(redo.peek());
    }

    /** The scene one step forward again, or null. */
    Restored redo() {
        if (redo.isEmpty()) return null;
        undo.push(current);
        current = redo.pop();
        return restored(current);
    }

    /** A scene brought back, and which entity to select (what was selected when that edit was made). */
    record Restored(Scene scene, long selected) { }

    private Restored restored(Step selectionFrom) {
        return new Restored(SceneSerializer.fromJson(current.scene), selectionFrom.selected);
    }
}
