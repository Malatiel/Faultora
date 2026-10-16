package dev.faultora.engine;

import dev.faultora.engine.exec.Deadline;
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
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.result.ActiveFault;
import dev.faultora.spi.result.AssertionResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(20)
class EngineDeadlineTest {
    @TempDir
    Path directory;
    private final ManualTime time = new ManualTime();
    private boolean realTime;
    private final ProbeConnector connector = new ProbeConnector();
    private final InterruptibleRollback faults = new InterruptibleRollback();
    private final AtomicBoolean cancellation = new AtomicBoolean();
    private long operationBudget = 5000;
    private int journalIndex;
    private final OperationDefinition request = definition("request");
    private final OperationDefinition cleanup = definition("cleanup");

    @BeforeEach
    void warmUp() throws Exception {
        assertThat(run(5000, 5000, operation("warm", 0, null, false)).status())
                .isEqualTo(RunResult.Status.PASSED);
        connector.budgets.clear();
        connector.operations.clear();
        connector.prepared.set(0);
        connector.released.set(0);
    }

    @Test
    void retriesAndBackoffConsumeOneNodeBudget() throws Exception {
        connector.delayMs = 70;
        connector.retryable = true;
        long start = time.elapsedMillis();
        RunResult result = run(0, 5000, operation("retry", 300,
                new PlanNode.RetrySpec(20, 20, 1, 20), false));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.nodeResults().get(new NodeId("retry")).error().code()).isEqualTo("DEADLINE_EXCEEDED");
        assertShrinkingBudgets(300);
        assertThat(connector.budgets).hasSize(4);
        assertBounded(start);
    }

    @Test
    void aLongBackoffCannotStartAnotherAttemptAfterExpiry() throws Exception {
        connector.retryable = true;
        long start = time.elapsedMillis();
        RunResult result = run(0, 5000, operation("retry", 150,
                new PlanNode.RetrySpec(3, 5000, 1, 5000), false));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(connector.budgets).hasSize(1);
        assertBounded(start);
    }

    @Test
    void theConfiguredConnectorTotalBudgetAlsoBoundsTheWholeRetryChain() throws Exception {
        operationBudget = 250;
        connector.retryable = true;
        connector.delayMs = 60;
        RunResult result = run(0, 5000, operation("retry", 0,
                new PlanNode.RetrySpec(20, 0, 1, 0), false));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertShrinkingBudgets(250);
        assertThat(connector.budgets).hasSize(5);
    }

    @Test
    void timeSpentPreparingDoesNotBecomeANewExecutionBudget() throws Exception {
        connector.prepareDelayMs = 200;
        RunResult result = run(0, 5000, operation("request", 100, null, false));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(connector.budgets).isEmpty();
        assertThat(connector.prepared.get()).isEqualTo(1);
        assertThat(connector.released.get()).isEqualTo(1);
    }

    @Test
    void aLateSuccessFromACallbackThatIgnoresItsBudgetIsStillAFailure() throws Exception {
        connector.delayMs = 120;
        connector.ignoreBudget = true;
        RunResult result = run(0, 5000, operation("request", 60, null, false));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.nodeResults().get(new NodeId("request")).error().category())
                .isEqualTo(NormalizedError.ErrorCategory.TIMEOUT);
    }

    @Test
    void pollsReceiveTheRemainingGroupBudget() throws Exception {
        connector.delayMs = 65;
        RunResult result = run(0, 5000, eventually(250, 10, false));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.nodeResults().get(new NodeId("eventually")).error().code()).isEqualTo("EVENTUALLY_TIMEOUT");
        assertShrinkingBudgets(250);
        assertThat(connector.budgets).hasSize(4);
    }

    @Test
    void aLongPollIntervalCannotStartAnExtraPollAfterExpiry() throws Exception {
        long start = time.elapsedMillis();
        RunResult result = run(0, 5000, eventually(150, 5000, false));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(connector.budgets).hasSize(1);
        assertBounded(start);
    }

    @Test
    void aSatisfiedConditionCannotPassAfterThePollingBudgetIsSpent() throws Exception {
        connector.delayMs = 120;
        connector.ignoreBudget = true;
        RunResult result = run(0, 5000, eventually(60, 10, true));

        assertThat(result.nodeResults().get(new NodeId("eventually")).status()).isEqualTo(RunResult.Status.FAILED);
    }

    @Test
    void repeatChildrenAndIterationsShareOneGroupDeadline() throws Exception {
        connector.delayMs = 80;
        RunResult result = run(0, 5000, new PlanNode.RepeatNode(new NodeId("repeat"),
                List.of(operation("child", 5000, null, false)), null, 20,
                List.of(), SafetyClassification.READ_ONLY, 250));

        assertThat(result.nodeResults().get(new NodeId("repeat")).error().category())
                .isEqualTo(NormalizedError.ErrorCategory.TIMEOUT);
        assertShrinkingBudgets(250);
        assertThat(connector.budgets).hasSize(4);
    }

    @Test
    void queuedParallelChildrenCannotAcquireAFreshGroupBudget() throws Exception {
        connector.delayMs = 150;
        PlanNode group = new PlanNode.ParallelNode(new NodeId("parallel"),
                List.of(operation("a", 5000, null, false), operation("b", 5000, null, false),
                        operation("c", 5000, null, false)), List.of(), SafetyClassification.READ_ONLY, 250);
        RunResult result = run(0, 5000, group);

        assertThat(result.nodeResults().get(new NodeId("parallel")).status()).isEqualTo(RunResult.Status.FAILED);
        assertShrinkingBudgets(250);
        assertThat(connector.budgets).hasSize(2);
        assertThat(result.nodeResults().get(new NodeId("c")).status()).isEqualTo(RunResult.Status.FAILED);
    }

    @Test
    void aRunDeadlineBoundsItsCurrentAndLastOperationAndStillRunsCleanup() throws Exception {
        connector.delayMs = 1000;
        RunResult result = run(200, 5000, operation("request", 5000, null, true), cleanupNode());

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.error().code()).isEqualTo("SCENARIO_DEADLINE_EXCEEDED");
        assertThat(result.nodeResults().get(new NodeId("request")).status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.nodeResults().get(new NodeId("cleanup")).status()).isEqualTo(RunResult.Status.PASSED);
        assertThat(connector.operations).containsExactly("request", "cleanup");
        assertThat(connector.budgets.get(0).totalTimeoutMs()).isLessThanOrEqualTo(200);
        assertThat(connector.budgets.get(1).totalTimeoutMs()).isGreaterThan(200);
    }

    @Test
    void theRunDeadlineClipsLocalWaitsAndPreventsTheNextOperation() throws Exception {
        long start = time.elapsedMillis();
        RunResult result = run(150, 5000, new PlanNode.WaitNode(new NodeId("wait"), 5000,
                List.of(), SafetyClassification.READ_ONLY), operation("next", 0, null, false));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(connector.operations).isEmpty();
        assertBounded(start);
    }

    @Test
    void theTargetPolicyAlsoBoundsAProgrammaticallyBuiltPlan() throws Exception {
        connector.delayMs = 1000;
        RunResult result = run(5000, 150, operation("request", 5000, null, false));

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(connector.budgets.get(0).totalTimeoutMs()).isLessThanOrEqualTo(150);
    }

    @Test
    void cancellationDuringBackoffStopsRetryAndRunsCleanup() throws Exception {
        realTime = true;
        operationBudget = 30_000;
        connector.retryable = true;
        connector.entered = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = workers.submit(() -> run(0, 60_000, operation("retry", 30_000,
                    new PlanNode.RetrySpec(20, 30_000, 1, 30_000), false), cleanupNode()));
            assertThat(connector.entered.await(5, TimeUnit.SECONDS)).isTrue();
            cancellation.set(true);
            assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo(RunResult.Status.CANCELLED);
        }
        assertThat(connector.operations).containsExactly("request", "cleanup");
    }

    @Test
    void cancellationDuringParallelWaitingDoesNotWaitForTheGroupDeadline() throws Exception {
        realTime = true;
        operationBudget = 30_000;
        connector.delayMs = 30_000;
        connector.entered = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = workers.submit(() -> run(0, 60_000, new PlanNode.ParallelNode(new NodeId("parallel"),
                    List.of(operation("child", 30_000, null, false)), List.of(), SafetyClassification.READ_ONLY, 30_000)));
            assertThat(connector.entered.await(5, TimeUnit.SECONDS)).isTrue();
            cancellation.set(true);
            assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo(RunResult.Status.CANCELLED);
        }
    }

    @Test
    void aThreadInterruptStopsBackoffPreservesItsFlagAndDoesNotCancelCleanup() throws Exception {
        realTime = true;
        operationBudget = 30_000;
        connector.retryable = true;
        connector.entered = new CountDownLatch(1);
        AtomicReference<Thread> caller = new AtomicReference<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = workers.submit(() -> {
                caller.set(Thread.currentThread());
                RunResult result = run(0, 60_000, operation("retry", 30_000,
                        new PlanNode.RetrySpec(20, 30_000, 1, 30_000), false), cleanupNode());
                boolean interrupted = Thread.interrupted();
                assertThat(interrupted).isTrue();
                return result;
            });
            assertThat(connector.entered.await(5, TimeUnit.SECONDS)).isTrue();
            caller.get().interrupt();
            assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo(RunResult.Status.CANCELLED);
        }
        assertThat(connector.operations).containsExactly("request", "cleanup");
        assertThat(Files.readString(directory.resolve("run-2.ndjson"))).contains("RUN_FAILED", "CLEANUP_COMPLETED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptionDoesNotPreventFaultRollbackWithOrWithoutCleanup(boolean withCleanup) throws Exception {
        realTime = true;
        operationBudget = 30_000;
        connector.retryable = true;
        connector.entered = new CountDownLatch(1);
        AtomicReference<Thread> caller = new AtomicReference<>();
        PlanNode fault = new PlanNode.FaultStartNode(new NodeId("fault"), "synthetic-fault", "test",
                Map.of(), 30_000, List.of(), SafetyClassification.READ_ONLY);
        PlanNode retry = operation("retry", 30_000, new PlanNode.RetrySpec(20, 30_000, 1, 30_000), false);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = workers.submit(() -> {
                caller.set(Thread.currentThread());
                RunResult result = withCleanup ? run(0, 60_000, fault, retry, cleanupNode())
                        : run(0, 60_000, fault, retry);
                assertThat(Thread.interrupted()).isTrue();
                return result;
            });
            assertThat(connector.entered.await(5, TimeUnit.SECONDS)).isTrue();
            caller.get().interrupt();
            assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo(RunResult.Status.CANCELLED);
        }
        assertThat(faults.rollbacks.get()).isEqualTo(1);
        assertThat(faults.interrupted.get()).isFalse();
        assertThat(Files.readString(directory.resolve("run-2.ndjson")))
                .contains("run-end").doesNotContain("rollback-failed");
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

    private RunResult run(long scenarioMs, long policyMs, PlanNode... nodes) throws Exception {
        TargetDefinition target = new TargetDefinition(new TargetId("test"), "Test", "http://127.0.0.1:1",
                List.of(new ProtocolId("http")), List.of(), Map.of());
        ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"), List.of(target),
                List.of(request, cleanup), Map.of(), Map.of(), List.of());
        ScenarioDocument scenario = new ScenarioDocument("faultora.dev/v1", "Scenario",
                new ScenarioMetadata("deadline", null, Map.of(), Map.of()), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
        ExecutionPlan plan = ExecutionPlan.builder().runId(new RunId("synthetic"))
                .scenario(scenario).catalog(catalog).seed(42)
                .targetPolicy(new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                        100, 1, policyMs, 1024, Set.of(), Set.of()))
                .scenarioDigest("synthetic-scenario").catalogDigest("synthetic-catalog")
                .scenarioTimeoutMs(scenarioMs).addNodes(List.of(nodes)).build();
        ConnectorContext context = new ConnectorContext(EvidencePolicy.MINIMAL, key -> null,
                5000, 5000, operationBudget, Map.of());
        try (RunJournal journal = new RunJournal(directory.resolve("run-" + (++journalIndex) + ".ndjson"), true)) {
            return new LocalEngine(Map.of("http", connector), Map.of("condition", new Condition()),
                    Map.of("synthetic-fault", faults), LocalEngine.DEFAULT_CLEANUP_TIMEOUT_MS,
                    realTime ? Deadline::start : time::deadline)
                    .execute(plan, journal, ExpressionContext.builder().build(), context, cancellation);
        }
    }

    private PlanNode.EventuallyNode eventually(long timeout, long interval, boolean satisfied) {
        return new PlanNode.EventuallyNode(new NodeId("eventually"), operation("poll", 5000, null, false),
                List.of(new PlanNode.Condition("condition", Map.of("satisfied", satisfied), null)),
                timeout, interval, 100, List.of(), SafetyClassification.READ_ONLY);
    }

    private PlanNode.OperationNode operation(String id, long deadline, PlanNode.RetrySpec retry, boolean expectError) {
        return new PlanNode.OperationNode(new NodeId(id), request.id(), request, Map.of(), null, expectError,
                retry, List.of(), SafetyClassification.READ_ONLY, deadline, 0);
    }

    private PlanNode.CleanupNode cleanupNode() {
        return new PlanNode.CleanupNode(new NodeId("cleanup"), cleanup.id(), Map.of(),
                List.of(), SafetyClassification.READ_ONLY, 0, 0);
    }

    private static OperationDefinition definition(String id) {
        return new OperationDefinition(new OperationId(id), new ProtocolId("http"), new TargetId("test"),
                SafetyClassification.READ_ONLY, Map.of(), null, Map.of(), Map.of("method", "GET", "path", "/"));
    }

    private void assertBounded(long start) {
        assertThat(time.elapsedMillis() - start).isLessThanOrEqualTo(300);
    }

    private static class Condition implements AssertionProvider {
        public String type() { return "condition"; }
        public AssertionResult evaluate(String type, Map<String, Object> params,
                                        EvidenceView evidence, AssertionContext context) {
            return Boolean.TRUE.equals(params.get("satisfied")) ? AssertionResult.pass("ready")
                    : AssertionResult.fail("not ready", Map.of());
        }
    }

    private static class InterruptibleRollback implements FaultProvider {
        final AtomicInteger rollbacks = new AtomicInteger();
        final AtomicBoolean interrupted = new AtomicBoolean();
        public Set<String> capabilities() { return Set.of("synthetic-fault"); }
        public ActiveFault inject(String type, Map<String, Object> params, FaultContext context) {
            return new ActiveFault("synthetic-handle", type, context.targetScope(), System.currentTimeMillis(),
                    context.hardExpiryMs(), "synthetic rollback");
        }
        public void rollback(ActiveFault fault, FaultContext context) {
            interrupted.set(Thread.currentThread().isInterrupted());
            try {
                Thread.sleep(0);
                rollbacks.incrementAndGet();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Synthetic rollback interrupted", stopped);
            }
        }
    }

    private final class ProbeConnector implements Connector {
        final List<ConnectorContext> budgets = new CopyOnWriteArrayList<>();
        final List<String> operations = new CopyOnWriteArrayList<>();
        final AtomicInteger prepared = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        long delayMs;
        long prepareDelayMs;
        boolean retryable;
        boolean ignoreBudget;
        CountDownLatch entered;
        public ProtocolId protocol() { return new ProtocolId("http"); }
        public Set<String> capabilities() { return Set.of(); }
        public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
            prepared.incrementAndGet();
            pause(prepareDelayMs);
            return () -> target;
        }
        public OperationResult execute(PreparedTarget target, OperationDefinition operation,
                                       Map<String, Object> inputs, ConnectorContext context) {
            budgets.add(context);
            operations.add(operation.id().value());
            if (operation.id().value().equals("cleanup")) {
                return OperationResult.success(200, Map.of(), null, 0, Map.of());
            }
            if (entered != null) { entered.countDown(); }
            pause(ignoreBudget ? delayMs : Math.min(delayMs, context.totalTimeoutMs()));
            if (retryable || !ignoreBudget && delayMs >= context.totalTimeoutMs()) {
                return OperationResult.failure(new NormalizedError(NormalizedError.ErrorCategory.TIMEOUT,
                        "SYNTHETIC_TIMEOUT", "synthetic", retryable, Map.of()), delayMs);
            }
            return OperationResult.success(200, Map.of(), null, delayMs, Map.of());
        }
        private void pause(long ms) {
            if (!realTime) {
                time.advanceMillis(ms);
                return;
            }
            try { Thread.sleep(ms); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        public void release(PreparedTarget target) { released.incrementAndGet(); }
        public void close() { }
    }
}
