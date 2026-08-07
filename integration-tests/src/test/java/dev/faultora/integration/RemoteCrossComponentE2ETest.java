package dev.faultora.integration;

import dev.faultora.engine.run.RunResult;
import dev.faultora.examples.recovery.PaymentRecoverySystem;
import dev.faultora.examples.recovery.SystemConfig;
import dev.faultora.runner.DispatchedRun;
import dev.faultora.runner.protocol.Dispatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M3 gate again, from a runner this time.
 * <p>
 * The same business invariant across HTTP, events and a database — proved by a
 * runner that dialled out for the work, compiled the scenario itself, and sent
 * back what it found. This is the bullet M4-04 states literally: <b>run the M2
 * and M3 suites through a runner</b>, and it reaches what nothing else does.
 * A dispatched run here opens a database as a named user whose password it
 * resolves from its own environment, which is the arrangement ADR-021 describes
 * and which was once described and not implemented — the dispatch carried no
 * credentials at all, and a runner connected to a database with no user.
 * <p>
 * Each scenario runs twice, correct and broken, for the reason the CLI suite
 * gives: a reliability test that has never failed proves nothing.
 * <p>
 * What is <em>not</em> asserted here is journal-for-journal equality with a
 * local run, which {@link RemoteRunE2ETest} does for the HTTP suite. These
 * scenarios converge by polling real infrastructure, so how many times a
 * condition was polled is a fact about the day rather than about the run.
 */
@EnabledIf("dockerIsAvailable")
class RemoteCrossComponentE2ETest {

    /** Seeds do not repeat, and do not overlap the CLI suite's. */
    private static final AtomicInteger SEEDS = new AtomicInteger(77000);

    private PaymentRecoverySystem system;

    @TempDir
    Path directory;

    @SuppressWarnings("unused")
    static boolean dockerIsAvailable() {
        if (RecoveryInfrastructure.dockerIsAvailable()) {
            return true;
        }
        System.err.println("Remote cross-component suite skipped: no container runtime. "
                + "The M3 suite is NOT proved through a runner by this build.");
        return false;
    }

    @BeforeAll
    static void startInfrastructure() throws Exception {
        RecoveryInfrastructure.start();
    }

    @AfterEach
    void stopSystem() {
        if (system != null) {
            system.close();
            system = null;
        }
    }

    private void start(SystemConfig config) throws Exception {
        system = new PaymentRecoverySystem(
                config, RecoveryInfrastructure.jdbcUrl(),
                RecoveryInfrastructure.databaseOwner(),
                RecoveryInfrastructure.databaseOwnerPassword(),
                RecoveryInfrastructure.bootstrapServers());
        system.createSchema(RecoveryInfrastructure.READ_ONLY_PASSWORD);
        system.emptyTables();
        system.start();
    }

    /**
     * Dispatch a scenario to a runner and wait for what it found.
     * <p>
     * The runner is handed the read-only password under the same handle the
     * scenario names, and the dispatch carries only that name. Nothing that has
     * been a credential crosses the wire.
     */
    private DispatchedRun.Outcome remotely(String scenarioFile, String runId)
            throws Exception {
        try (RemoteRunner runner = new RemoteRunner(
                directory.resolve(runId),
                Map.of(RecoveryInfrastructure.SECRET_ID,
                        RecoveryInfrastructure.READ_ONLY_PASSWORD))) {
            DispatchedRun.Outcome outcome = runner.run(new RemoteRunner.Request(
                    runId,
                    RecoveryFixtures.scenario(scenarioFile),
                    RemoteRunner.catalog(
                            RecoveryFixtures.openApi(),
                            RecoveryFixtures.asyncApi(),
                            RecoveryFixtures.observations()),
                    Map.of("", system.apiBaseUrl(),
                            "broker", "kafka://" + RecoveryInfrastructure.bootstrapServers(),
                            "ledger", RecoveryInfrastructure.jdbcUrl()),
                    new Dispatch.Credentials(
                            null, RecoveryInfrastructure.READ_ONLY_USER,
                            RecoveryInfrastructure.SECRET_ID),
                    SEEDS.incrementAndGet(), 60_000));

            assertThat(outcome.didRun())
                    .as(() -> "the runner refused: " + outcome.refusal()).isTrue();
            assertThat(runner.journalDelivered(runId))
                    .as("what the run learned reached the far side")
                    .isNotEmpty();
            assertThat(runner.faultsStillActive())
                    .as("nothing was left injected").isZero();
            return outcome;
        }
    }

    @Test
    void aRunnerProvesTheLedgerBalancesAcrossHttpEventsAndADatabase() throws Exception {
        start(SystemConfig.correct());

        DispatchedRun.Outcome outcome =
                remotely("settlement-invariant.yaml", "run-remote-settlement");

        assertThat(outcome.result().status()).isEqualTo(RunResult.Status.PASSED);
        // The database was read from the runner, as the role that may only read
        // it, with a password the dispatch never carried.
        assertThat(Files.readString(outcome.journalPath())).contains("ROWS_OBSERVED");
    }

    @Test
    void theSameRunnerSaysSoWhenTheLedgerBooksOneSide() throws Exception {
        // One property removed and nothing else: the request succeeds, the
        // event arrives, and only the sum of the ledger says money went
        // missing. If the runner could not read the database it would fail
        // here too — so the correct half above is what makes this one mean
        // something, and the other way round.
        start(SystemConfig.singleEntryLedger());

        DispatchedRun.Outcome outcome =
                remotely("settlement-invariant.yaml", "run-remote-single-entry");

        assertThat(outcome.result().status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(Files.readString(outcome.journalPath()))
                .contains("the-ledger-balances");
    }

    @Test
    void aRunnerFindsACommandDeliveredTwiceHasOneEffect() throws Exception {
        start(SystemConfig.correct());

        DispatchedRun.Outcome outcome =
                remotely("duplicate-delivery.yaml", "run-remote-duplicate");

        assertThat(outcome.result().status()).isEqualTo(RunResult.Status.PASSED);
    }

    @Test
    void theSameRunnerSaysSoWhenTheConsumerIsNotIdempotent() throws Exception {
        start(SystemConfig.nonIdempotentConsumer());

        DispatchedRun.Outcome outcome =
                remotely("duplicate-delivery.yaml", "run-remote-not-idempotent");

        assertThat(outcome.result().status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(Files.readString(outcome.journalPath()))
                .contains("one-effect-per-command");
    }
}
