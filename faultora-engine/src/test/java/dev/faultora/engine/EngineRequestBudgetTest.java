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
import dev.faultora.spi.context.*;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.result.AssertionResult;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(5)
class EngineRequestBudgetTest {
    @TempDir Path directory;
    private final Probe connector = new Probe();
    private final AtomicBoolean cancellation = new AtomicBoolean();
    private List<RunEvent> events;
    private long totalTimeout = 10_000;
    private static final OperationDefinition READ = definition("read", "http", "test");
    private static final OperationDefinition CLEANUP = definition("cleanup", "http", "test");

    @Test
    void aManuallyBuiltPlanCannotStartAnExtraAttempt() throws Exception {
        RunResult result = run(1, operation("first"), operation("second"));
        assertBudgetFailure(result, "second");
        assertCounts(1, 1, 1);
    }

    @Test
    void everyRetryUsesAnAttemptAndExhaustionStopsBeforeAnotherBackoff() throws Exception {
        connector.retryable = true;
        RunResult result = run(2, retry("retry", 10, 0));
        assertBudgetFailure(result, "retry");
        assertCounts(2, 2, 2);
        assertThat(events.stream().filter(RunEvent.OperationRetried.class::isInstance)).hasSize(1);
    }

    @Test
    void anExhaustedBudgetDoesNotWaitForAConfiguredFiveSecondBackoff() throws Exception {
        connector.retryable = true;
        assertBudgetFailure(run(1, retry("retry", 2, 5000)), "retry");
        assertCounts(1, 1, 1);
        assertThat(events.stream().filter(RunEvent.OperationRetried.class::isInstance)).isEmpty();
    }

    @Test
    void earlySuccessChargesOnlyTheAttemptThatActuallyStarted() throws Exception {
        assertThat(run(1, retry("retry", 10, 0)).status()).isEqualTo(RunResult.Status.PASSED);
        assertCounts(1, 1, 1);
    }

    @Test
    void expectErrorCannotTurnBudgetExhaustionIntoAPass() throws Exception {
        PlanNode.OperationNode expected = new PlanNode.OperationNode(new NodeId("expected"), READ.id(), READ,
                Map.of(), null, true, null, List.of(), SafetyClassification.READ_ONLY, 0, 0);
        assertBudgetFailure(run(1, operation("first"), expected), "expected");
        assertCounts(1, 1, 1);
    }

    @Test
    void aMissingConnectorDoesNotSpendTheFollowingOperationsBudget() throws Exception {
        var missing = definition("missing", "missing", "test");
        RunResult result = run(1, operation("missing", missing), operation("valid"));
        assertThat(result.nodeResults().get(new NodeId("missing")).error().code()).isEqualTo("NO_CONNECTOR");
        assertThat(result.nodeResults().get(new NodeId("valid")).status()).isEqualTo(RunResult.Status.PASSED);
        assertCounts(1, 1, 1);
    }

    @Test
    void aMissingTargetDoesNotSpendTheFollowingOperationsBudget() throws Exception {
        var missing = definition("missing", "http", "missing");
        RunResult result = run(1, operation("missing", missing), operation("valid"));
        assertThat(result.nodeResults().get(new NodeId("missing")).error().code()).isEqualTo("TARGET_NOT_FOUND");
        assertThat(result.nodeResults().get(new NodeId("valid")).status()).isEqualTo(RunResult.Status.PASSED);
        assertCounts(1, 1, 1);
    }

    @Test
    void preparationFailureStillConsumesTheGrantedAttempt() throws Exception {
        connector.failPreparation = true;
        assertBudgetFailure(run(1, operation("first"), operation("second")), "second");
        assertCounts(1, 0, 0);
    }

    @Test
    void cancellationBeforeInvocationDoesNotSpendAnyAttempt() throws Exception {
        cancellation.set(true);
        assertThat(run(1, operation("first")).status()).isEqualTo(RunResult.Status.CANCELLED);
        assertCounts(0, 0, 0);
    }

    @Test
    void anExpiredOperationBudgetDoesNotSpendAnyAttempt() throws Exception {
        totalTimeout = 0;
        assertThat(run(1, operation("first")).status()).isEqualTo(RunResult.Status.FAILED);
        assertCounts(0, 0, 0);
    }

