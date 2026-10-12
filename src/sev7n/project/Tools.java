package sev7n.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs an outside program (tar, 7-Zip, Blender) with a time limit. */
final class Tools {

    /** What the program printed, and how it finished. */
    record Result(int exitCode, String output) {
        String lastLine() {
            return output.strip().lines().reduce((a, b) -> b).orElse("");
        }
    }

    private Tools() { }

    static Result run(List<String> command, long timeoutMinutes) throws IOException {
        // output goes to a file, so a stuck program can't block us and the time limit always works
        Path log = Files.createTempFile("sev7n-tool", ".log");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException(Path.of(command.get(0)).getFileName() + " took too long");
            }
            return new Result(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
