package dev.faultora.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A runner that stops reporting has stopped writing.
 * <p>
 * close() used to interrupt the ticker and return while it could still be in
 * the middle of a write, so the health file or its partial sibling appeared
 * after whoever closed it had moved on. CI saw it as a temporary directory
 * that could not be deleted because something was still writing into it.
 */
class RunnerHealthTest {

    @TempDir
    Path directory;

    @Test
    void nothingIsWrittenAfterCloseReturns() throws Exception {
        // A zero interval keeps the ticker writing continuously, so a close
        // that does not wait for it is caught in the act rather than by luck.
        for (int attempt = 0; attempt < 200; attempt++) {
            Path home = Files.createDirectory(directory.resolve("runner-" + attempt));
            Path file = home.resolve("health.json");
            RunnerHealth health = RunnerHealth.reporting(file, "runner-" + attempt, 0);
            health.waiting();

            health.close();

            assertThat(home.resolve("health.json.partial"))
                    .as("attempt %d: no write is left half-done", attempt).doesNotExist();
            assertThat(RunnerHealth.read(file).state())
                    .as("attempt %d: the file a probe reads is complete", attempt)
                    .isEqualTo(RunnerHealth.Status.WAITING);
            Files.delete(file);
            Thread.sleep(2);
            try (Stream<Path> left = Files.list(home)) {
                assertThat(left).as("attempt %d: nothing reappears once closed", attempt).isEmpty();
            }
        }
    }

    @Test
    void reportingAfterCloseIsIgnoredRatherThanRecreatingTheFile() throws Exception {
        Path file = directory.resolve("health.json");
        RunnerHealth health = RunnerHealth.reporting(file, "runner-late", 60_000);
        health.close();
        Files.delete(file);

        // A shutdown hook can report STOPPING after the serve loop has closed
        // the reporter; that must not bring the file back.
        health.stopping();

        assertThat(file).doesNotExist();
    }
}
