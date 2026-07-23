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
    void unsupportedFaultNodeFailsInsteadOfPassingSilently() throws Exception {
        ExecutionPlan plan = ExecutionPlan.builder()
                .runId(new RunId("run-fault"))
                .scenario(buildScenario())
                .catalog(catalog)
                .targetPolicy(policy)
                .seed(42L)
                .scenarioDigest("sha256:abc")
                .catalogDigest("sha256:def")
                .addNode(new PlanNode.FaultStartNode(
                        new NodeId("fault"),
                        "latency",
                        "default",
                        Map.of(),
                        100,
                        List.of(),
                        SafetyClassification.MUTATING,
                        0,
                        0))
                .build();
        LocalEngine engine = new LocalEngine(Map.of(), Map.of());

        try (RunJournal journal = new RunJournal(tempDir.resolve("fault.ndjson"), true)) {
            RunResult result = engine.execute(
                    plan, journal, exprContext, connectorContext, new AtomicBoolean(false));

            assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
            assertThat(result.nodeResults().get(new NodeId("fault")).error().message())
                    .contains("not supported");
        }
    }

    // --- Helpers ---

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
