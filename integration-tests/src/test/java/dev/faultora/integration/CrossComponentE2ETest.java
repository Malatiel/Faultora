package dev.faultora.integration;

import dev.faultora.cli.FaultoraCli;
import dev.faultora.examples.recovery.PaymentRecoverySystem;
import dev.faultora.examples.recovery.SystemConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M3 exit gate: a business invariant proved across HTTP, events and a
 * database, through the packaged CLI, against real infrastructure.
 * <p>
 * Every scenario here runs twice. Once against the correct reference system,
 * where it must pass — and once against the system with exactly one property
 * removed, where it must fail. A reliability test that has never failed proves
 * nothing, and the second half of each test is the only thing that shows the
 * first half was measuring anything.
 * <p>
 * The database is a real PostgreSQL rather than the in-memory one the
 * connector's unit tests use. H2 proves the connector's bounds and refusals; it
 * cannot prove that a query runs, that a fetch size bounds anything, or that a
 * read-only grant is enough for the observations to work — and the events
 * release's most serious defect was invisible to every in-memory test that
 * covered it.
 * <p>
 * The suite skips itself when no container runtime is available, so an offline
 * build stays green — and says so out loud, because a skipped gate must never
 * read as a passed one.
 */
@EnabledIf("dockerIsAvailable")
class CrossComponentE2ETest {

    /** Seeds do not repeat, so no two runs in this class share a payment id. */
    private static final AtomicInteger SEEDS = new AtomicInteger(88000);

    private PaymentRecoverySystem system;

    @SuppressWarnings("unused")
    static boolean dockerIsAvailable() {
        if (RecoveryInfrastructure.dockerIsAvailable()) {
            return true;
        }
        System.err.println("Cross-component suite skipped: no container runtime. "
                + "The M3 exit gate is NOT covered by this build.");
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

    /**
     * Start one version of the reference system against an empty database.
     * <p>
     * The containers are shared by the whole class and the application is not:
     * every test gets the tables emptied and its own processes, so a variant
     * cannot inherit rows or a consumer from the test before it.
     */
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

    private int run(String scenario, String seed) throws IOException {
        // A fresh directory per run. Seeds restart with the JVM, so a reused
        // path would let an assertion about this run's journal be satisfied by
        // the previous suite's — an assertion that cannot fail.
        Path outputDir = Files.createTempDirectory("faultora-e2e-cross-" + seed + "-");
        lastOutputDir = outputDir;
        return new FaultoraCli(
                new PrintWriter(System.out, true), new PrintWriter(System.err, true))
                .run(new String[]{
                        "test",
                        "--scenario", RecoveryFixtures.scenario(scenario).toString(),
                        "--openapi", RecoveryFixtures.openApi().toString(),
                        "--asyncapi", RecoveryFixtures.asyncApi().toString(),
                        "--observations", RecoveryFixtures.observations().toString(),
                        "--target", system.apiBaseUrl(),
                        "--target", "broker=kafka://"
                                + RecoveryInfrastructure.bootstrapServers(),
                        "--target", "ledger=" + RecoveryInfrastructure.jdbcUrl(),
                        "--db-user", RecoveryInfrastructure.READ_ONLY_USER,
                        "--db-secret-id", RecoveryInfrastructure.SECRET_ID,
                        "--allow-private",
                        "--seed", seed,
                        "--format", "console,json",
                        "--output", outputDir.toString()
                });
    }

    private Path lastOutputDir;

    private String journal() throws IOException {
        return Files.readString(lastOutputDir.resolve("events.ndjson"));
    }

    private static String nextSeed() {
        return String.valueOf(SEEDS.incrementAndGet());
    }

    @Test
    void oneCommandOverHttpBooksALedgerThatBalances() throws Exception {
        start(SystemConfig.correct());

        int exit = run("settlement-invariant.yaml", nextSeed());

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
        // The database was read through the CLI, as the role that may only
        // read it, and the journal says what came back.
        assertThat(journal()).contains("ROWS_OBSERVED");
    }

    @Test
    void theSameScenarioFailsAgainstALedgerThatBooksOneSide() throws Exception {
        // The variant removes the second entry of each booking. Nothing else
        // changes: the request succeeds, the event arrives, and only the sum
        // of the ledger says that money went missing.
        start(SystemConfig.singleEntryLedger());

        int exit = run("settlement-invariant.yaml", nextSeed());

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        assertThat(journal()).contains("the-ledger-balances");
    }

    @Test
    void aCommandDeliveredTwiceHasOneBusinessEffect() throws Exception {
        start(SystemConfig.correct());

        int exit = run("duplicate-delivery.yaml", nextSeed());

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void theSameScenarioFailsAgainstAConsumerThatIsNotIdempotent() throws Exception {
        // The redelivery is booked a second time. The ledger still balances —
        // it is twice the correct ledger — so what catches it is the count of
        // settlements, which is the business effect rather than its arithmetic.
        start(SystemConfig.nonIdempotentConsumer());

        int exit = run("duplicate-delivery.yaml", nextSeed());

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        assertThat(journal()).contains("one-effect-per-command");
    }

    @Test
    void aPaymentIsBookedRatherThanCommittedAndForgotten() throws Exception {
        start(SystemConfig.correct());

        int exit = run("lost-event-detected.yaml", nextSeed());

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void theSameScenarioFailsWhenTheOutboxIsWrittenOutsideTheTransaction()
            throws Exception {
        // The payment is committed and its event never written. From the
        // caller's side the request succeeded; the ledger is where the loss is
        // visible, and the scenario's convergence budget is what reports it.
        start(SystemConfig.nonTransactionalOutbox());

        int exit = run("lost-event-detected.yaml", nextSeed());

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        assertThat(journal()).contains("booking-appears");
    }

    @Test
    void aChargeWhoseResponseWasLostIsReconciled() throws Exception {
        // The provider takes every charge and answers none of them. Nothing in
        // this system is broken; it simply has to find out what happened.
        start(SystemConfig.lostProviderResponse());

        int exit = run("reconciled-unknown-outcome.yaml", nextSeed());

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void theSameScenarioFailsWithNobodyToReconcile() throws Exception {
        start(SystemConfig.lostProviderResponseAndNoReconciliation());

        int exit = run("reconciled-unknown-outcome.yaml", nextSeed());

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        assertThat(journal()).contains("booking-appears");
    }
}
