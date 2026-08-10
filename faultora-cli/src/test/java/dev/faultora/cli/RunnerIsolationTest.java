package dev.faultora.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing listens.
 * <p>
 * The first line of the M4 exit gate — <em>no inbound connection into the
 * private network is required</em> — has been true by construction all along:
 * the runner is given an address to dial and no port of its own. By
 * construction is exactly the kind of property that stops being true quietly,
 * the day somebody adds a metrics endpoint because it seemed harmless, and
 * nothing else in the suite would notice.
 * <p>
 * So this asks the operating system rather than the source. A running runner is
 * inspected for listening TCP sockets and must have none — and the same
 * question is put to a process that <em>is</em> listening, because a check that
 * has never found anything proves nothing about the one that found nothing.
 * <p>
 * Skipped where {@code lsof} is absent, loudly: a gate that quietly did not run
 * reads exactly like a gate that passed.
 */
@EnabledIf("canSeeOpenSockets")
class RunnerIsolationTest {

    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1alpha1
            kind: Scenario
            metadata:
              name: listening-to-nobody
            execute:
              - id: pause
                type: wait
                timeout: 50ms
            """;

    @TempDir
    Path directory;

    @SuppressWarnings("unused")
    static boolean canSeeOpenSockets() {
        try {
            new ProcessBuilder("lsof", "-v").redirectErrorStream(true).start().waitFor();
            return true;
        } catch (Exception noTool) {
            System.err.println("Runner isolation test skipped: no lsof. "
                    + "That nothing listens is NOT checked by this build.");
            return false;
        }
    }

    @Test
    void aRunningRunnerHasNoListeningSocket() throws Exception {
        try (RunnerProcess runner = RunnerProcess.start(directory)) {
            runner.awaitRegistered();

            assertThat(listeningSocketsOf(runner.process().pid()))
                    .as("a runner is reached by nothing; it dials out and that is all")
                    .isEmpty();

            // And still none while it is working, which is when a diagnostics
            // endpoint would be most tempting to have added.
            runner.offer("run-unlistening", SCENARIO);
            runner.awaitRunUnderWay("run-unlistening");

            assertThat(listeningSocketsOf(runner.process().pid()))
                    .as("nor while a run is in flight")
                    .isEmpty();
        }
    }

    @Test
    void theCheckFindsAListeningSocketWhenThereIsOne() throws Exception {
        // The half that makes the half above mean something. This JVM hosts the
        // qualification dispatcher, which listens on purpose — if the check
        // cannot see that, it cannot see anything.
        try (RunnerProcess runner = RunnerProcess.start(directory)) {
            runner.awaitRegistered();

            assertThat(listeningSocketsOf(ProcessHandle.current().pid()))
                    .as("the dispatcher in this JVM is listening, and is found")
                    .isNotEmpty();
        }
    }

    /**
     * The TCP sockets a process is listening on.
     * <p>
     * {@code -a} is not optional: lsof combines its selectors with OR by
     * default, so without it this would report every listening socket on the
     * machine and the test would pass or fail for reasons that have nothing to
     * do with the runner.
     */
    private static List<String> listeningSocketsOf(long pid) throws Exception {
        Process lsof = new ProcessBuilder(
                "lsof", "-a", "-nP", "-p", String.valueOf(pid), "-iTCP", "-sTCP:LISTEN")
                .redirectErrorStream(true).start();
        String said = new String(lsof.getInputStream().readAllBytes());
        lsof.waitFor(30, TimeUnit.SECONDS);
        return said.lines()
                .filter(line -> line.contains("(LISTEN)"))
                .toList();
    }
}
