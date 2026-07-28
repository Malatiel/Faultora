package dev.faultora.integration;

import dev.faultora.cli.FaultoraCli;
import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reliability scenarios M2-05 asks for that concern recovery rather than
 * invariants: a target that restarts mid-run, and cleanup obligations that
 * survive a setup which only half succeeded.
 */
class RecoveryE2ETest {

    /** How long the target stays unreachable; the step's retries outlast it. */
    private static final long DOWNTIME_MS = 700;

    private PaymentApi api;

    @BeforeEach
    void startServer() throws IOException {
        api = new PaymentApi();
        api.start();
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.stop();
    }

    private int run(String scenarioName, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        return new FaultoraCli(new PrintWriter(System.out, true), new PrintWriter(System.err, true))
                .run(new String[]{
                        "test",
                        "--scenario", ExampleFixtures.scenario(scenarioName).toString(),
                        "--openapi", ExampleFixtures.openApi().toString(),
                        "--target", api.baseUrl(),
                        "--allow-private",
                        // The cleanup deletes what setup created, which the
                        // default policy withholds until it is asked for.
                        "--allow-destructive",
                        "--format", "console,json",
                        "--output", outputDir.toString()
                });
    }

    @Test
    void aRunSurvivesTheTargetRestartingUnderIt() throws IOException {
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-restart");

        // The target goes away before the scenario starts and comes back on
        // the same port while the step is retrying.
        api.restartAfter(DOWNTIME_MS);

        int exit = run("restart-recovery.yaml", outputDir);

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
        String events = Files.readString(outputDir.resolve("events.ndjson"));
        // The recovery has to be visible: a run that never noticed the outage
        // would prove nothing about surviving one.
        assertThat(events).contains("OPERATION_RETRIED");
    }

    @Test
    void cleanupDisposesOfWhatAHalfFinishedSetupCreated() throws IOException {
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-partial-setup");

        int exit = run("cleanup-after-partial-setup.yaml", outputDir);

        // Setup failed, so the run fails — and the cleanup still ran.
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        String events = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(events).contains("CLEANUP_STARTED");
        assertThat(events).contains("dispose-first");
        // Every cleanup obligation carried out, none of them failed.
        assertThat(events).contains("\"failed\":0");
        assertThat(events).contains("FAULT_ROLLED_BACK");
        // The wait belongs to cleanup: running it in the main phase would let
        // the outage expire before the step it is meant to interfere with.
        assertThat(events.indexOf("create-second"))
                .isLessThan(events.indexOf("wait-out-the-outage"));
    }
}