    @Test
    void repeatIterationsShareTheRunBudget() throws Exception {
        var repeat = new PlanNode.RepeatNode(new NodeId("repeat"), List.of(operation("child")), null, 5,
                List.of(), SafetyClassification.READ_ONLY, 0);
        RunResult result = run(2, repeat);
        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertBudgetFailure(result, "child:2");
        assertCounts(2, 2, 2);
    }

    @Test
    void parallelChildrenCannotRacePastTheLastPermits() throws Exception {
        connector.concurrent = new CountDownLatch(3);
        var parallel = new PlanNode.ParallelNode(new NodeId("parallel"),
                IntStream.range(0, 20).mapToObj(index -> operation("child-" + index)).toList(),
                List.of(), SafetyClassification.READ_ONLY, 0);
        RunResult result = run(3, parallel);
        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.nodeResults().values().stream()
                .filter(node -> node.error() != null && "REQUEST_BUDGET_EXCEEDED".equals(node.error().code())))
                .hasSize(17);
        assertCounts(3, 3, 3);
        assertThat(attempts().stream().map(RunEvent.OperationAttempted::attemptNumber))
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void pollingStopsWithAPolicyFailureRatherThanConvertingItToATimeout() throws Exception {
        var eventually = new PlanNode.EventuallyNode(new NodeId("eventually"), operation("poll"),
                List.of(new PlanNode.Condition("never", Map.of(), null)), 10_000, 1, 20,
                List.of(), SafetyClassification.READ_ONLY);
        RunResult result = run(2, eventually);
        assertBudgetFailure(result, "eventually");
        assertBudgetFailure(result, "poll");
        assertCounts(2, 2, 2);
        assertThat(events.stream().filter(RunEvent.ConditionPolled.class::isInstance)).hasSize(2);
    }

    @Test
    void cleanupHasAReserveWithinTheSameLimitEvenWhenMainRetriesExhaustTheirShare() throws Exception {
        connector.retryable = true;
        RunResult result = run(3, retry("retry", 10, 0), cleanup("cleanup"));
        assertBudgetFailure(result, "retry");
        assertThat(result.nodeResults().get(new NodeId("cleanup")).status()).isEqualTo(RunResult.Status.PASSED);
        assertCounts(3, 3, 3);
    }

    @Test
    void anOversizedCleanupReserveCannotWidenThePolicyLimit() throws Exception {
        RunResult result = run(1, operation("first"), cleanup("cleanup-a"), cleanup("cleanup-b"));
        assertBudgetFailure(result, "first");
        assertThat(result.nodeResults().get(new NodeId("cleanup-a")).status()).isEqualTo(RunResult.Status.PASSED);
        assertBudgetFailure(result, "cleanup-b");
        assertCounts(1, 1, 1);
    }

    @Test
    void mainWorkAndCleanupAtTheExactCombinedLimitPass() throws Exception {
        assertThat(run(2, operation("first"), cleanup("cleanup")).status()).isEqualTo(RunResult.Status.PASSED);
        assertCounts(2, 2, 2);
    }

    @Test
    void theLargestValidPolicyLimitDoesNotOverflowWhenCleanupIsReserved() throws Exception {
        assertThat(run(Integer.MAX_VALUE, operation("a"), operation("b"), cleanup("cleanup")).status())
                .isEqualTo(RunResult.Status.PASSED);
        assertCounts(3, 3, 3);
    }

