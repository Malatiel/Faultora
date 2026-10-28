package dev.faultora.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Bounded host commands used by image qualification, with output kept off the pipe. */
final class ImageCommand {
    private ImageCommand() { }

    record Result(int exitValue, String output) { }

    static Result run(Path work, Path logs, int seconds, String... command) throws Exception {
        Path log = Files.createTempFile(logs, "image-command-", ".log");
        Process process = new ProcessBuilder(List.of(command)).directory(work.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                String said = Files.readString(log, StandardCharsets.UTF_8);
                throw new AssertionError("Image command exceeded " + seconds + " seconds: " + command[0]
                        + "\n" + said.substring(Math.max(0, said.length() - 4000)));
            }
            return new Result(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Image command did not stop");
                }
            }
        }
    }
}
