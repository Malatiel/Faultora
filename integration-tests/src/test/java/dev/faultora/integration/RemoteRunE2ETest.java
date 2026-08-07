package dev.faultora.integration;

import dev.faultora.cli.FaultoraCli;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.examples.payment.PaymentApi;
import dev.faultora.runner.DispatchedRun;
import dev.faultora.runner.protocol.Dispatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M2 suite, run through a runner and compared with the same run made here.
 * <p>
 * Two of the exit gate's four lines are what this measures. <b>Local and runner
 * modes produce the same normalized result model</b> — not both passing, which
 * is a check that cannot fail for the reason it exists, but the same steps, the
 * same responses and the same assertion outcomes. And <b>disconnection cannot
 * extend a run or an active fault beyond policy</b>, whose second half nothing
 * had yet touched: every dispatched run so far waited, so no fault had ever
 * been active when a lease ran out.
 * <p>
 * Nothing listens inside the private network here either. The runner dials the
 * dispatcher, over mutual TLS, on a real socket.
 */
class RemoteRunE2ETest {

    /** Same seed both ways: a difference in the journals is then a real one. */
    private static final long SEED = 20240807L;

    private PaymentApi api;

    @TempDir
    Path directory;

    @BeforeEach
    void startServer() throws IOException {
        api = new PaymentApi();
        api.start();
    }

    @AfterEach
    void stopServer() {
        if (api != null) {
            api.stop();
        }
    }

    /** Run a scenario the way an engineer does, and hand back its journal. */
    private Path locally(String scenarioFile, String seed) throws IOException {
        Path output = Files.createDirectory(directory.resolve("local-" + seed));
        new FaultoraCli(new PrintWriter(System.out, true), new PrintWriter(System.err, true))
                .run(new String[]{
                        "test",
                        "--scenario", ExampleFixtures.scenario(scenarioFile).toString(),
                        "--openapi", ExampleFixtures.openApi().toString(),
                        "--target", api.baseUrl(),
                        "--allow-private",
                        "--seed", seed,
                        "--format", "json",
                        "--output", output.toString()});
        return output.resolve("events.ndjson");
    }

    private RemoteRunner.Request dispatchOf(String runId, Path scenario, long leaseTtlMs) {
        return new RemoteRunner.Request(
                runId, scenario, Map.of("openapi", ExampleFixtures.openApi()),
                Map.of("", api.baseUrl()), Dispatch.Credentials.none(), SEED, leaseTtlMs);
    }

    @Test
    void theSmokeScenarioRunsOnARunnerAndProducesTheSameRun() throws Exception {
        Path local = locally("passing.yaml", String.valueOf(SEED));

        try (RemoteRunner runner = new RemoteRunner(directory.resolve("smoke"), Map.of())) {
            DispatchedRun.Outcome outcome = runner.run(dispatchOf(
                    "run-remote-smoke", ExampleFixtures.scenario("passing.yaml"), 60_000));

            assertThat(outcome.didRun())
                    .as(() -> "the runner refused: " + outcome.refusal()).isTrue();
            assertThat(outcome.result().status()).isEqualTo(RunResult.Status.PASSED);
            assertThat(outcome.leaseExpired()).isFalse();

            // The comparison, and it is made against the journal the dispatcher
            // received rather than the one on the runner's disk: a run whose
            // findings never crossed the wire has failed the gate from the
            // other side, and reading the local file would not notice.
            assertThat(NormalizedRun.of(runner.journalDelivered("run-remote-smoke")))
                    .as("the same steps, responses and assertion outcomes as a local run")
                    .isEqualTo(NormalizedRun.of(local));
            assertThat(runner.outcomeDelivered("run-remote-smoke")).contains("PASSED");
        }
    }

    @Test
    void aScenarioThatFailsHereFailsTheSameWayOnARunner() throws Exception {
        // The half that shows the half above was measuring something. A
        // comparison of two passing runs would agree just as readily if the
        // runner had stopped evaluating assertions altogether.
        Path local = locally("failing.yaml", String.valueOf(SEED));

        try (RemoteRunner runner = new RemoteRunner(directory.resolve("failing"), Map.of())) {
            DispatchedRun.Outcome outcome = runner.run(dispatchOf(
                    "run-remote-failing", ExampleFixtures.scenario("failing.yaml"), 60_000));

            assertThat(outcome.result().status()).isEqualTo(RunResult.Status.FAILED);
            assertThat(outcome.result().failedAssertions()).isEqualTo(1);
            assertThat(NormalizedRun.of(runner.journalDelivered("run-remote-failing")))
                    .as("a failure travels as the same failure")
                    .isEqualTo(NormalizedRun.of(local));
        }
    }

    @Test
    void theComparisonNoticesADifferenceOfOneField() throws Exception {
        // The two comparisons above assert equality, and equality is what a
        // broken comparison hands out for free: a signature built from field
        // names matching nothing is empty for every event, and both tests
        // still pass while measuring nothing.
        //
        // Two different scenarios would not settle it either — their journals
        // differ in length, so even empty signatures compare unequal. What
        // settles it is one journal against a copy of itself with a single
        // field changed: same events, same order, one assertion that passed
        // now recorded as failed.
        List<String> journal = Files.readAllLines(locally("passing.yaml", "1"));
        List<String> doctored = journal.stream()
                .map(line -> line.replace("\"outcome\":\"PASS\"", "\"outcome\":\"FAIL\""))
                .toList();

        assertThat(doctored).hasSameSizeAs(journal).isNotEqualTo(journal);
        assertThat(NormalizedRun.of(journal))
                .as("an assertion outcome is part of what is compared")
                .isNotEqualTo(NormalizedRun.of(doctored));
    }

