package sev7n.editor;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import javax.swing.JFileChooser;
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

    /** Windows' folder chooser; the folder arrives through 'onChosen' (nothing happens if it's cancelled). */
    static void chooseFolder(String title, Path startIn, Consumer<Path> onChosen) {
        EventQueue.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser(startIn == null ? null : startIn.toFile());
            chooser.setDialogTitle(title);
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) onChosen.accept(chooser.getSelectedFile().toPath());
        });
    }

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
