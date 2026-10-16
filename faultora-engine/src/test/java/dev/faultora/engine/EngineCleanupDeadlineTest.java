package dev.faultora.engine;

import dev.faultora.engine.exec.ManualTime;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioMetadata;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.result.ActiveFault;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class EngineCleanupDeadlineTest {
    @TempDir
    Path directory;
    private final ManualTime time = new ManualTime();
    private final CleanupConnector connector = new CleanupConnector();
    private final RollbackProvider faults = new RollbackProvider();
    private final AtomicBoolean cancellation = new AtomicBoolean();
    private long cleanupBudget = 250;
    private long operationBudget = 5000;
    private int journalIndex;
    private boolean useDefault;

    @BeforeEach
    void warmUp() throws Exception {
        assertThat(run(5000, operation("warm")).status()).isEqualTo(RunResult.Status.PASSED);
        connector.budgets.clear();
        connector.operations.clear();
    }

    @Test
    void existingConstructorsGiveCleanupAFiniteDefaultBudget() throws Exception {
        useDefault = true;
        assertThat(run(5000, cleanup("cleanup", "fast", 0)).status()).isEqualTo(RunResult.Status.PASSED);
        assertThat(LocalEngine.DEFAULT_CLEANUP_TIMEOUT_MS).isEqualTo(30_000);
        assertThat(connector.budgets.getFirst().totalTimeoutMs()).isLessThanOrEqualTo(operationBudget);
        // A larger connector budget cannot widen the engine's cleanup ceiling.
        operationBudget = 60_000;
        connector.budgets.clear();
        assertThat(run(5000, cleanup("cleanup", "fast", 0)).status()).isEqualTo(RunResult.Status.PASSED);
        assertThat(connector.budgets.getFirst().totalTimeoutMs()).isBetween(25_000L, 30_000L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void anUnboundedCleanupConfigurationIsRejected(long budget) {
        assertThatThrownBy(() -> new LocalEngine(Map.of("http", connector), Map.of(), Map.of(), budget))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
    }

    @Test
    void severalCleanupOperationsConsumeOneBudgetAndRemainingObligationsFail() throws Exception {
        connector.delayMs = 100;
        long started = time.elapsedMillis();
        RunResult result = run(5000, cleanup("a", "slow", 0), cleanup("b", "slow", 0),
                cleanup("c", "slow", 0), cleanup("d", "slow", 0));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(connector.operations).hasSize(3);
        assertShrinkingBudgets(250);
        assertExpired(result, "c", "d");
        assertThat(result.error().metadata().get("cleanupFailures")).isEqualTo(2);
        assertBounded(started);
        assertThat(journal()).contains("CLEANUP_STARTED", "CLEANUP_COMPLETED", "RUN_FAILED");
    }

    @Test
    void aCleanupWaitConsumesTheSameBudgetAndPreventsTheNextRequest() throws Exception {
        long started = time.elapsedMillis();
        RunResult result = run(5000, cleanupWait(), cleanup("after", "fast", 0));

        assertThat(connector.operations).isEmpty();
        assertExpired(result, "wait", "after");
        assertThat(result.error().metadata().get("cleanupFailures")).isEqualTo(2);
        assertBounded(started);
    }

    @Test
    void cleanupGetsItsOwnBudgetAfterTheMainPhaseHasRunLongerThanThatBudget() throws Exception {
        cleanupBudget = 150;
        RunResult result = run(5000, new PlanNode.WaitNode(new NodeId("main-wait"), 250,
                        List.of(), SafetyClassification.READ_ONLY),
                cleanup("cleanup", "fast", 0));

        assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
        assertThat(connector.budgets.getFirst().totalTimeoutMs()).isEqualTo(150);
    }

    @Test
    void cleanupStillExecutesAfterTheMainDeadline() throws Exception {
        RunResult result = run(80, new PlanNode.WaitNode(new NodeId("main-wait"), 5000,
                        List.of(), SafetyClassification.READ_ONLY),
                cleanup("cleanup", "fast", 0));

        assertThat(result.error().code()).isEqualTo("SCENARIO_DEADLINE_EXCEEDED");
        assertThat(result.nodeResults().get(new NodeId("cleanup")).status()).isEqualTo(RunResult.Status.PASSED);
        assertThat(connector.operations).containsExactly("fast");
        assertThat(connector.budgets.getFirst().totalTimeoutMs()).isGreaterThan(80);
    }

    @Test
    void cancellationDoesNotPreventCleanupButDoesNotGiveEachStepAFreshBudget() throws Exception {
        cancellation.set(true);
        connector.delayMs = 100;
        RunResult result = run(5000, operation("main"), cleanup("a", "slow", 0),
                cleanup("b", "slow", 0), cleanup("c", "slow", 0), cleanup("d", "slow", 0));

        assertThat(result.status()).isEqualTo(RunResult.Status.CANCELLED);
        assertThat(connector.operations).containsExactly("slow", "slow", "slow");
        assertShrinkingBudgets(250);
        assertExpired(result, "c", "d");
    }

    @Test
    void aShorterNodeTimeoutDoesNotExhaustTheWholeCleanupPhase() throws Exception {
        connector.delayMs = 1000;
        RunResult result = run(5000, cleanup("short", "slow", 60), cleanup("after", "fast", 0));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.nodeResults().get(new NodeId("short")).error().category())
                .isEqualTo(NormalizedError.ErrorCategory.TIMEOUT);
        assertThat(result.nodeResults().get(new NodeId("after")).status()).isEqualTo(RunResult.Status.PASSED);
        assertThat(connector.budgets.getFirst().totalTimeoutMs()).isLessThanOrEqualTo(60);
        assertThat(connector.budgets.getLast().totalTimeoutMs()).isGreaterThan(100);
        assertThat(result.error().metadata().get("cleanupFailures")).isEqualTo(1);
    }

    @Test
    void aShorterConnectorTimeoutRemainsInForceDuringCleanup() throws Exception {
        operationBudget = 60;
        connector.delayMs = 1000;
        RunResult result = run(5000, cleanup("short", "slow", 0), cleanup("after", "fast", 0));

        assertThat(result.nodeResults().get(new NodeId("after")).status()).isEqualTo(RunResult.Status.PASSED);
        assertThat(connector.budgets).allSatisfy(context ->
                assertThat(context.totalTimeoutMs()).isPositive().isLessThanOrEqualTo(60));
    }

    @Test
    void successfulCleanupOperationsAndWaitsWithinTheBudgetKeepTheRunPassed() throws Exception {
        RunResult result = run(5000, cleanup("before", "fast", 0),
                new PlanNode.WaitNode(new NodeId("wait"), 20, List.of(), SafetyClassification.READ_ONLY, true),
                cleanup("after", "fast", 0));

        assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
        assertThat(connector.operations).containsExactly("fast", "fast");
        assertShrinkingBudgets(250);
        assertThat(journal()).contains("CLEANUP_COMPLETED", "RUN_COMPLETED").doesNotContain("NODE_FAILED");
    }

    @Test
    void aLateSuccessfulCleanupCallbackCannotTurnAnExpiredPhaseIntoAPass() throws Exception {
        cleanupBudget = 60;
        connector.delayMs = 120;
        connector.ignoreBudget = true;
        RunResult result = run(5000, cleanup("late", "slow", 0), cleanup("after", "fast", 0));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(connector.operations).containsExactly("slow");
        assertExpired(result, "late", "after");
    }

    @Test
    void aFailedCleanupStepDoesNotPreventFollowingStepsWithinTheBudget() throws Exception {
        connector.throwOnSlow = true;
        RunResult result = run(5000, cleanup("failed", "slow", 0), cleanup("after", "fast", 0));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.nodeResults().get(new NodeId("failed")).status()).isEqualTo(RunResult.Status.ERROR);
        assertThat(result.nodeResults().get(new NodeId("after")).status()).isEqualTo(RunResult.Status.PASSED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exhaustingCleanupAfterCancellationStillRollsBackFaultsAndRestoresInterrupt(boolean interrupt)
            throws Exception {
        connector.stopMain = true;
        connector.interruptMain = interrupt;
        PlanNode fault = new PlanNode.FaultStartNode(new NodeId("fault"), "synthetic-fault", "test", Map.of(),
                30_000, List.of(), SafetyClassification.READ_ONLY);
        try {
            RunResult result = run(5000, fault, operation("main"), cleanupWait(), cleanup("after", "fast", 0));

            assertThat(result.status()).isEqualTo(RunResult.Status.CANCELLED);
            assertThat(faults.rollbacks.get()).isEqualTo(1);
            assertThat(faults.interrupted.get()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupt);
            assertExpired(result, "wait", "after");
            Thread.interrupted();
            String events = journal();
            assertThat(events.indexOf("FAULT_ROLLED_BACK")).isGreaterThan(events.indexOf("CLEANUP_COMPLETED"));
            assertThat(events).contains("run-end").doesNotContain("rollback-failed");
        } finally {
            Thread.interrupted();
        }
    }

    private RunResult run(long scenarioMs, PlanNode... nodes) throws Exception {
        List<OperationDefinition> definitions = List.of(definition("main"), definition("slow"), definition("fast"));
        ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"), List.of(new TargetDefinition(
                new TargetId("test"), "Test", "http://127.0.0.1:1", List.of(new ProtocolId("http")), List.of(), Map.of())),
                definitions, Map.of(), Map.of(), List.of());
        ScenarioDocument scenario = new ScenarioDocument("faultora.dev/v1", "Scenario",
                new ScenarioMetadata("cleanup", null, Map.of(), Map.of()), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
        ExecutionPlan plan = ExecutionPlan.builder().runId(new RunId("synthetic"))
                .scenario(scenario).catalog(catalog).scenarioTimeoutMs(scenarioMs)
                .scenarioDigest("synthetic").catalogDigest("synthetic").seed(1)
                .targetPolicy(new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                        100, 1, 5000, 1024, Set.of(), Set.of()))
                .addNodes(List.of(nodes)).build();
        LocalEngine engine = useDefault ? new LocalEngine(Map.of("http", connector), Map.of())
                : new LocalEngine(Map.of("http", connector), Map.of(), Map.of("synthetic-fault", faults),
                        cleanupBudget, time::deadline);
        try (RunJournal journal = new RunJournal(directory.resolve("run-" + (++journalIndex) + ".ndjson"), true)) {
            return engine.execute(plan, journal, ExpressionContext.builder().build(),
                    new ConnectorContext(EvidencePolicy.MINIMAL, key -> null, 5000, 5000,
                            operationBudget, Map.of()), cancellation);
        }
    }

    private String journal() throws Exception {
        return Files.readString(directory.resolve("run-" + journalIndex + ".ndjson"));
    }

    private static PlanNode.CleanupNode cleanup(String id, String operation, long deadline) {
        return new PlanNode.CleanupNode(new NodeId(id), new OperationId(operation), Map.of(), List.of(),
                SafetyClassification.READ_ONLY, deadline, 0);
    }

    private static PlanNode.WaitNode cleanupWait() {
        return new PlanNode.WaitNode(new NodeId("wait"), 5000, List.of(), SafetyClassification.READ_ONLY, true);
    }

    private static PlanNode.OperationNode operation(String id) {
        OperationDefinition definition = definition("main");
        return new PlanNode.OperationNode(new NodeId(id), definition.id(), definition, Map.of(), null, List.of(),
                SafetyClassification.READ_ONLY, 0, 0);
    }

    private static OperationDefinition definition(String id) {
        return new OperationDefinition(new OperationId(id), new ProtocolId("http"), new TargetId("test"),
                SafetyClassification.READ_ONLY, Map.of(), null, Map.of(), Map.of("method", "GET", "path", "/"));
    }

    private static void assertExpired(RunResult result, String... ids) {
        for (String id : ids) {
            RunResult.NodeResult node = result.nodeResults().get(new NodeId(id));
            assertThat(node.status()).isEqualTo(RunResult.Status.FAILED);
            assertThat(node.error().category()).isEqualTo(NormalizedError.ErrorCategory.TIMEOUT);
            assertThat(node.error().code()).isEqualTo("CLEANUP_DEADLINE_EXCEEDED");
        }
    }

    private void assertShrinkingBudgets(long ceiling) {
        long previous = ceiling + 1;
        for (ConnectorContext context : connector.budgets) {
            assertThat(context.totalTimeoutMs()).isPositive().isLessThan(previous);
            assertThat(context.requestTimeoutMs()).isPositive().isLessThanOrEqualTo(context.totalTimeoutMs());
            assertThat(context.connectTimeoutMs()).isPositive().isLessThanOrEqualTo(context.requestTimeoutMs());
            previous = context.totalTimeoutMs();
        }
    }

    private void assertBounded(long start) {
        assertThat(time.elapsedMillis() - start).isEqualTo(cleanupBudget);
    }

    private final class CleanupConnector implements Connector {
        final List<ConnectorContext> budgets = new CopyOnWriteArrayList<>();
        final List<String> operations = new CopyOnWriteArrayList<>();
        long delayMs;
        boolean ignoreBudget;
        boolean throwOnSlow;
        boolean stopMain;
        boolean interruptMain;
        public ProtocolId protocol() { return new ProtocolId("http"); }
        public Set<String> capabilities() { return Set.of(); }
        public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) { return () -> target; }
        public OperationResult execute(PreparedTarget target, OperationDefinition operation,
                                       Map<String, Object> inputs, ConnectorContext context) {
            budgets.add(context);
            operations.add(operation.id().value());
            if (operation.id().value().equals("main") && stopMain) {
                if (interruptMain) { Thread.currentThread().interrupt(); }
                else { cancellation.set(true); }
            }
            if (operation.id().value().equals("slow")) {
                if (throwOnSlow) { throw new IllegalStateException("Synthetic cleanup failure"); }
                time.advanceMillis(ignoreBudget ? delayMs : Math.min(delayMs, context.totalTimeoutMs()));
                if (!ignoreBudget && delayMs >= context.totalTimeoutMs()) {
                    return OperationResult.failure(new NormalizedError(NormalizedError.ErrorCategory.TIMEOUT,
                            "SYNTHETIC_TIMEOUT", "synthetic", false, Map.of()), delayMs);
                }
            }
            return OperationResult.success(200, Map.of(), null, 0, Map.of());
        }
        public void release(PreparedTarget target) { }
        public void close() { }
    }

    private static class RollbackProvider implements FaultProvider {
        final AtomicInteger rollbacks = new AtomicInteger();
        final AtomicBoolean interrupted = new AtomicBoolean();
        public Set<String> capabilities() { return Set.of("synthetic-fault"); }
        public ActiveFault inject(String type, Map<String, Object> params, FaultContext context) {
            return new ActiveFault("synthetic", type, context.targetScope(), System.currentTimeMillis(),
                    context.hardExpiryMs(), "synthetic rollback");
        }
        public void rollback(ActiveFault fault, FaultContext context) {
            interrupted.set(Thread.currentThread().isInterrupted());
            try { Thread.sleep(0); }
            catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Synthetic rollback interrupted", stopped);
            }
            rollbacks.incrementAndGet();
        }
    }
}
