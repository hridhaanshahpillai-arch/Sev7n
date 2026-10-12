package sev7n.project;

import sev7n.scene.Json;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Brings outside files into a project:
 *
 *   pictures (.png .jpg ...)   -> assets/textures/
 *   models (.glb .gltf .obj)   -> assets/models/Name/   (with the files they use: .bin, .mtl, textures)
 *   .blend and .fbx            -> assets/models/Name/   (converted to .glb by Blender, if installed)
 *   archives (.zip .rar .7z)   -> assets/Name/          (unpacked, then any .blend / .fbx inside is converted)
 *   folders                    -> assets/Name/
 *   scripts (.7n .8s)          -> scripts/
 *
 * Runs on a background thread; 'report' receives one friendly line per result.
 */
public final class AssetImporter {

    /** One line for the Console. */
    public record Message(boolean problem, String text) { }

    private final Project project;
    private final Consumer<Message> report;

    public AssetImporter(Project project, Consumer<Message> report) {
        this.project = project;
        this.report = report;
    }

    public void importAll(List<Path> sources) {
        for (Path source : sources) {
            try {
                importOne(source.toAbsolutePath().normalize());
            } catch (IOException | RuntimeException e) {
                problem("Couldn't import " + source.getFileName() + ": " + e.getMessage());
            }
        }
    }

    private void importOne(Path source) throws IOException {
        if (Files.isDirectory(source)) {
            Path target = unique(project.assetsFolder().resolve(source.getFileName().toString()));
            copyTree(source, target);
            ok("Imported folder " + project.relative(target));
            convertModelsIn(target);
            return;
        }
        if (!Files.isRegularFile(source)) throw new IOException("file not found");

        switch (AssetKind.of(source)) {
            case TEXTURE -> {
                Path target = copyInto(source, project.assetsFolder().resolve("textures"));
                ok("Imported picture " + project.relative(target));
            }
            case MODEL -> {
                Path target = switch (AssetKind.extension(source)) {
                    case "obj" -> importObj(source);
                    case "gltf" -> importGltf(source);
                    default -> copyInto(source, modelFolder(source));
                };
                ok("Imported model " + project.relative(target));
            }
            case BLEND, FBX -> {
                Path copy = copyInto(source, modelFolder(source));
                convert(copy);
            }
            case ARCHIVE -> {
                Path target = unique(project.assetsFolder().resolve(AssetKind.baseName(source)));
                Archives.extract(source, target);
                ok("Unpacked " + source.getFileName() + " into " + project.relative(target));
                convertModelsIn(target);
            }
            case SCRIPT -> {
                Path target = copyInto(source, project.scriptsFolder());
                ok("Imported script " + project.relative(target));
            }
            case SOUND -> {
                Path target = copyInto(source, project.assetsFolder().resolve("sounds"));
                ok("Imported sound " + project.relative(target) + " (playing sounds is coming in a later version)");
            }
            case UNSUPPORTED_MODEL -> {
                Path target = copyInto(source, project.assetsFolder().resolve("models"));
                problem("Copied " + project.relative(target) + ", but ." + AssetKind.extension(source)
                        + " models can't be shown. Export it as .glb (Blender: File > Export > glTF 2.0).");
            }
            case MATERIAL, OTHER -> {
                Path target = copyInto(source, project.assetsFolder().resolve("other"));
                ok("Copied " + project.relative(target));
            }
        }
    }

    // ---------------------------------------------------------------- models

    /** A new folder for one model: assets/models/Name/. */
    private Path modelFolder(Path model) throws IOException {
        Path folder = unique(project.assetsFolder().resolve("models").resolve(AssetKind.baseName(model)));
        Files.createDirectories(folder);
        return folder;
    }

