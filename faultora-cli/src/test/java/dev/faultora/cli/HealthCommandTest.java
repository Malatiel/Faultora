package dev.faultora.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a probe learns about a runner it cannot connect to.
 * <p>
 * The distinction these tests exist for is between live and ready, and getting
 * it wrong is not a cosmetic mistake: a liveness probe that asked about the
 * control plane would restart every runner in a fleet at the same moment,
 * during the outage, for a condition restarting cannot fix.
 */
class HealthCommandTest {

    @TempDir
    Path directory;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int probe(String... args) {
        return new HealthCommand(new PrintWriter(out, true), new PrintWriter(err, true))
                .execute(List.of(args));
    }

    private Path statusOf(String state, boolean registered, long writtenAt)
            throws Exception {
        Path file = directory.resolve("health.json");
        Files.writeString(file, "{\"state\":\"" + state + "\",\"runnerId\":\"runner-1\","
                + "\"updatedAtEpochMs\":" + writtenAt + ",\"registered\":" + registered
                + ",\"currentRunId\":null,\"runsServed\":4}");
        return file;
    }

    @Test
    void aRunnerThatWroteAMomentAgoIsAlive() throws Exception {
        Path status = statusOf("WAITING", true, System.currentTimeMillis());

        assertThat(probe("--file", status.toString())).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(out.toString()).contains("runner-1", "WAITING", "4 served");
    }

    @Test
    void aRunnerThatHasWrittenNothingForTooLongIsNot() throws Exception {
        Path status = statusOf("WAITING", true, System.currentTimeMillis() - 600_000);

        assertThat(probe("--file", status.toString()))
                .isEqualTo(FaultoraCli.EXIT_RUNNER_FAILURE);
        assertThat(err.toString()).contains("last wrote");
    }

    @Test
    void anUnreachableControlPlaneIsNotSomethingToRestartOver() throws Exception {
        // The one that matters. A runner dialling a control plane that is not
        // answering is working correctly; restarting it changes nothing and
        // would take out a whole fleet at once.
        Path status = statusOf("REGISTERING", false, System.currentTimeMillis());

        assertThat(probe("--file", status.toString()))
                .as("liveness says nothing about the far side")
                .isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(probe("--file", status.toString(), "--require-registered"))
                .as("and readiness does, which is information rather than a restart")
                .isEqualTo(FaultoraCli.EXIT_RUNNER_FAILURE);
        assertThat(err.toString()).contains("Not ready", "REGISTERING");
    }

    @Test
    void aStartingRunnerIsAliveBeforeItHasReachedAnybody() throws Exception {
        // Written before the first registration attempt on purpose: a startup
        // probe reading a file that does not exist yet would kill the container
        // while it was still dialling.
        Path status = statusOf("STARTING", false, System.currentTimeMillis());

        assertThat(probe("--file", status.toString())).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void aStatusFileThatIsNotThereIsNotHealthy() {
        assertThat(probe("--file", directory.resolve("nothing.json").toString()))
                .isEqualTo(FaultoraCli.EXIT_RUNNER_FAILURE);
        assertThat(err.toString()).contains("could not be read");
    }

    @Test
    void theRunnerWritesWhatTheProbeReads() throws Exception {
        // The two halves are written and read by different classes, which is
        // exactly how a field gets renamed on one side only.
        Path file = directory.resolve("written.json");
        try (RunnerHealth health = RunnerHealth.reporting(file, "runner-2")) {
            assertThat(probe("--file", file.toString()))
                    .as("a file exists before anything has been attempted")
                    .isEqualTo(FaultoraCli.EXIT_PASS);
            assertThat(probe("--file", file.toString(), "--require-registered"))
                    .isEqualTo(FaultoraCli.EXIT_RUNNER_FAILURE);

            health.waiting();

            assertThat(probe("--file", file.toString(), "--require-registered"))
                    .as("registered, and the probe can see it")
                    .isEqualTo(FaultoraCli.EXIT_PASS);

            health.running("run-77");
            assertThat(RunnerHealth.read(file).currentRunId()).isEqualTo("run-77");
        }
    }
}
