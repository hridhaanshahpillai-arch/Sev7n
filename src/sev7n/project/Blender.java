package sev7n.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Converts .blend and .fbx files to .glb models by running Blender in the background.
 *
 * .blend is Blender's own working format and changes with every Blender version, and FBX
 * is a closed format, so the reliable way to read them is to ask Blender to export the
 * open glTF format, which keeps materials and textures. Blender is free: blender.org
 */
public final class Blender {

    public static final String DOWNLOAD_URL = "https://www.blender.org/download/";

    /** Opens (or imports) the file and exports everything as one .glb with textures inside. */
    private static final String EXPORT_SCRIPT = """
            import bpy, sys
            source, out = sys.argv[sys.argv.index('--') + 1:][:2]
            if source.lower().endswith('.fbx'):
                bpy.ops.wm.read_factory_settings(use_empty=True)
                bpy.ops.import_scene.fbx(filepath=source)
            else:
                bpy.ops.wm.open_mainfile(filepath=source)
            bpy.ops.export_scene.gltf(filepath=out, export_format='GLB', export_apply=True)
            """;

    private Blender() { }

    /** Looks for Blender in the usual places: Program Files, the Microsoft Store/user install, Steam, and PATH. */
    public static Optional<Path> find() {
        List<Path> candidates = new ArrayList<>();
        // the Blender that comes with Sev7n (in its blender folder) comes first
        Path bundled = sev7n.Sev7n.home().resolve("blender");
        candidates.add(bundled.resolve("blender.exe"));
        candidates.add(bundled.resolve("blender"));
        candidates.add(bundled.resolve("Blender.app/Contents/MacOS/Blender"));
        Optional<Path> own = candidates.stream().filter(Files::isRegularFile).findFirst();
        if (own.isPresent()) return own;
        if (sev7n.Platform.mac()) {
            Optional<Path> unpacked = installBundledMac(bundled);
            if (unpacked.isPresent()) return unpacked;
        }
        candidates.clear();
        String localAppData = System.getenv().getOrDefault("LOCALAPPDATA", "");
        for (Path folder : List.of(Path.of("C:\\Program Files\\Blender Foundation"),
                Path.of(localAppData, "Programs", "Blender Foundation"))) {
            if (!Files.isDirectory(folder)) continue;
            try (Stream<Path> versions = Files.list(folder)) {
                versions.map(v -> v.resolve("blender.exe")).filter(Files::exists).forEach(candidates::add);
            } catch (IOException ignored) {
                // unreadable folder: skip it
            }
        }
        candidates.sort(Comparator.comparing(Path::toString).reversed()); // newest version first
        candidates.add(Path.of("C:\\Program Files (x86)\\Steam\\steamapps\\common\\Blender\\blender.exe"));
        // a Mac keeps it inside the app; Linux installs it as plain "blender" (often from the package manager or Snap)
        candidates.add(Path.of("/Applications/Blender.app/Contents/MacOS/Blender"));
        candidates.add(Path.of(System.getProperty("user.home"), "Applications/Blender.app/Contents/MacOS/Blender"));
        candidates.add(Path.of("/snap/bin/blender"));
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!dir.isBlank()) candidates.add(Path.of(dir.trim(), sev7n.Platform.program("blender")));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }

    /**
     * The Mac download carries Blender as the .dmg it comes in (it can't be unpacked on the PC that
     * makes the download). The first time it's needed, it's copied out of the .dmg into the blender folder.
     */
    private static synchronized Optional<Path> installBundledMac(Path folder) {
        Path app = folder.resolve("Blender.app/Contents/MacOS/Blender");
        if (Files.isRegularFile(app)) return Optional.of(app);
        Path dmg;
        try (Stream<Path> files = Files.list(folder)) {
            dmg = files.filter(f -> f.toString().endsWith(".dmg")).findFirst().orElse(null);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (dmg == null) return Optional.empty();
        try {
            Path mount = Files.createTempDirectory("sev7n-blender");
            Tools.Result attached = Tools.run(List.of("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", mount.toString(), dmg.toString()), 5);
            if (attached.exitCode() != 0) return Optional.empty();
            try {
                Tools.run(List.of("cp", "-R", mount.resolve("Blender.app").toString(), folder.toString()), 10);
            } finally {
                Tools.run(List.of("hdiutil", "detach", mount.toString(), "-quiet"), 2);
            }
            if (Files.isRegularFile(app)) {
                Files.deleteIfExists(dmg); // unpacked: the .dmg isn't needed any more
                return Optional.of(app);
            }
        } catch (IOException ignored) {
            // falls back to an installed Blender, if there is one
        }
        return Optional.empty();
    }

    /** Converts a .blend or .fbx to a .glb next to it. Returns the .glb path. */
    public static Path convertToGlb(Path source) throws IOException {
        Path blender = find().orElseThrow(() -> new IOException(
                "Blender isn't installed. Get it free from " + DOWNLOAD_URL + " and import again, "
                        + "or export the model as .glb (Blender: File > Export > glTF 2.0) and import that."));
        Path out = source.resolveSibling(AssetKind.baseName(source) + ".glb");
        Path script = Files.createTempFile("sev7n-export", ".py");
        try {
            Files.writeString(script, EXPORT_SCRIPT, StandardCharsets.UTF_8);
            Tools.Result result = Tools.run(List.of(blender.toString(), "--background", "--factory-startup",
                    "--python", script.toString(), "--", source.toString(), out.toString()), 5);
            if (result.exitCode() != 0 || !Files.exists(out)) {
                throw new IOException("Blender couldn't convert " + source.getFileName() + ": " + result.lastLine());
            }
            return out;
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
