package dev.faultora.runner;

import dev.faultora.engine.LocalEngine;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioMetadata;
import dev.faultora.spi.context.ConnectorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of the release gate that is about disconnection.
 * <p>
 * <em>Disconnection cannot extend a run or an active fault beyond policy.</em>
 * The lease is how that is met, and the property only means something against a
 * run that is actually executing — so this drives the real engine, lets the
 * lease run out, and checks that the run ended and its cleanup happened. No
 * message from anywhere is involved, because the case a lease exists for is the
 * case where nothing can be heard.
 */
class LeaseWatchTest {

    @TempDir
    Path tempDir;

    private static ExecutionPlan planOfWaits(int waits, long eachMs) {
        ExecutionPlan.Builder plan = ExecutionPlan.builder()
                .runId(new RunId("run-lease"))
                .scenario(new ScenarioDocument(
                        "faultora.dev/v1alpha1", "Scenario",
                        new ScenarioMetadata("lease", null, Map.of(), Map.of()),
                        Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null))
                .catalog(new ApiCatalog(
                        new CatalogVersion("sha256:catalog"), List.of(), List.of(),
                        Map.of(), Map.of(), List.of()))
                .targetPolicy(new TargetPolicy(
                        Set.of(), Set.of(SafetyClassification.READ_ONLY),
                        100, 1, 600_000, 1024, Set.of(), Set.of()))
                .seed(42L)
                .scenarioDigest("sha256:scenario")
                .catalogDigest("sha256:catalog");

        NodeId previous = null;
        for (int index = 0; index < waits; index++) {
            NodeId nodeId = new NodeId("wait-" + index);
            plan.addNode(new PlanNode.WaitNode(nodeId, eachMs,
                    previous == null ? List.of() : List.of(previous),
                    SafetyClassification.READ_ONLY));
            previous = nodeId;
        }
        plan.addNode(new PlanNode.CleanupNode(
                new NodeId("cleanup"), new OperationId("nothing"), Map.of(),
                previous == null ? List.of() : List.of(previous),
                SafetyClassification.READ_ONLY, 0, 0));
        return plan.build();
    }

    @Test
    void aLeaseRunningOutStopsTheRunWithoutAnybodySayingSo() throws Exception {
        // Twenty seconds of work under a lease worth half a second. Nothing
        // sends a cancellation; the runner's own clock does.
        AtomicBoolean cancellation = new AtomicBoolean(false);
        long receivedAt = System.currentTimeMillis();
        Lease lease = new Lease(receivedAt, 500, 100);

        RunResult result;
        try (LeaseWatch watch = new LeaseWatch(lease, receivedAt, cancellation);
             RunJournal journal = new RunJournal(tempDir.resolve("events.ndjson"), true)) {
            watch.start();
            result = new LocalEngine(Map.of(), Map.of()).execute(
                    planOfWaits(40, 500), journal, ExpressionContext.builder().build(),
                    connectorContext(), cancellation);

            assertThat(watch.hasExpired())
                    .as("the lease is what ended this, not the work running out")
                    .isTrue();
            assertThat(journal.events())
                    .as("cleanup still ran: a bounded run is not an abandoned one")
                    .anyMatch(event -> event instanceof RunEvent.CleanupCompleted);
        }

        assertThat(System.currentTimeMillis() - receivedAt)
                .as("twenty seconds of work stopped inside a five-second margin")
                .isLessThan(5_000);
        assertThat(result.status()).isNotEqualTo(RunResult.Status.PASSED);
    }

    @Test
    void aRenewalGrantedBeforeExpiryExtendsTheRun() {
        AtomicBoolean cancellation = new AtomicBoolean(false);
        long receivedAt = System.currentTimeMillis();

        try (LeaseWatch watch = new LeaseWatch(
                new Lease(receivedAt, 400, 100), receivedAt, cancellation)) {
            watch.start();
            watch.renew(new Lease(receivedAt, 5_000, 100), System.currentTimeMillis());

            sleep(700);

            assertThat(watch.hasExpired()).isFalse();
            assertThat(cancellation.get()).isFalse();
            assertThat(watch.remainingMs()).isGreaterThan(0);
        }
    }

    @Test
    void aRenewalArrivingAfterTheRunStoppedDoesNotRestartIt() {
        // A lease that turns up late cannot un-cancel a run: the faults it
        // rolled back are already rolled back, and resuming would be executing
        // under a permission that had lapsed.
        AtomicBoolean cancellation = new AtomicBoolean(false);
        long receivedAt = System.currentTimeMillis();

        try (LeaseWatch watch = new LeaseWatch(
                new Lease(receivedAt, 200, 100), receivedAt, cancellation)) {
            watch.start();
            sleep(500);
            assertThat(watch.hasExpired()).isTrue();

            watch.renew(new Lease(System.currentTimeMillis(), 60_000, 1_000),
                    System.currentTimeMillis());

            assertThat(cancellation.get()).isTrue();
            assertThat(watch.hasExpired()).isTrue();
        }
    }

    private static ConnectorContext connectorContext() {
        return new ConnectorContext(
                EvidencePolicy.MINIMAL, handleId -> null, 5000, 30000, 60000,
                Map.of("baseUrl", "http://localhost:8080"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
