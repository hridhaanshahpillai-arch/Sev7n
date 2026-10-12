package sev7n.project;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** What kind of file an asset is, decided by its extension. */
public enum AssetKind {

    TEXTURE("Picture", Set.of("png", "jpg", "jpeg", "bmp", "gif")),
    MODEL("Model", Set.of("glb", "gltf", "obj")),
    BLEND("Blender file", Set.of("blend")),
    FBX("FBX model", Set.of("fbx")),
    UNSUPPORTED_MODEL("Model (not supported yet)", Set.of("dae", "3ds", "stl", "ply")),
    SOUND("Sound", Set.of("wav", "ogg", "mp3", "flac")),
    ARCHIVE("Archive", Set.of("zip", "rar", "7z", "tar", "gz", "tgz")),
    MATERIAL("Material", Set.of("mtl")),
    SCRIPT("Script", Set.of("7n", "8s")),
    OTHER("File", Set.of());

    public final String label;
    private final Set<String> extensions;

    AssetKind(String label, Set<String> extensions) {
        this.label = label;
        this.extensions = extensions;
    }

    /** Files Blender can turn into a .glb model. */
    public boolean convertible() {
        return this == BLEND || this == FBX;
    }

    public static AssetKind of(Path file) {
        String extension = extension(file);
        for (AssetKind kind : values()) {
            if (kind.extensions.contains(extension)) return kind;
        }
        return OTHER;
    }

    /** "Tree.OBJ" -> "obj". */
    public static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** "Tree.obj" -> "Tree". */
    public static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
