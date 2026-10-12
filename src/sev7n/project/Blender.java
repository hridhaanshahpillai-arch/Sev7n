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
        for (String dir : System.getenv().getOrDefault("PATH", "").split(";")) {
            if (!dir.isBlank()) candidates.add(Path.of(dir.trim(), "blender.exe"));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst();
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
