package sev7n.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Unpacks archives. Zips are handled by Java itself. Other formats (.rar, .7z, .tar...) use
 * 7-Zip if it's installed, otherwise Windows' built-in tar, which reads .rar and .7z on Windows 11.
 */
final class Archives {

    private static final Path SEVEN_ZIP = Path.of("C:\\Program Files\\7-Zip\\7z.exe");

    private Archives() { }

    static void extract(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        if (AssetKind.extension(archive).equals("zip")) unzip(archive, destination);
        else extractWithTool(archive, destination);
    }

    private static void unzip(Path zip, Path destination) throws IOException {
        try {
            unzip(zip, destination, StandardCharsets.UTF_8);
        } catch (ZipException | IllegalArgumentException e) {
            unzip(zip, destination, Charset.forName("IBM437")); // older zips name files in the DOS code page
        }
    }

    private static void unzip(Path zip, Path destination, Charset names) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        try (ZipFile file = new ZipFile(zip.toFile(), names)) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = root.resolve(entry.getName()).normalize();
                // "zip slip": never let an archive write outside its own folder
                if (!target.startsWith(root)) throw new IOException("unsafe path in archive: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Unpacks in a short temporary folder first, because Windows tar can't handle long paths
     * (over 260 characters), then moves the files into place with Java, which can.
     */
    private static void extractWithTool(Path archive, Path destination) throws IOException {
        Path work = Files.createTempDirectory("sev7n-unpack");
        try {
            Path copy = work.resolve("archive." + AssetKind.extension(archive));
            Path out = Files.createDirectory(work.resolve("out"));
            Files.copy(archive, copy);
            List<String> command = Files.exists(SEVEN_ZIP)
                    ? List.of(SEVEN_ZIP.toString(), "x", "-y", "-o" + out, copy.toString())
                    : List.of("tar.exe", "-xf", copy.toString(), "-C", out.toString());
            Tools.Result result = Tools.run(command, 10);
            if (result.exitCode() != 0) {
                String tool = command.get(0).endsWith("7z.exe") ? "7-Zip" : "Windows tar";
                throw new IOException(tool + " couldn't unpack it: " + result.lastLine());
            }
            moveTree(out, destination);
        } finally {
            deleteTree(work);
        }
    }

    private static void moveTree(Path from, Path to) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(from)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            Path target = to.resolve(from.relativize(file).toString()).normalize();
            if (!target.startsWith(to.toAbsolutePath().normalize())) continue; // never write outside the destination
            Files.createDirectories(target.getParent());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteTree(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // it's only a temporary folder
        }
    }
}
