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
 * The system's own "Open" dialog. On Windows it runs on Java's UI thread; on macOS and Linux
 * it's the native dialog (through tinyfd), on a thread of its own. Either way the editor keeps
 * drawing while it's open, and the chosen files arrive through 'onChosen' (on that other thread).
 */
final class FilePicker {

    private FilePicker() { }

    /** The folder chooser; the folder arrives through 'onChosen' (nothing happens if it's cancelled). */
    static void chooseFolder(String title, Path startIn, Consumer<Path> onChosen) {
        if (!sev7n.Platform.windows()) {
            onOwnThread(() -> {
                String folder = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_selectFolderDialog(title, startIn == null ? "" : startIn.toString());
                if (folder != null && !folder.isBlank()) onChosen.accept(Path.of(folder));
            });
            return;
        }
        EventQueue.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser(startIn == null ? null : startIn.toFile());
            chooser.setDialogTitle(title);
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) onChosen.accept(chooser.getSelectedFile().toPath());
        });
    }

    static void chooseFiles(String title, Consumer<List<Path>> onChosen) {
        if (!sev7n.Platform.windows()) {
            onOwnThread(() -> {
                String chosen = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(title, System.getProperty("user.home") + "/", null, null, true);
                if (chosen == null || chosen.isBlank()) return;
                onChosen.accept(Arrays.stream(chosen.split("\\|")).filter(s -> !s.isBlank()).map(Path::of).toList()); // tinyfd separates files with |
            });
            return;
        }
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

    private static void onOwnThread(Runnable dialog) {
        Thread thread = new Thread(dialog, "sev7n-file-dialog");
        thread.setDaemon(true);
        thread.start();
    }
}
