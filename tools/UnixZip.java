import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Zips a folder so macOS and Linux keep its scripts runnable (Windows' own zip forgets that).
 * Used by release.ps1:  java tools/UnixZip.java <folder> <file.zip> <runnable file names...>
 */
public class UnixZip {
    /** Programs inside the bundled Java and Blender: anything in a bin or MacOS folder, and a few known helpers. */
    static boolean looksRunnable(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return path.contains("/bin/") || path.contains("/MacOS/") || file.equals("jspawnhelper") || file.equals("jexec")
                || file.equals("blender") || file.equals("blender-softwaregl") || file.equals("blender-launcher")
                || file.endsWith(".sh") || file.endsWith(".command") || file.matches("python3(\\.\\d+)?");
    }

    public static void main(String[] args) throws IOException {
        Path folder = Path.of(args[0]).toAbsolutePath();
        Path zip = Path.of(args[1]).toAbsolutePath();
        List<String> runnable = List.of(args).subList(2, args.length);
        Files.deleteIfExists(zip);
        URI uri = URI.create("jar:" + zip.toUri());
        try (FileSystem out = FileSystems.newFileSystem(uri, Map.of("create", "true", "enablePosixFileAttributes", "true"));
             Stream<Path> files = Files.walk(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = folder.getParent().relativize(file).toString().replace('\\', '/');
                Path target = out.getPath(name);
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
                    continue;
                }
                Files.copy(file, target);
                boolean run = runnable.contains(file.getFileName().toString()) || looksRunnable(name);
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(run ? "rwxr-xr-x" : "rw-r--r--"));
            }
        }
    }
}