    /** Copies a .gltf with the .bin and picture files it points at, keeping their relative paths. */
    private Path importGltf(Path gltf) throws IOException {
        Path folder = modelFolder(gltf);
        Files.copy(gltf, folder.resolve(gltf.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> json = Json.object(Json.parse(Files.readString(gltf, StandardCharsets.UTF_8)));
        for (String section : List.of("buffers", "images")) {
            for (Object item : Json.list(json, section)) {
                String uri = Json.string(Json.object(item), "uri", "");
                if (uri.isEmpty() || uri.startsWith("data:")) continue;
                Path file = gltf.resolveSibling(URLDecoder.decode(uri, StandardCharsets.UTF_8)).normalize();
                if (Files.isRegularFile(file)) copyRelative(gltf.getParent(), file, folder);
                else problem(gltf.getFileName() + " uses " + uri + ", which wasn't found next to it");
            }
        }
        return folder.resolve(gltf.getFileName());
    }

    /** Copies an .obj with the .mtl files it uses and the textures those mention, keeping relative paths. */
    private Path importObj(Path obj) throws IOException {
        Path folder = modelFolder(obj);
        Path sourceDir = obj.getParent();
        Files.copy(obj, folder.resolve(obj.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        for (String library : references(obj, "mtllib")) {
            Path mtl = sourceDir.resolve(library);
            if (!Files.isRegularFile(mtl)) continue;
            copyRelative(sourceDir, mtl, folder);
            for (String texture : references(mtl, "map_")) {
                // texture paths in a .mtl are relative to the .mtl's folder
                Path file = mtl.getParent().resolve(texture).normalize();
                if (Files.isRegularFile(file)) copyRelative(sourceDir, file, folder);
            }
        }
        return folder.resolve(obj.getFileName());
    }

    /**
     * Converts the .blend and .fbx files in a folder, skipping any that already come with
     * a ready model of the same name (packs often include both).
     */
    private void convertModelsIn(Path folder) throws IOException {
        List<Path> all;
        try (Stream<Path> files = Files.walk(folder)) {
            all = files.filter(Files::isRegularFile).toList();
        }
        // packs often ship the same model as .glb in one folder and .fbx / .blend in others
        Set<String> ready = new HashSet<>();
        for (Path file : all) {
            if (AssetKind.of(file) == AssetKind.MODEL) ready.add(AssetKind.baseName(file).toLowerCase());
        }
        List<Path> needed = all.stream()
                .filter(p -> AssetKind.of(p).convertible() && !ready.contains(AssetKind.baseName(p).toLowerCase()))
                .toList();
        if (needed.isEmpty()) return;
        if (Blender.find().isEmpty()) {
            problem(needed.size() + (needed.size() == 1 ? " model" : " models") + " in " + project.relative(folder)
                    + " (.blend / .fbx) need Blender to be converted. Install it free from " + Blender.DOWNLOAD_URL
                    + ", then right-click them in the Project panel > Convert to .glb with Blender");
            return;
        }
        for (Path source : needed) convert(source);
    }

    /** Asks Blender to turn a .blend or .fbx into a .glb next to it. */
    public void convert(Path source) {
        try {
            Path glb = Blender.convertToGlb(source);
            ok("Converted " + source.getFileName() + " to " + project.relative(glb));
        } catch (IOException e) {
            problem("Copied " + project.relative(source) + ", but couldn't convert it: " + e.getMessage());
        }
    }

    /** File names a model or material file points at, e.g. "mtllib tree.mtl" or "map_Kd bark.png". */
    private static Set<String> references(Path file, String keyword) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            String[] parts = line.split("\\s+");
            boolean matches = keyword.endsWith("_")
                    ? parts[0].toLowerCase().startsWith(keyword.toLowerCase()) && parts.length > 1
                    : parts[0].equals(keyword) && parts.length > 1;
            if (!matches) continue;
            String name = keyword.equals("mtllib") ? line.substring(keyword.length()).strip() : parts[parts.length - 1];
            found.add(name.replace('\\', '/'));
        }
        return found;
    }

    // ---------------------------------------------------------------- file helpers

    /** Copies a file into a folder, renaming it "name (1).ext" if the name is taken. */
    private static Path copyInto(Path file, Path folder) throws IOException {
        Files.createDirectories(folder);
        Path target = unique(folder.resolve(file.getFileName().toString()));
        Files.copy(file, target);
        return target;
    }

    /** Copies 'file' to the same relative place under 'targetRoot' as it has under 'sourceRoot'. */
    private static void copyRelative(Path sourceRoot, Path file, Path targetRoot) throws IOException {
        Path relative = file.startsWith(sourceRoot) ? sourceRoot.relativize(file) : file.getFileName();
        Path target = targetRoot.resolve(relative.toString());
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static void copyTree(Path source, Path target) throws IOException {
        List<Path> all;
        try (Stream<Path> files = Files.walk(source)) {
            all = new ArrayList<>(files.toList());
        }
        for (Path from : all) {
            Path to = target.resolve(source.relativize(from).toString());
            if (Files.isDirectory(from)) Files.createDirectories(to);
            else {
                Files.createDirectories(to.getParent());
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** "tree.png" -> "tree (1).png" if needed, so imports never overwrite existing files. */
    static Path unique(Path wanted) {
        if (!Files.exists(wanted)) return wanted;
        String name = wanted.getFileName().toString();
        int dot = Files.isDirectory(wanted) ? -1 : name.lastIndexOf('.');
        String base = dot <= 0 ? name : name.substring(0, dot);
        String extension = dot <= 0 ? "" : name.substring(dot);
        for (int i = 1; ; i++) {
            Path candidate = wanted.resolveSibling(base + " (" + i + ")" + extension);
            if (!Files.exists(candidate)) return candidate;
        }
    }

    private void ok(String text) {
        report.accept(new Message(false, text));
    }

    private void problem(String text) {
        report.accept(new Message(true, text));
    }
}