    private RunResult run(int limit, PlanNode... nodes) throws Exception {
        TargetDefinition target = new TargetDefinition(new TargetId("test"), "Test", "http://127.0.0.1:1",
                List.of(new ProtocolId("http")), List.of(), Map.of());
        ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"), List.of(target),
                List.of(READ, CLEANUP), Map.of(), Map.of(), List.of());
        ExecutionPlan plan = ExecutionPlan.builder().runId(new RunId("synthetic-budget"))
                .scenario(new ScenarioDocument("faultora.dev/v1", "Scenario",
                        new ScenarioMetadata("synthetic-budget", null, Map.of(), Map.of()), Map.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), null))
                .catalog(catalog).seed(42).scenarioDigest("synthetic-scenario").catalogDigest("synthetic-catalog")
                .targetPolicy(new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                        limit, 20, 10_000, 1024, Set.of(), Set.of())).addNodes(List.of(nodes)).build();
        AssertionProvider never = new AssertionProvider() {
            public String type() {
                return "never";
            }
            public AssertionResult evaluate(String type, Map<String, Object> params,
                                            EvidenceView evidence, AssertionContext context) {
                return AssertionResult.fail("Not ready", Map.of());
            }
        };
        try (RunJournal journal = new RunJournal(directory.resolve("events.ndjson"), true)) {
            RunResult result = new LocalEngine(Map.of("http", connector), Map.of("never", never))
                    .execute(plan, journal, ExpressionContext.builder().build(),
                            new ConnectorContext(EvidencePolicy.MINIMAL, key -> null, 1000, 1000, totalTimeout, Map.of()),
                            cancellation);
            events = journal.events();
            return result;
        }
    }

    private List<RunEvent.OperationAttempted> attempts() {
        return events.stream().filter(RunEvent.OperationAttempted.class::isInstance)
                .map(RunEvent.OperationAttempted.class::cast).toList();
    }

    private void assertCounts(int prepared, int executed, int released) {
        assertThat(connector.prepared.get()).isEqualTo(prepared);
        assertThat(connector.executed.get()).isEqualTo(executed);
        assertThat(connector.released.get()).isEqualTo(released);
        assertThat(attempts()).hasSize(prepared);
        assertThat(events.stream().filter(RunEvent.RunStarted.class::isInstance)
                .map(RunEvent.RunStarted.class::cast).findFirst().orElseThrow().resolvedConfig())
                .containsEntry("requestBudgetUnit", "connector-attempt");
    }

    private static void assertBudgetFailure(RunResult result, String nodeId) {
        var node = result.nodeResults().get(new NodeId(nodeId));
        assertThat(node.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(node.error().category()).isEqualTo(NormalizedError.ErrorCategory.POLICY_VIOLATION);
        assertThat(node.error().code()).isEqualTo("REQUEST_BUDGET_EXCEEDED");
        assertThat(node.error().retryable()).isFalse();
    }

    private static PlanNode.OperationNode operation(String id) {
        return operation(id, READ);
    }
    private static PlanNode.OperationNode operation(String id, OperationDefinition definition) {
        return new PlanNode.OperationNode(new NodeId(id), definition.id(), definition, Map.of(), null,
                List.of(), SafetyClassification.READ_ONLY, 0, 0);
    }
    private static PlanNode.OperationNode retry(String id, int attempts, long backoff) {
        return new PlanNode.OperationNode(new NodeId(id), READ.id(), READ, Map.of(), null, false,
                new PlanNode.RetrySpec(attempts, backoff, 1, backoff), List.of(), SafetyClassification.READ_ONLY, 0, 0);
    }
    private static PlanNode.CleanupNode cleanup(String id) {
        return new PlanNode.CleanupNode(new NodeId(id), CLEANUP.id(), Map.of(),
                List.of(), SafetyClassification.READ_ONLY, 0, 0);
    }
    private static OperationDefinition definition(String id, String protocol, String target) {
        return new OperationDefinition(new OperationId(id), new ProtocolId(protocol), new TargetId(target),
                SafetyClassification.READ_ONLY, Map.of(), null, Map.of(), Map.of());
    }

    private static class Probe implements Connector {
        final AtomicInteger prepared = new AtomicInteger();
        final AtomicInteger executed = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        boolean retryable;
        boolean failPreparation;
        CountDownLatch concurrent;
        public ProtocolId protocol() {
            return new ProtocolId("http");
        }
        public Set<String> capabilities() {
            return Set.of();
        }
        public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
            prepared.incrementAndGet();
            if (failPreparation) {
                throw new IllegalArgumentException("Synthetic preparation refusal");
            }
            return () -> target;
        }
        public OperationResult execute(PreparedTarget target, OperationDefinition operation,
                                       Map<String, Object> inputs, ConnectorContext context) {
            executed.incrementAndGet();
            if (concurrent != null) {
                concurrent.countDown();
                try {
                    assertThat(concurrent.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            if (retryable && !operation.id().equals(CLEANUP.id())) {
                return OperationResult.failure(new NormalizedError(NormalizedError.ErrorCategory.NETWORK,
                        "SYNTHETIC_RETRY", "Synthetic transient failure", true, Map.of()), 0);
            }
            return OperationResult.success(200, Map.of(), null, 0, Map.of());
        }
        public void release(PreparedTarget target) {
            released.incrementAndGet();
        }
        public void close() { }
    }
}
