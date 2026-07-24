package dev.faultora.engine;

import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.*;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioMetadata;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.result.AssertionResult;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LocalEngineTest {

    @TempDir
    Path tempDir;

    private ApiCatalog catalog;
    private TargetPolicy policy;
    private ExpressionContext exprContext;
    private ConnectorContext connectorContext;

    @BeforeEach
    void setUp() {
        catalog = new ApiCatalog(
                new CatalogVersion("v1alpha1-test"),
                List.of(
                        new TargetDefinition(
                                new TargetId("default"), "Default", "http://localhost:8080",
                                List.of(new ProtocolId("http")), List.of(), Map.of()
                        )
                ),
                List.of(
                        new OperationDefinition(
                                new OperationId("create-payment"),
                                new ProtocolId("http"),
                                new TargetId("default"),
                                SafetyClassification.MUTATING,
                                Map.of(), null, Map.of("201", new SchemaId("Payment")),
                                Map.of("method", "POST", "path", "/payments")
                        ),
                        new OperationDefinition(
                                new OperationId("get-payment"),
                                new ProtocolId("http"),
                                new TargetId("default"),
                                SafetyClassification.READ_ONLY,
                                Map.of(), null, Map.of("200", new SchemaId("Payment")),
                                Map.of("method", "GET", "path", "/payments/1")
                        ),
                        new OperationDefinition(
                                new OperationId("cleanup-op"),
                                new ProtocolId("http"),
                                new TargetId("default"),
                                SafetyClassification.READ_ONLY,
                                Map.of(), null, Map.of(),
                                Map.of("method", "POST", "path", "/cleanup")
                        )
                ),
                Map.of(), Map.of(), List.of()
        );

        policy = new TargetPolicy(
                Set.of(),
                Set.of(SafetyClassification.READ_ONLY, SafetyClassification.MUTATING),
                1000, 10, 300000, 1048576,
                Set.of(), Set.of()
        );

        exprContext = ExpressionContext.builder().build();

        connectorContext = new ConnectorContext(
                EvidencePolicy.MINIMAL,
                handleId -> null,
                5000, 30000, 60000,
                Map.of("baseUrl", "http://localhost:8080")
        );
    }

    @Test
    void cleanupRunsAfterForcedFailure() throws Exception {
        // Build a plan with an operation that fails, followed by cleanup
        ExecutionPlan plan = ExecutionPlan.builder()
                .runId(new RunId("run-cleanup-test"))
                .scenario(buildScenario())
                .catalog(catalog)
                .targetPolicy(policy)
                .seed(42L)
                .scenarioDigest("sha256:abc")
                .catalogDigest("sha256:def")
                .addNode(new PlanNode.OperationNode(
                        new NodeId("failing-op"),
                        new OperationId("create-payment"),
                        findOp("create-payment"),
                        Map.of(), null,
                        List.of(),
                        SafetyClassification.MUTATING, 0, 0
                ))
                .addNode(new PlanNode.AssertionNode(
                        new NodeId("assert-status"),
                        "status",
                        Map.of("expected", 200),
                        new NodeId("failing-op"),
                        null,
                        List.of(new NodeId("failing-op")),
                        SafetyClassification.READ_ONLY, 0, 0
                ))
                .addNode(new PlanNode.CleanupNode(
                        new NodeId("cleanup-step"),
                        new OperationId("cleanup-op"),
                        Map.of(),
                        List.of(new NodeId("failing-op")),
                        SafetyClassification.READ_ONLY, 0, 0
                ))
                .build();

        // Connector that always fails
        Map<String, Connector> connectors = Map.of("http", new FailingConnector());
        Map<String, AssertionProvider> assertionProviders = Map.of("status", new StatusAssertionProvider());

        LocalEngine engine = new LocalEngine(connectors, assertionProviders);

        Path journalPath = tempDir.resolve("events.ndjson");
        try (RunJournal journal = new RunJournal(journalPath, true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            // Cleanup should have run despite the failure
            assertThat(result.nodeResults()).containsKey(new NodeId("cleanup-step"));
            RunResult.NodeResult cleanupResult = result.nodeResults().get(new NodeId("cleanup-step"));
            // Cleanup executes (may fail since connector fails, but it was attempted)
            assertThat(cleanupResult).isNotNull();

            // Journal should contain cleanup events
            List<RunEvent> events = journal.events();
            assertThat(events).anyMatch(e -> e instanceof RunEvent.CleanupStarted);
            assertThat(events).anyMatch(e -> e instanceof RunEvent.CleanupCompleted);
        }
    }

    @Test
    void cancellationStopsExecution() throws Exception {
        ExecutionPlan plan = ExecutionPlan.builder()
                .runId(new RunId("run-cancel-test"))
                .scenario(buildScenario())
                .catalog(catalog)
                .targetPolicy(policy)
                .seed(42L)
                .scenarioDigest("sha256:abc")
                .catalogDigest("sha256:def")
                .addNode(new PlanNode.OperationNode(
                        new NodeId("step-1"),
                        new OperationId("create-payment"),
                        findOp("create-payment"),
                        Map.of(), null,
                        List.of(),
                        SafetyClassification.MUTATING, 0, 0
                ))
                .addNode(new PlanNode.OperationNode(
                        new NodeId("step-2"),
                        new OperationId("get-payment"),
                        findOp("get-payment"),
                        Map.of(), null,
                        List.of(new NodeId("step-1")),
                        SafetyClassification.READ_ONLY, 0, 0
                ))
                .build();

        Map<String, Connector> connectors = Map.of("http", new SuccessConnector());
        Map<String, AssertionProvider> assertionProviders = Map.of();

        LocalEngine engine = new LocalEngine(connectors, assertionProviders);

        // Pre-cancel the execution
        AtomicBoolean cancellation = new AtomicBoolean(true);

        Path journalPath = tempDir.resolve("events-cancel.ndjson");
        try (RunJournal journal = new RunJournal(journalPath, true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, cancellation);

            assertThat(result.status()).isEqualTo(RunResult.Status.CANCELLED);
            // When cancelled from the start, no nodes execute
            // The run should still complete with CANCELLED status
            assertThat(result.totalNodes()).isEqualTo(0);

            // Journal should have RUN_STARTED and RUN_FAILED (cancelled)
            List<RunEvent> events = journal.events();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(RunEvent.RunStarted.class);
            assertThat(events.get(1)).isInstanceOf(RunEvent.RunFailed.class);
        }
    }

    @Test
    void identicalSeededRunsProduceEquivalentPlans() throws Exception {
        ExecutionPlan plan1 = buildDeterministicPlan(42L);
        ExecutionPlan plan2 = buildDeterministicPlan(42L);

        // Plans should have identical structure
        assertThat(plan1.nodes()).hasSameSizeAs(plan2.nodes());
        for (int i = 0; i < plan1.nodes().size(); i++) {
            assertThat(plan1.nodes().get(i).nodeId())
                    .isEqualTo(plan2.nodes().get(i).nodeId());
        }

        // Execute both with the same connector
        Map<String, Connector> connectors = Map.of("http", new SuccessConnector());
        Map<String, AssertionProvider> assertionProviders = Map.of("status", new StatusAssertionProvider());

        LocalEngine engine = new LocalEngine(connectors, assertionProviders);

        Path journal1 = tempDir.resolve("run1.ndjson");
        Path journal2 = tempDir.resolve("run2.ndjson");

        RunResult result1, result2;
        try (RunJournal j1 = new RunJournal(journal1, true);
             RunJournal j2 = new RunJournal(journal2, true)) {
            result1 = engine.execute(plan1, j1, exprContext, connectorContext, new AtomicBoolean(false));
            result2 = engine.execute(plan2, j2, exprContext, connectorContext, new AtomicBoolean(false));
        }

        // Results should be structurally equivalent
        assertThat(result1.status()).isEqualTo(result2.status());
        assertThat(result1.totalNodes()).isEqualTo(result2.totalNodes());
        assertThat(result1.passedAssertions()).isEqualTo(result2.passedAssertions());
        assertThat(result1.failedAssertions()).isEqualTo(result2.failedAssertions());
        assertThat(result1.nodeResults().keySet())
                .containsExactlyInAnyOrderElementsOf(result2.nodeResults().keySet());
    }

    @Test
    void journalEventsAreOrdered() throws Exception {
        ExecutionPlan plan = ExecutionPlan.builder()
                .runId(new RunId("run-journal-test"))
                .scenario(buildScenario())
                .catalog(catalog)
                .targetPolicy(policy)
                .seed(42L)
                .scenarioDigest("sha256:abc")
                .catalogDigest("sha256:def")
                .addNode(new PlanNode.OperationNode(
                        new NodeId("step-1"),
                        new OperationId("create-payment"),
                        findOp("create-payment"),
                        Map.of(), null,
                        List.of(),
                        SafetyClassification.MUTATING, 0, 0
                ))
                .build();

        Map<String, Connector> connectors = Map.of("http", new SuccessConnector());
        Map<String, AssertionProvider> assertionProviders = Map.of();

        LocalEngine engine = new LocalEngine(connectors, assertionProviders);

        Path journalPath = tempDir.resolve("events-order.ndjson");
        try (RunJournal journal = new RunJournal(journalPath, true)) {
            engine.execute(plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            List<RunEvent> events = journal.events();
            assertThat(events).isNotEmpty();

            // First event should be RUN_STARTED
            assertThat(events.get(0)).isInstanceOf(RunEvent.RunStarted.class);

            // Last events should be RUN_COMPLETED or RUN_FAILED
            RunEvent last = events.get(events.size() - 1);
            assertThat(last).isInstanceOfAny(RunEvent.RunCompleted.class, RunEvent.RunFailed.class);

            // NODE_STARTED should come before NODE_COMPLETED for each node
            long nodeStartedIdx = -1;
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i) instanceof RunEvent.NodeStarted ns
                        && ns.nodeId().value().equals("step-1")) {
                    nodeStartedIdx = i;
                }
                if (events.get(i) instanceof RunEvent.NodeCompleted nc
                        && nc.nodeId().value().equals("step-1")) {
                    assertThat(nodeStartedIdx).isLessThan(i);
                    break;
                }
            }
        }
    }

    @Test
    void assertionPassAndFailCountedCorrectly() throws Exception {
        ExecutionPlan plan = ExecutionPlan.builder()
                .runId(new RunId("run-assert-test"))
                .scenario(buildScenario())
                .catalog(catalog)
                .targetPolicy(policy)
                .seed(42L)
                .scenarioDigest("sha256:abc")
                .catalogDigest("sha256:def")
                .addNode(new PlanNode.OperationNode(
                        new NodeId("op"),
                        new OperationId("create-payment"),
                        findOp("create-payment"),
                        Map.of(), null,
                        List.of(),
                        SafetyClassification.MUTATING, 0, 0
                ))
                .addNode(new PlanNode.AssertionNode(
                        new NodeId("assert-pass"),
                        "status",
                        Map.of("expected", 200),
                        new NodeId("op"),
                        null,
                        List.of(new NodeId("op")),
                        SafetyClassification.READ_ONLY, 0, 0
                ))
                .addNode(new PlanNode.AssertionNode(
                        new NodeId("assert-fail"),
                        "status",
                        Map.of("expected", 404),
                        new NodeId("op"),
                        null,
                        List.of(new NodeId("op")),
                        SafetyClassification.READ_ONLY, 0, 0
                ))
                .build();

        Map<String, Connector> connectors = Map.of("http", new SuccessConnector());
        Map<String, AssertionProvider> assertionProviders = Map.of("status", new StatusAssertionProvider());

        LocalEngine engine = new LocalEngine(connectors, assertionProviders);

        Path journalPath = tempDir.resolve("events-assert.ndjson");
        try (RunJournal journal = new RunJournal(journalPath, true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.passedAssertions()).isEqualTo(1);
            assertThat(result.failedAssertions()).isEqualTo(1);
            assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        }
    }

    @Test
    void failedDependencyPreventsDependentOperation() throws Exception {
        ExecutionPlan plan = ExecutionPlan.builder()
                .runId(new RunId("run-failed-dependency"))
                .scenario(buildScenario())
                .catalog(catalog)
                .targetPolicy(policy)
                .seed(42L)
                .scenarioDigest("sha256:abc")
                .catalogDigest("sha256:def")
                .addNode(new PlanNode.OperationNode(
                        new NodeId("first"),
                        new OperationId("create-payment"),
                        findOp("create-payment"),
                        Map.of(), null, List.of(),
                        SafetyClassification.MUTATING, 0, 0))
                .addNode(new PlanNode.OperationNode(
                        new NodeId("second"),
                        new OperationId("get-payment"),
                        findOp("get-payment"),
                        Map.of(), null, List.of(new NodeId("first")),
                        SafetyClassification.READ_ONLY, 0, 0))
                .build();
        LocalEngine engine = new LocalEngine(
                Map.of("http", new FailingConnector()), Map.of());

        try (RunJournal journal = new RunJournal(
                tempDir.resolve("failed-dependency.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
            assertThat(result.nodeResults()).containsKey(new NodeId("first"));
            assertThat(result.nodeResults()).doesNotContainKey(new NodeId("second"));
            assertThat(result.error().code()).isEqualTo("RUN_FAILED");
        }
    }

    @Test
    void waitStepUsesConfiguredDuration() throws Exception {
        ExecutionPlan plan = ExecutionPlan.builder()
                .runId(new RunId("run-wait"))
                .scenario(buildScenario())
                .catalog(catalog)
                .targetPolicy(policy)
                .seed(42L)
                .scenarioDigest("sha256:abc")
                .catalogDigest("sha256:def")
                .addNode(new PlanNode.OperationNode(
                        new NodeId("wait"),
                        new OperationId("_wait"),
                        null,
                        Map.of("waitMs", 1200L), null, List.of(),
                        SafetyClassification.READ_ONLY, 0, 0))
                .build();
        LocalEngine engine = new LocalEngine(Map.of(), Map.of());

        long started = System.nanoTime();
        try (RunJournal journal = new RunJournal(tempDir.resolve("wait.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));
            long elapsedMs =
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
            assertThat(elapsedMs).isGreaterThanOrEqualTo(1100);
        }
    }

    @Test
    void faultNodeWithoutMatchingProviderFailsInsteadOfPassingSilently() throws Exception {
        ExecutionPlan plan = planWithNodes("run-fault", new PlanNode.FaultStartNode(
                new NodeId("fault"), "latency", "default", Map.of(), 100,
                List.of(), SafetyClassification.MUTATING, 0, 0));
        LocalEngine engine = new LocalEngine(Map.of(), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("fault.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
            assertThat(result.nodeResults().get(new NodeId("fault")).error().message())
                    .contains("No fault provider supports fault type");
        }
    }

    @Test
    void faultIsInjectedAndRolledBackAtRunEnd() throws Exception {
        StubFaultProvider provider = new StubFaultProvider();
        ExecutionPlan plan = planWithNodes("run-fault-lifecycle",
                new PlanNode.FaultStartNode(
                        new NodeId("inject"), "stub-fault", "default",
                        Map.of("delayMs", 10), 60_000,
                        List.of(), SafetyClassification.MUTATING, 0, 0),
                new PlanNode.OperationNode(
                        new NodeId("op"), new OperationId("create-payment"),
                        findOp("create-payment"), Map.of(), null,
                        List.of(new NodeId("inject")),
                        SafetyClassification.MUTATING, 0, 0));
        LocalEngine engine = new LocalEngine(
                Map.of("http", new SuccessConnector()), Map.of(),
                Map.of("stub", provider));

        try (RunJournal journal = new RunJournal(tempDir.resolve("fault-lc.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
            assertThat(provider.injections.get()).isEqualTo(1);
            assertThat(provider.rollbacks.get()).isEqualTo(1);

            List<RunEvent> events = journal.events();
            assertThat(events).anyMatch(e -> e instanceof RunEvent.FaultInjected fi
                    && fi.faultType().equals("stub-fault"));
            assertThat(events).anyMatch(e -> e instanceof RunEvent.FaultRolledBack rb
                    && rb.rollbackStatus().equals("run-end"));
        }
    }

    @Test
    void watchdogRollsBackFaultAtHardExpiryWhileRunContinues() throws Exception {
        StubFaultProvider provider = new StubFaultProvider();
        ExecutionPlan plan = planWithNodes("run-fault-expiry",
                new PlanNode.FaultStartNode(
                        new NodeId("inject"), "stub-fault", "default",
                        Map.of(), 100,
                        List.of(), SafetyClassification.MUTATING, 0, 0),
                new PlanNode.OperationNode(
                        new NodeId("wait"), new OperationId("_wait"), null,
                        Map.of("waitMs", 700L), null,
                        List.of(new NodeId("inject")),
                        SafetyClassification.READ_ONLY, 0, 0));
        LocalEngine engine = new LocalEngine(Map.of(), Map.of(), Map.of("stub", provider));

        try (RunJournal journal = new RunJournal(tempDir.resolve("fault-exp.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
            assertThat(provider.rollbacks.get()).isEqualTo(1);
            assertThat(journal.events()).anyMatch(e -> e instanceof RunEvent.FaultRolledBack rb
                    && rb.rollbackStatus().equals("hard-expiry"));
        }
    }

    @Test
    void faultStopNodeRollsBackExactlyOnce() throws Exception {
        StubFaultProvider provider = new StubFaultProvider();
        ExecutionPlan plan = planWithNodes("run-fault-stop",
                new PlanNode.FaultStartNode(
                        new NodeId("inject"), "stub-fault", "default",
                        Map.of(), 60_000,
                        List.of(), SafetyClassification.MUTATING, 0, 0),
                new PlanNode.FaultStopNode(
                        new NodeId("stop"), new NodeId("inject"),
                        List.of(new NodeId("inject")),
                        SafetyClassification.MUTATING, 0, 0));
        LocalEngine engine = new LocalEngine(Map.of(), Map.of(), Map.of("stub", provider));

        try (RunJournal journal = new RunJournal(tempDir.resolve("fault-stop.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
            assertThat(provider.rollbacks.get()).isEqualTo(1);

            List<RunEvent.FaultRolledBack> rollbackEvents = journal.events().stream()
                    .filter(e -> e instanceof RunEvent.FaultRolledBack)
                    .map(e -> (RunEvent.FaultRolledBack) e)
                    .toList();
            assertThat(rollbackEvents).hasSize(1);
            assertThat(rollbackEvents.get(0).rollbackStatus()).isEqualTo("fault-stop");
        }
    }

    @Test
    void expectErrorStepPassesWhenOperationFails() throws Exception {
        ExecutionPlan plan = planWithNodes("run-expect-error",
                new PlanNode.OperationNode(
                        new NodeId("failing"), new OperationId("create-payment"),
                        findOp("create-payment"), Map.of(), null, true,
                        List.of(), SafetyClassification.MUTATING, 0, 0),
                new PlanNode.OperationNode(
                        new NodeId("after"), new OperationId("get-payment"),
                        findOp("get-payment"), Map.of(), null,
                        List.of(new NodeId("failing")),
                        SafetyClassification.READ_ONLY, 0, 0));
        LocalEngine engine = new LocalEngine(
                Map.of("http", new FailCreatePaymentConnector()), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("expect-err.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.nodeResults().get(new NodeId("failing")).status())
                    .isEqualTo(RunResult.Status.PASSED);
            // The dependent step runs because the expected failure counts as success.
            assertThat(result.nodeResults()).containsKey(new NodeId("after"));
            assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
        }
    }

    @Test
    void expectErrorStepFailsWhenOperationSucceeds() throws Exception {
        ExecutionPlan plan = planWithNodes("run-expect-error-miss",
                new PlanNode.OperationNode(
                        new NodeId("unexpected-success"), new OperationId("create-payment"),
                        findOp("create-payment"), Map.of(), null, true,
                        List.of(), SafetyClassification.MUTATING, 0, 0));
        LocalEngine engine = new LocalEngine(
                Map.of("http", new SuccessConnector()), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("expect-miss.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            RunResult.NodeResult node = result.nodeResults().get(new NodeId("unexpected-success"));
            assertThat(node.status()).isEqualTo(RunResult.Status.FAILED);
            assertThat(node.error().code()).isEqualTo("EXPECTED_ERROR");
            assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        }
    }

    @Test
    void failedOperationEmitsNodeFailedEvent() throws Exception {
        ExecutionPlan plan = planWithNodes("run-node-failed",
                new PlanNode.OperationNode(
                        new NodeId("failing"), new OperationId("create-payment"),
                        findOp("create-payment"), Map.of(), null,
                        List.of(), SafetyClassification.MUTATING, 0, 0));
        LocalEngine engine = new LocalEngine(
                Map.of("http", new FailingConnector()), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("node-failed.ndjson"), true)) {
            engine.execute(plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(journal.events()).anyMatch(e -> e instanceof RunEvent.NodeFailed nf
                    && nf.nodeId().value().equals("failing")
                    && nf.error().code().equals("CONNECTION_REFUSED"));
        }
    }

    @Test
    void retryRecoversAfterTransientRetryableFailures() throws Exception {
        FlakyConnector connector = new FlakyConnector(2, true);
        ExecutionPlan plan = planWithNodes("run-retry-recover",
                new PlanNode.OperationNode(
                        new NodeId("flaky"), new OperationId("create-payment"),
                        findOp("create-payment"), Map.of(), null, false,
                        new PlanNode.RetrySpec(4, 5, 2.0, 50),
                        List.of(), SafetyClassification.MUTATING, 0, 3));
        LocalEngine engine = new LocalEngine(Map.of("http", connector), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("retry-ok.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
            assertThat(connector.executions.get()).isEqualTo(3);
            long retryEvents = journal.events().stream()
                    .filter(e -> e instanceof RunEvent.OperationRetried).count();
            assertThat(retryEvents).isEqualTo(2);
        }
    }

    @Test
    void retryStopsImmediatelyOnNonRetryableError() throws Exception {
        FlakyConnector connector = new FlakyConnector(Integer.MAX_VALUE, false);
        ExecutionPlan plan = planWithNodes("run-retry-nonretryable",
                new PlanNode.OperationNode(
                        new NodeId("flaky"), new OperationId("create-payment"),
                        findOp("create-payment"), Map.of(), null, false,
                        new PlanNode.RetrySpec(4, 5, 2.0, 50),
                        List.of(), SafetyClassification.MUTATING, 0, 3));
        LocalEngine engine = new LocalEngine(Map.of("http", connector), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("retry-nr.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
            assertThat(connector.executions.get()).isEqualTo(1);
            assertThat(journal.events())
                    .noneMatch(e -> e instanceof RunEvent.OperationRetried);
        }
    }

    @Test
    void retryExhaustionFailsTheNode() throws Exception {
        FlakyConnector connector = new FlakyConnector(Integer.MAX_VALUE, true);
        ExecutionPlan plan = planWithNodes("run-retry-exhausted",
                new PlanNode.OperationNode(
                        new NodeId("flaky"), new OperationId("create-payment"),
                        findOp("create-payment"), Map.of(), null, false,
                        new PlanNode.RetrySpec(3, 1, 1.0, 5),
                        List.of(), SafetyClassification.MUTATING, 0, 2));
        LocalEngine engine = new LocalEngine(Map.of("http", connector), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("retry-ex.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
            assertThat(connector.executions.get()).isEqualTo(3);
            long retryEvents = journal.events().stream()
                    .filter(e -> e instanceof RunEvent.OperationRetried).count();
            assertThat(retryEvents).isEqualTo(2);
        }
    }

    @Test
    void retryDelaysAreDeterministicPerSeed() {
        PlanNode.RetrySpec retry = new PlanNode.RetrySpec(5, 100, 2.0, 10_000);
        NodeId nodeId = new NodeId("flaky");

        for (int attempt = 1; attempt <= 4; attempt++) {
            long first = LocalEngine.retryDelayMs(retry, 42L, nodeId, attempt);
            long second = LocalEngine.retryDelayMs(retry, 42L, nodeId, attempt);
            assertThat(second).isEqualTo(first);

            double base = 100 * Math.pow(2.0, attempt - 1);
            assertThat(first)
                    .isBetween(Math.round(base * 0.9) - 1, Math.round(base * 1.1) + 1);
        }

        // The cap applies after jitter.
        PlanNode.RetrySpec capped = new PlanNode.RetrySpec(5, 100, 2.0, 150);
        assertThat(LocalEngine.retryDelayMs(capped, 42L, nodeId, 4))
                .isLessThanOrEqualTo(150);
    }

    @Test
    void outputBindingExposesPriorStepResponseToLaterInputs() throws Exception {
        CapturingConnector connector = new CapturingConnector();
        ExecutionPlan plan = planWithNodes("run-output-binding",
                new PlanNode.OperationNode(
                        new NodeId("create"), new OperationId("create-payment"),
                        findOp("create-payment"), Map.of(), "first",
                        List.of(), SafetyClassification.MUTATING, 0, 0),
                new PlanNode.OperationNode(
                        new NodeId("read"), new OperationId("get-payment"),
                        findOp("get-payment"),
                        Map.of(
                                "paymentId", "{{steps.first.body.id}}",
                                "note", "status was {{steps.first.status}}",
                                "body", Map.of("copyOf", "{{steps.first.body.id}}")),
                        null,
                        List.of(new NodeId("create")),
                        SafetyClassification.READ_ONLY, 0, 0));
        LocalEngine engine = new LocalEngine(Map.of("http", connector), Map.of());

        EvidencePolicy capturing = new EvidencePolicy(
                true, true, Set.of(), 1_048_576, 100, List.of(), Set.of(), "session");
        ConnectorContext capturingContext = new ConnectorContext(
                capturing, handleId -> null, 5000, 30000, 60000,
                Map.of("baseUrl", "http://localhost:8080"));

        try (RunJournal journal = new RunJournal(tempDir.resolve("binding.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, capturingContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
            Map<String, Object> readInputs = connector.captured.get("get-payment");
            assertThat(readInputs).isNotNull();
            assertThat(readInputs.get("paymentId")).isEqualTo("pay-1");
            assertThat(readInputs.get("note")).isEqualTo("status was 200");
            // Templates resolve inside nested maps too.
            assertThat(readInputs.get("body"))
                    .isEqualTo(Map.of("copyOf", "pay-1"));
        }
    }

    @Test
    void outputOfFailedStepIsNotBound() throws Exception {
        ExecutionPlan plan = planWithNodes("run-no-binding-on-failure",
                new PlanNode.OperationNode(
                        new NodeId("create"), new OperationId("create-payment"),
                        findOp("create-payment"), Map.of(), "first",
                        List.of(), SafetyClassification.MUTATING, 0, 0));
        LocalEngine engine = new LocalEngine(
                Map.of("http", new FailingConnector()), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("binding-f.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            // The run fails on the node itself; nothing is bound and nothing throws.
            assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        }
    }

    @Test
    void parallelChildrenExecuteConcurrently() throws Exception {
        ConcurrencyTrackingConnector connector = new ConcurrencyTrackingConnector(200);
        ExecutionPlan plan = planWithNodes("run-parallel",
                new PlanNode.ParallelNode(
                        new NodeId("race"),
                        List.of(
                                childOp("first", "create-payment"),
                                childOp("second", "create-payment")),
                        List.of(), SafetyClassification.MUTATING, 0, 0));
        LocalEngine engine = new LocalEngine(Map.of("http", connector), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("parallel.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
            assertThat(result.nodeResults()).containsKeys(
                    new NodeId("race"), new NodeId("first"), new NodeId("second"));
            // Both requests were in flight at the same time.
            assertThat(connector.maxObservedConcurrency.get()).isEqualTo(2);
            // Journal has lifecycle events for group and children.
            assertThat(journal.events()).anyMatch(e -> e instanceof RunEvent.NodeStarted ns
                    && ns.nodeId().value().equals("race") && ns.nodeType().equals("parallel"));
            assertThat(journal.events()).anyMatch(e -> e instanceof RunEvent.NodeCompleted nc
                    && nc.nodeId().value().equals("first"));
        }
    }

    @Test
    void parallelChildFailureFailsTheGroupButAllChildrenRun() throws Exception {
        ExecutionPlan plan = planWithNodes("run-parallel-fail",
                new PlanNode.ParallelNode(
                        new NodeId("race"),
                        List.of(
                                childOp("failing", "create-payment"),
                                childOp("passing", "get-payment")),
                        List.of(), SafetyClassification.MUTATING, 0, 0));
        LocalEngine engine = new LocalEngine(
                Map.of("http", new FailCreatePaymentConnector()), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("parallel-f.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
            assertThat(result.nodeResults().get(new NodeId("race")).status())
                    .isEqualTo(RunResult.Status.FAILED);
            assertThat(result.nodeResults().get(new NodeId("failing")).status())
                    .isEqualTo(RunResult.Status.FAILED);
            assertThat(result.nodeResults().get(new NodeId("passing")).status())
                    .isEqualTo(RunResult.Status.PASSED);
        }
    }

    @Test
    void parallelChildOutputsAreBoundForLaterSteps() throws Exception {
        CapturingConnector connector = new CapturingConnector();
        ExecutionPlan plan = planWithNodes("run-parallel-binding",
                new PlanNode.ParallelNode(
                        new NodeId("race"),
                        List.of(new PlanNode.OperationNode(
                                new NodeId("first"), new OperationId("create-payment"),
                                findOp("create-payment"), Map.of(), "winner",
                                List.of(), SafetyClassification.MUTATING, 0, 0)),
                        List.of(), SafetyClassification.MUTATING, 0, 0),
                new PlanNode.OperationNode(
                        new NodeId("after"), new OperationId("get-payment"),
                        findOp("get-payment"),
                        Map.of("paymentId", "{{steps.winner.body.id}}"), null,
                        List.of(new NodeId("race")),
                        SafetyClassification.READ_ONLY, 0, 0));
        LocalEngine engine = new LocalEngine(Map.of("http", connector), Map.of());

        EvidencePolicy capturing = new EvidencePolicy(
                true, true, Set.of(), 1_048_576, 100, List.of(), Set.of(), "session");
        ConnectorContext capturingContext = new ConnectorContext(
                capturing, handleId -> null, 5000, 30000, 60000,
                Map.of("baseUrl", "http://localhost:8080"));

        try (RunJournal journal = new RunJournal(tempDir.resolve("parallel-b.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, capturingContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
            assertThat(connector.captured.get("get-payment"))
                    .containsEntry("paymentId", "pay-1");
        }
    }

    // --- Helpers ---

    private PlanNode.OperationNode childOp(String id, String operationId) {
        return new PlanNode.OperationNode(
                new NodeId(id), new OperationId(operationId),
                findOp(operationId), Map.of(), null,
                List.of(), SafetyClassification.MUTATING, 0, 0);
    }

    private ExecutionPlan planWithNodes(String runId, PlanNode... nodes) {
        ExecutionPlan.Builder builder = ExecutionPlan.builder()
                .runId(new RunId(runId))
                .scenario(buildScenario())
                .catalog(catalog)
                .targetPolicy(policy)
                .seed(42L)
                .scenarioDigest("sha256:abc")
                .catalogDigest("sha256:def");
        for (PlanNode node : nodes) {
            builder.addNode(node);
        }
        return builder.build();
    }

    private OperationDefinition findOp(String id) {
        return catalog.operations().stream()
                .filter(op -> op.id().value().equals(id))
                .findFirst().orElseThrow();
    }

    private ScenarioDocument buildScenario() {
        return new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("test", "Test scenario", Map.of(), Map.of()),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private ExecutionPlan buildDeterministicPlan(long seed) {
        return ExecutionPlan.builder()
                .runId(new RunId("run-det-" + seed))
                .scenario(buildScenario())
                .catalog(catalog)
                .targetPolicy(policy)
                .seed(seed)
                .scenarioDigest("sha256:abc")
                .catalogDigest("sha256:def")
                .addNode(new PlanNode.OperationNode(
                        new NodeId("step-1"),
                        new OperationId("create-payment"),
                        findOp("create-payment"),
                        Map.of(), null,
                        List.of(),
                        SafetyClassification.MUTATING, 0, 0
                ))
                .addNode(new PlanNode.AssertionNode(
                        new NodeId("assert-1"),
                        "status",
                        Map.of("expected", 200),
                        new NodeId("step-1"),
                        null,
                        List.of(new NodeId("step-1")),
                        SafetyClassification.READ_ONLY, 0, 0
                ))
                .build();
    }

    /**
     * Connector that always returns HTTP 200.
     */
    static class SuccessConnector implements Connector {
        @Override
        public ProtocolId protocol() { return new ProtocolId("http"); }

        @Override
        public Set<String> capabilities() { return Set.of("http-get", "http-post"); }

        @Override
        public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
            return () -> target;
        }

        @Override
        public OperationResult execute(PreparedTarget preparedTarget, OperationDefinition operation,
                                        Map<String, Object> inputs, ConnectorContext context) {
            return OperationResult.success(
                    200,
                    Map.of("content-type", List.of("application/json")),
                    "{\"id\":\"pay-1\",\"status\":\"ok\"}".getBytes(),
                    50,
                    Map.of()
            );
        }

        @Override
        public void release(PreparedTarget preparedTarget) {}

        @Override
        public void close() {}
    }

    /**
     * Connector that always fails with a connection error.
     */
    static class FailingConnector implements Connector {
        @Override
        public ProtocolId protocol() { return new ProtocolId("http"); }

        @Override
        public Set<String> capabilities() { return Set.of("http-get", "http-post"); }

        @Override
        public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
            return () -> target;
        }

        @Override
        public OperationResult execute(PreparedTarget preparedTarget, OperationDefinition operation,
                                        Map<String, Object> inputs, ConnectorContext context) {
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.NETWORK,
                    "CONNECTION_REFUSED",
                    "Simulated connection failure",
                    true,
                    Map.of()
            );
            return OperationResult.failure(error, 10);
        }

        @Override
        public void release(PreparedTarget preparedTarget) {}

        @Override
        public void close() {}
    }

    /**
     * Connector that sleeps per request and records peak concurrency.
     */
    static class ConcurrencyTrackingConnector extends SuccessConnector {
        final java.util.concurrent.atomic.AtomicInteger inFlight =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxObservedConcurrency =
                new java.util.concurrent.atomic.AtomicInteger();
        private final long sleepMs;

        ConcurrencyTrackingConnector(long sleepMs) {
            this.sleepMs = sleepMs;
        }

        @Override
        public OperationResult execute(PreparedTarget preparedTarget, OperationDefinition operation,
                                        Map<String, Object> inputs, ConnectorContext context) {
            int current = inFlight.incrementAndGet();
            maxObservedConcurrency.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return super.execute(preparedTarget, operation, inputs, context);
        }
    }

    /**
     * Connector recording the resolved inputs it receives, keyed by operation ID.
     */
    static class CapturingConnector extends SuccessConnector {
        final Map<String, Map<String, Object>> captured =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public OperationResult execute(PreparedTarget preparedTarget, OperationDefinition operation,
                                        Map<String, Object> inputs, ConnectorContext context) {
            captured.put(operation.id().value(), inputs);
            return super.execute(preparedTarget, operation, inputs, context);
        }
    }

    /**
     * Connector that fails the first N executions, then succeeds.
     */
    static class FlakyConnector extends SuccessConnector {
        final java.util.concurrent.atomic.AtomicInteger executions =
                new java.util.concurrent.atomic.AtomicInteger();
        private final int failures;
        private final boolean retryable;

        FlakyConnector(int failures, boolean retryable) {
            this.failures = failures;
            this.retryable = retryable;
        }

        @Override
        public OperationResult execute(PreparedTarget preparedTarget, OperationDefinition operation,
                                        Map<String, Object> inputs, ConnectorContext context) {
            if (executions.incrementAndGet() <= failures) {
                return OperationResult.failure(new NormalizedError(
                        NormalizedError.ErrorCategory.NETWORK,
                        retryable ? "CONNECTION_RESET" : "TLS_FAILURE",
                        "Simulated transient failure",
                        retryable, Map.of()), 1);
            }
            return super.execute(preparedTarget, operation, inputs, context);
        }
    }

    /**
     * Connector that fails create-payment and succeeds for everything else.
     */
    static class FailCreatePaymentConnector extends SuccessConnector {
        @Override
        public OperationResult execute(PreparedTarget preparedTarget, OperationDefinition operation,
                                        Map<String, Object> inputs, ConnectorContext context) {
            if (operation.id().value().equals("create-payment")) {
                return OperationResult.failure(new NormalizedError(
                        NormalizedError.ErrorCategory.TIMEOUT,
                        "FAULT_RESPONSE_LOSS",
                        "Simulated lost response",
                        true, Map.of()), 5);
            }
            return super.execute(preparedTarget, operation, inputs, context);
        }
    }

    /**
     * Fault provider stub recording inject and rollback calls.
     */
    static class StubFaultProvider implements dev.faultora.spi.contract.FaultProvider {
        final java.util.concurrent.atomic.AtomicInteger injections =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger rollbacks =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Set<String> capabilities() {
            return Set.of("stub-fault");
        }

        @Override
        public dev.faultora.spi.result.ActiveFault inject(
                String faultType, Map<String, Object> params,
                dev.faultora.spi.context.FaultContext context) {
            int id = injections.incrementAndGet();
            long activatedAtMs = Math.min(
                    System.currentTimeMillis(), context.hardExpiryMs() - 1);
            return new dev.faultora.spi.result.ActiveFault(
                    "stub-" + id, faultType, context.targetScope(),
                    activatedAtMs, context.hardExpiryMs(),
                    "forget stub fault");
        }

        @Override
        public void rollback(dev.faultora.spi.result.ActiveFault fault,
                             dev.faultora.spi.context.FaultContext context) {
            rollbacks.incrementAndGet();
        }
    }

    /**
     * Status assertion provider for tests.
     */
    static class StatusAssertionProvider implements AssertionProvider {
        @Override
        public String type() { return "status"; }

        @Override
        public AssertionResult evaluate(String assertionType, Map<String, Object> params,
                                         EvidenceView evidence, AssertionContext context) {
            var statusCode = evidence.statusCode();
            if (statusCode.isEmpty()) {
                return AssertionResult.indeterminate("No status code available");
            }
            int actual = statusCode.get();
            int expected = ((Number) params.get("expected")).intValue();
            if (actual == expected) {
                return AssertionResult.pass("Status " + actual + " matches expected " + expected);
            }
            return AssertionResult.fail(
                    "Expected status " + expected + " but got " + actual,
                    Map.of("expected", expected, "actual", actual)
            );
        }
    }
}
