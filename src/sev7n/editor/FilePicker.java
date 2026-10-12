package sev7n.editor;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Windows' own "Open" dialog. It runs on Java's UI thread, so the editor keeps drawing
 * while it's open; the chosen files arrive through 'onChosen' (on that UI thread).
 */
final class FilePicker {

    private FilePicker() { }

    static void chooseFiles(String title, Consumer<List<Path>> onChosen) {
        EventQueue.invokeLater(() -> {
            FileDialog dialog = new FileDialog((Frame) null, title, FileDialog.LOAD);
            dialog.setMultipleMode(true);
            dialog.setAlwaysOnTop(true); // it has no parent window, so keep it in front of the editor
            dialog.setVisible(true);     // waits here until the user picks or cancels
            File[] files = dialog.getFiles();
            dialog.dispose();
            onChosen.accept(Arrays.stream(files).map(File::toPath).toList());
        });
    }
}
