package dev.faultora.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a signal does to a runner with work in flight.
 * <p>
 * This one starts a real process and really signals it, because the thing under
 * test is a shutdown hook and a hook cannot be exercised from inside the JVM
 * that would have to survive it. A hook that only set a flag would let the JVM
 * exit mid-run — the run stops, nobody is told, and the journal sits in a
 * container that is about to be discarded. ADR-020 names that failure: a run
 * that ended tidily and lost what it learned has failed from the other side.
 * <p>
 * Both halves are here. With a grace period the outcome arrives; with one too
 * short to matter it does not, which is what shows the waiting is doing the
 * work rather than the run happening to be quick.
 */
class RunnerShutdownTest {

    /** Long enough that a signal always lands while the run is going. */
    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1alpha1
            kind: Scenario
            metadata:
              name: still-running-when-the-signal-comes
            execute:
              - id: pause
                type: wait
                timeout: 3s
            """;

    @TempDir
    Path directory;

    @Test
    void aSignalLetsTheRunInFlightFinishAndReport() throws Exception {
        try (RunnerProcess runner = RunnerProcess.start(
                directory, "--shutdown-grace", "30s")) {
            runner.offer("run-signalled", SCENARIO);
            runner.awaitRunUnderWay("run-signalled");

            runner.process().destroy();

            assertThat(runner.process().waitFor(60, TimeUnit.SECONDS))
                    .as("it stopped rather than ignoring the signal").isTrue();
            assertThat(runner.dispatcher().outcomeOf("run-signalled"))
                    .as("the run finished and said so before the process went")
                    .contains("PASSED");
        }
    }

    @Test
    void aGraceTooShortToMatterLosesWhatTheRunFound() throws Exception {
        // The half that shows the half above is measuring the waiting. Same
        // signal at the same moment, and the only difference is that nothing
        // waits — so the process goes while the run is still going, and what it
        // had learned never reaches anybody.
        try (RunnerProcess runner = RunnerProcess.start(
                directory, "--shutdown-grace", "1ms")) {
            runner.offer("run-cut-off", SCENARIO);
            runner.awaitRunUnderWay("run-cut-off");

            runner.process().destroy();

            assertThat(runner.process().waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(runner.dispatcher().outcomeOf("run-cut-off"))
                    .as("nothing reported it, which is what the grace period prevents")
                    .isNull();
        }
    }
}