    @Test
    void aRunnerKeepsWhatTheSignedPolicySaysItMayKeep() throws Exception {
        // The dispatcher can now say how much of what a run sees may be held,
        // and the way to show it lands is to watch an assertion lose the thing
        // it reads. The scenario's jsonpath runs over the response body: with
        // bodies kept it passes, and with a policy that keeps none it comes back
        // indeterminate — not passing quietly, which is the distinction the
        // whole evidence model turns on.
        Path scenario = ExampleFixtures.scenario("passing.yaml");
        EvidencePolicy keepsNoBodies = new EvidencePolicy(
                false, true, Set.of(), 0, 1000, List.of(), Set.of(), "session");

        try (RemoteRunner runner = new RemoteRunner(directory.resolve("evidence"), Map.of())) {
            DispatchedRun.Outcome kept = runner.run(new RemoteRunner.Request(
                    "run-remote-keeping", scenario,
                    Map.of("openapi", ExampleFixtures.openApi()),
                    Map.of("", api.baseUrl()), Dispatch.Credentials.none(),
                    SEED, 60_000, null));
            DispatchedRun.Outcome withheld = runner.run(new RemoteRunner.Request(
                    "run-remote-withheld", scenario,
                    Map.of("openapi", ExampleFixtures.openApi()),
                    Map.of("", api.baseUrl()), Dispatch.Credentials.none(),
                    SEED, 60_000, keepsNoBodies));

            assertThat(outcomeOf(kept, "jsonpath"))
                    .as("a dispatch that said nothing keeps what a local run keeps")
                    .isEqualTo("PASS");
            assertThat(outcomeOf(withheld, "jsonpath"))
                    .as("with no body kept there is nothing to read, and an "
                            + "assertion that cannot be evaluated says so")
                    .isEqualTo("INDETERMINATE");
        }
    }

    /**
     * How a run's assertion of one type came out, read from its journal.
     * <p>
     * By type rather than by id because an evaluated assertion is journalled
     * without its id — the same limitation {@link NormalizedRun} records.
     */
    private static String outcomeOf(DispatchedRun.Outcome outcome, String assertionType)
            throws IOException {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        for (String line : Files.readAllLines(outcome.journalPath())) {
            var event = mapper.readTree(line);
            if ("ASSERTION_EVALUATED".equals(event.path("eventType").asText())
                    && assertionType.equals(event.path("assertionType").asText())) {
                return event.path("outcome").asText();
            }
        }
        throw new AssertionError("no " + assertionType + " assertion was evaluated in "
                + Files.readString(outcome.journalPath()));
    }

    @Test
    void aFaultDoesNotOutliveTheRunItsLeaseEnded() throws Exception {
        // A fault good for a minute, work that would take half of one, and a
        // dispatcher that stops answering part-way through. The lease is not
        // renewed, so it runs out and the run stops on the runner's own clock
        // — and the point is what happens to the fault, because a fault still
        // injected after its run is gone is the failure the whole arrangement
        // exists to prevent. Nothing here is told to stop.
        Path scenario = directory.resolve("outlives-its-lease.yaml");
        Files.writeString(scenario, """
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata:
                  name: fault-outliving-its-lease
                faults:
                  - id: hold-a-fault
                    faultType: http-latency
                    targetScope: "*"
                    duration: 60s
                    params:
                      delayMs: 10
                execute:
                  - id: create-payment
                    type: operation
                    operationId: create-payment
                    dependsOn: [hold-a-fault]
                    inputs:
                      body:
                        amount: 1000
                        currency: EUR
                  - id: keep-going
                    type: wait
                    timeout: 30s
                    dependsOn: [create-payment]
                """);

        try (RemoteRunner runner = new RemoteRunner(directory.resolve("fault"), Map.of())) {
            new Thread(() -> {
                try {
                    Thread.sleep(4_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                runner.stopAnsweringHeartbeats();
            }, "the-far-side-goes-quiet").start();

            long startedAt = System.currentTimeMillis();
            DispatchedRun.Outcome outcome =
                    runner.run(dispatchOf("run-remote-fault", scenario, 2_000));
            long took = System.currentTimeMillis() - startedAt;

            assertThat(outcome.didRun())
                    .as(() -> "the runner refused: " + outcome.refusal()).isTrue();
            assertThat(outcome.leaseExpired())
                    .as("the lease is what ended it, not the work running out").isTrue();
            assertThat(took)
                    .as("it was still going after four seconds, which a two-second "
                            + "lease allows only while something keeps renewing it")
                    .isGreaterThan(4_000);
            assertThat(took)
                    .as("and it stopped within a lease of losing contact, with "
                            + "most of the thirty seconds left to wait")
                    .isLessThan(15_000);

            assertThat(runner.faultsStillActive())
                    .as("the fault had fifty-eight seconds left and is gone anyway")
                    .isZero();
            String journal = Files.readString(outcome.journalPath());
            assertThat(journal).contains("FAULT_INJECTED", "FAULT_ROLLED_BACK");

            // And it said so. Only the heartbeat went unanswered, so the run
            // that stopped for want of permission still had somewhere to
            // deliver what it had found — which is the half of the gate that
            // says stopping tidily and telling nobody is also a failure.
            assertThat(runner.journalDelivered("run-remote-fault"))
                    .as("what the run learned before it stopped reached the far side")
                    .isNotEmpty();
            assertThat(runner.outcomeDelivered("run-remote-fault"))
                    .as("and the far side was told why it stopped")
                    .contains("leaseExpired\":true");
        }
    }
}
