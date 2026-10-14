package sev7n;

import org.lwjgl.system.Configuration;
import sev7n.editor.EditorApp;
import sev7n.player.GamePlayer;
import sev7n.project.Project;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Sev7n entry point.
 *
 *   sev7n                       open the editor on projects/MyGame
 *   sev7n path\to\Project       open the editor on that project folder
 *   sev7n path\to\Script.7n     open the script's project, with the script open (.8s graphs too)
 *   sev7n --play [Project]      play a game in its own window, no editor
 *         [--scene file]        ...starting from this scene file instead of the project's main.scene
 *
 * In an exported game (a game\ folder next to sev7n.jar) it just plays that game.
 *
 * For automated checks: --screenshot file.png [frames] [--test-play] [--test-select Name] [--test-guide SEVEN.md] [--test-tab Scene]
 * (with --play, only --screenshot file.png [frames]: saves the game's picture, then quits)
 */
public final class Sev7n {

    public static final String NAME = "Sev7n";
    public static final String VERSION = "0.6 alpha";

    private Sev7n() { }

    public static void main(String[] args) throws Exception {
        // LWJGL's scratch memory for native calls: Vulkan drivers can list hundreds of extensions
        Configuration.STACK_SIZE.set(512);

        // an exported game: the engine sits next to the game it should play
        Path engine = engineJar();
        Path exported = engine == null ? null : engine.getParent().resolve("game");
        if (exported != null && Files.exists(exported.resolve("main.scene"))) {
            GamePlayer.playExported(exported, engine.getParent().resolve("export.json"));
            return;
        }

        Path defaultProject = home().resolve(Path.of("projects", "MyGame"));

        if (args.length > 0 && args[0].equals("--play")) {
            boolean folderGiven = args.length > 1 && !args[1].startsWith("--");
            List<String> all = Arrays.asList(args);
            int sceneAt = all.indexOf("--scene");
            Path scene = sceneAt >= 0 && sceneAt + 1 < args.length ? Path.of(args[sceneAt + 1]) : null;
            EditorApp.TestRun test = parseTestRun(args);
            GamePlayer.play(folderGiven ? Path.of(args[1]) : defaultProject, scene,
                    test == null ? null : new GamePlayer.TestShot(test.screenshot(), test.frames()));
            return;
        }

        EditorApp.TestRun test = parseTestRun(args);
        Path target = args.length > 0 && !args[0].startsWith("--") ? Path.of(args[0]) : null;

        boolean behaviourFile = target != null && (target.toString().endsWith(".7n") || target.toString().endsWith(".8s"));
        if (behaviourFile && Files.isRegularFile(target)) {
            Path root = Project.findRoot(target);
            EditorApp.run(root != null ? root : defaultProject, target, test);
        } else if (target != null && Files.isDirectory(target)) {
            EditorApp.run(target, null, test);
        } else {
            EditorApp.run(defaultProject, null, test);
        }
    }

    /** The Sev7n folder (the one containing build\ and projects\). */
    public static Path home() {
        try {
            // running from build\sev7n.jar or build\classes: home is the folder above build\
            Path code = Path.of(Sev7n.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return code.getParent().getParent();
        } catch (URISyntaxException | NullPointerException e) {
            return Path.of("").toAbsolutePath();
        }
    }

    /** How to start another Sev7n (e.g. a game window): this Java, running this jar or these classes. */
    public static List<String> javaCommand() {
        // not this process's own program: in the Sev7n download that's Sev7n.exe, not Java
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        String java = Files.isRegularFile(bin.resolve("javaw.exe")) ? bin.resolve("javaw.exe").toString()
                : Files.isRegularFile(bin.resolve("java.exe")) ? bin.resolve("java.exe").toString()
                : ProcessHandle.current().info().command().orElse("java");
        Path jar = engineJar();
        return jar != null
                ? List.of(java, "-jar", jar.toString())
                : List.of(java, "-cp", System.getProperty("java.class.path"), Sev7n.class.getName());
    }

    /** The sev7n.jar this is running from, or null when running from compiled classes. */
    public static Path engineJar() {
        try {
            Path code = Path.of(Sev7n.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(code) && code.toString().endsWith(".jar") ? code : null;
        } catch (URISyntaxException | NullPointerException e) {
            return null;
        }
    }

    private static EditorApp.TestRun parseTestRun(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals("--screenshot") || i + 1 >= args.length) continue;
            int frames = i + 2 < args.length && args[i + 2].matches("\\d+") ? Integer.parseInt(args[i + 2]) : 60;
            List<String> all = Arrays.asList(args);
            boolean play = all.contains("--test-play");
            int selectAt = all.indexOf("--test-select");
            String select = selectAt >= 0 && selectAt + 1 < args.length ? args[selectAt + 1] : null;
            int guideAt = all.indexOf("--test-guide");
            String guide = guideAt >= 0 && guideAt + 1 < args.length ? args[guideAt + 1] : null;
            int tabAt = all.indexOf("--test-tab");
            String tab = tabAt >= 0 && tabAt + 1 < args.length ? args[tabAt + 1] : null;
            return new EditorApp.TestRun(Path.of(args[i + 1]), frames, play, select, guide, tab);
        }
        return null;
    }
}
