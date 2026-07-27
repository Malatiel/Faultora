package dev.faultora.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.fault.FaultSession;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.expression.ExpressionEvaluator;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.ActiveFault;
import dev.faultora.spi.result.AssertionResult;
import dev.faultora.spi.result.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local execution engine.
 * Executes plan nodes in topological order, maintains the run journal,
 * and handles failure, cancellation, deadlines, and cleanup continuation.
 */
public class LocalEngine {

    private static final Logger LOG = LoggerFactory.getLogger(LocalEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Separator between a repeat child's step ID and its iteration index. */
    static final String ITERATION_SEPARATOR = ":";

    private final Map<String, Connector> connectors;
    private final Map<String, AssertionProvider> assertionProviders;
    private final Map<String, FaultProvider> faultProviders;
    private final ExpressionEvaluator expressionEvaluator;

    public LocalEngine(
            Map<String, Connector> connectors,
            Map<String, AssertionProvider> assertionProviders
    ) {
        this(connectors, assertionProviders, Map.of());
    }

    public LocalEngine(
            Map<String, Connector> connectors,
            Map<String, AssertionProvider> assertionProviders,
            Map<String, FaultProvider> faultProviders
    ) {
        this.connectors = Map.copyOf(connectors);
        this.assertionProviders = Map.copyOf(assertionProviders);
        this.faultProviders = Map.copyOf(faultProviders);
        this.expressionEvaluator = new ExpressionEvaluator();
    }

    /**
     * Execute a compiled plan.
     *
     * @param plan       the execution plan
     * @param journal    run journal for events
     * @param context    expression evaluation context
     * @param connectorContext connector context for operations
     * @param cancellation    cancellation flag
     * @return the run result
     */
    public RunResult execute(
            ExecutionPlan plan,
            RunJournal journal,
            ExpressionContext context,
            ConnectorContext connectorContext,
            AtomicBoolean cancellation
    ) {
        long startTime = System.currentTimeMillis();
        RunId runId = plan.runId();

        // Emit run started event
        emitEvent(journal, new RunEvent.RunStarted(
                "RUN_STARTED", System.currentTimeMillis(), runId,
                plan.scenarioDigest(), plan.catalogDigest(), plan.seed(),
                Map.of()
        ));

        Map<NodeId, RunResult.NodeResult> nodeResults = new LinkedHashMap<>();
        Map<NodeId, NodeEvidence> nodeEvidenceMap = new LinkedHashMap<>();
        AtomicInteger passedAssertions = new AtomicInteger();
        AtomicInteger failedAssertions = new AtomicInteger();
        AtomicBoolean hasFailure = new AtomicBoolean(false);
        AtomicInteger cleanupFailedCount = new AtomicInteger(0);

        // Build execution order (topological)
        List<PlanNode> nodes = plan.topologicalOrder();

        // Fault rollback is guaranteed by the session: fault-stop nodes,
        // the hard-expiry watchdog, and the end-of-run sweep in close().
        FaultSession faultSession = new FaultSession((fault, rollbackStatus) ->
                emitEvent(journal, new RunEvent.FaultRolledBack(
                        "FAULT_ROLLED_BACK", System.currentTimeMillis(), runId,
                        fault.handle(), rollbackStatus)));

        // Cleanup obligations are collected before execution starts: a
        // deadline or cancellation must never drop them.
        List<PlanNode> cleanupNodes = nodes.stream()
                .filter(node -> node instanceof PlanNode.CleanupNode)
                .toList();
        // Step outputs accumulate into the expression context as nodes finish.
        ExpressionContext currentContext = context;
        boolean scenarioDeadlineExceeded = false;
        try {
            // Phase 1: Execute all non-cleanup nodes
            for (PlanNode node : nodes) {
                if (cancellation.get()) {
                    break;
                }

                if (node instanceof PlanNode.CleanupNode) {
                    continue;
                }

                // The scenario deadline bounds the whole run: no further node
                // starts once it elapses, and cleanup still runs below.
                if (plan.scenarioTimeoutMs() > 0
                        && System.currentTimeMillis() - startTime >= plan.scenarioTimeoutMs()) {
                    scenarioDeadlineExceeded = true;
                    break;
                }

                // Check if dependencies completed successfully
                if (!dependenciesSatisfied(node, nodeResults)) {
                    continue;
                }

                RunResult.NodeResult result;
                if (node instanceof PlanNode.ParallelNode parallelNode) {
                    Map<NodeId, RunResult.NodeResult> childResults = new LinkedHashMap<>();
                    result = executeParallelGroup(
                            parallelNode, plan, journal, currentContext, connectorContext,
                            nodeEvidenceMap, childResults, faultSession, cancellation);
                    nodeResults.putAll(childResults);
                    // Bind child outputs in declaration order once all children
                    // finished; children never observe each other's outputs.
                    for (PlanNode.OperationNode child : parallelNode.children()) {
                        RunResult.NodeResult childResult = childResults.get(child.nodeId());
                        if (childResult != null) {
                            currentContext = bindStepOutput(
                                    child, childResult, nodeEvidenceMap, currentContext);
                        }
                    }
                } else if (node instanceof PlanNode.RepeatNode repeatNode) {
                    Map<NodeId, RunResult.NodeResult> childResults = new LinkedHashMap<>();
                    result = executeRepeatGroup(
                            repeatNode, plan, journal, currentContext, connectorContext,
                            nodeEvidenceMap, childResults, faultSession, cancellation);
                    nodeResults.putAll(childResults);
                    // The last completed iteration is what later steps observe.
                    for (PlanNode.OperationNode child : repeatNode.children()) {
                        RunResult.NodeResult childResult = childResults.get(child.nodeId());
                        if (childResult != null) {
                            currentContext = bindStepOutput(
                                    child, childResult, nodeEvidenceMap, currentContext);
                        }
                    }
                } else if (node instanceof PlanNode.EventuallyNode eventuallyNode) {
                    Map<NodeId, RunResult.NodeResult> childResults = new LinkedHashMap<>();
                    result = executeEventually(
                            eventuallyNode, plan, journal, currentContext, connectorContext,
                            nodeEvidenceMap, childResults, cancellation);
                    nodeResults.putAll(childResults);
                    RunResult.NodeResult childResult =
                            childResults.get(eventuallyNode.child().nodeId());
                    if (childResult != null) {
                        currentContext = bindStepOutput(
                                eventuallyNode.child(), childResult,
                                nodeEvidenceMap, currentContext);
                    }
                } else {
                    result = executeNode(
                            node, plan, journal, currentContext, connectorContext,
                            nodeEvidenceMap, faultSession, cancellation
                    );
                    currentContext = bindStepOutput(node, result, nodeEvidenceMap, currentContext);
                }

                nodeResults.put(node.nodeId(), result);

                if (result.status() == RunResult.Status.FAILED ||
                        result.status() == RunResult.Status.ERROR) {
                    hasFailure.set(true);
                }

                // Count assertions
                for (AssertionResult ar : result.assertions()) {
                    if (ar.outcome() == AssertionResult.Outcome.PASS) {
                        passedAssertions.incrementAndGet();
                    } else if (ar.outcome() == AssertionResult.Outcome.FAIL
                            || ar.outcome() == AssertionResult.Outcome.INDETERMINATE) {
                        failedAssertions.incrementAndGet();
                    }
                }
            }

            // Phase 2: Always run cleanup nodes, even if there were failures
            if (!cancellation.get()) {
                emitEvent(journal, new RunEvent.CleanupStarted(
                        "CLEANUP_STARTED", System.currentTimeMillis(), runId,
                        cleanupNodes.size()
                ));

                long cleanupStart = System.currentTimeMillis();
                int cleanupSucceeded = 0;
                int cleanupFailed = 0;

                for (PlanNode cleanupNode : cleanupNodes) {
                    RunResult.NodeResult result = executeNode(
                            cleanupNode, plan, journal, currentContext, connectorContext,
                            nodeEvidenceMap, faultSession, cancellation
                    );
                    nodeResults.put(cleanupNode.nodeId(), result);

                    if (result.status() == RunResult.Status.PASSED) {
                        cleanupSucceeded++;
                    } else {
                        cleanupFailed++;
                        cleanupFailedCount.incrementAndGet();
                    }
                }

                long cleanupDuration = System.currentTimeMillis() - cleanupStart;
                emitEvent(journal, new RunEvent.CleanupCompleted(
                        "CLEANUP_COMPLETED", System.currentTimeMillis(), runId,
                        cleanupSucceeded, cleanupFailed, cleanupDuration
                ));
            }
        } finally {
            faultSession.close();
        }

        long totalDuration = System.currentTimeMillis() - startTime;

        // Determine overall status
        RunResult.Status status;
        if (cancellation.get()) {
            status = RunResult.Status.CANCELLED;
        } else if (hasFailure.get() || failedAssertions.get() > 0
                || cleanupFailedCount.get() > 0 || scenarioDeadlineExceeded) {
            status = RunResult.Status.FAILED;
        } else {
            status = RunResult.Status.PASSED;
        }

        long failedNodes = nodeResults.values().stream()
                .filter(result -> result.status() != RunResult.Status.PASSED)
                .count();
        NormalizedError runError = null;
        if (status == RunResult.Status.PASSED) {
            emitEvent(journal, new RunEvent.RunCompleted(
                    "RUN_COMPLETED", System.currentTimeMillis(), runId,
                    nodeResults.size(), passedAssertions.get(),
                    failedAssertions.get(), totalDuration
            ));
        } else {
            String failureDetail = "Run " + status.name().toLowerCase(Locale.ROOT)
                    + ": " + failedNodes + " failed nodes, "
                    + failedAssertions.get() + " failed assertions";
            if (scenarioDeadlineExceeded) {
                failureDetail += "; scenario deadline of " + plan.scenarioTimeoutMs()
                        + "ms elapsed before every node ran";
            }
            runError = new NormalizedError(
                    status == RunResult.Status.CANCELLED
                            ? NormalizedError.ErrorCategory.CANCELLED
                            : NormalizedError.ErrorCategory.INTERNAL,
                    status == RunResult.Status.CANCELLED ? "RUN_CANCELLED"
                            : (scenarioDeadlineExceeded
                                    ? "SCENARIO_DEADLINE_EXCEEDED" : "RUN_FAILED"),
                    failureDetail,
                    false,
                    Map.of("passedAssertions", passedAssertions.get(),
                            "failedAssertions", failedAssertions.get(),
                            "failedNodes", failedNodes,
                            "cleanupFailures", cleanupFailedCount.get())
            );
            emitEvent(journal, new RunEvent.RunFailed(
                    "RUN_FAILED", System.currentTimeMillis(), runId,
                    runError, totalDuration
            ));
        }

        try {
            journal.flush();
        } catch (IOException e) {
            LOG.warn("Failed to flush journal: {}", e.getMessage());
        }

        return new RunResult(
                runId, status, nodeResults.size(),
                passedAssertions.get(), failedAssertions.get(),
                nodeResults, totalDuration,
                runError
        );
    }

    /**
     * Execute a parallel group: children start together on a bounded pool and
     * run concurrently; the group passes only if every child passes. Child
     * events, evidence, retries, and expectError behave exactly as for
     * standalone operation nodes.
     */
    private RunResult.NodeResult executeParallelGroup(
            PlanNode.ParallelNode group,
            ExecutionPlan plan,
            RunJournal journal,
            ExpressionContext context,
            ConnectorContext connectorContext,
            Map<NodeId, NodeEvidence> evidenceMap,
            Map<NodeId, RunResult.NodeResult> childResultsOut,
            FaultSession faultSession,
            AtomicBoolean cancellation
    ) {
        long groupStart = System.currentTimeMillis();
        emitEvent(journal, new RunEvent.NodeStarted(
                "NODE_STARTED", System.currentTimeMillis(),
                plan.runId(), group.nodeId(), "parallel", null));

        int policyConcurrency = plan.targetPolicy() != null
                ? plan.targetPolicy().maxConcurrency() : group.children().size();
        int poolSize = Math.max(1, Math.min(group.children().size(), policyConcurrency));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "faultora-parallel");
            thread.setDaemon(true);
            return thread;
        });

        // A group timeout bounds the whole group: children still running when
        // it elapses are cancelled and reported as deadline failures.
        long groupDeadlineAtMs = group.deadlineMs() > 0
                ? groupStart + group.deadlineMs() : 0;

        ConcurrentMap<NodeId, NodeEvidence> childEvidence = new ConcurrentHashMap<>();
        List<Future<RunResult.NodeResult>> futures = new ArrayList<>();
        try {
            for (PlanNode.OperationNode child : group.children()) {
                futures.add(pool.submit(() -> executeNode(
                        child, plan, journal, context, connectorContext,
                        childEvidence, faultSession, cancellation)));
            }
            for (int i = 0; i < futures.size(); i++) {
                PlanNode.OperationNode child = group.children().get(i);
                try {
                    Future<RunResult.NodeResult> future = futures.get(i);
                    childResultsOut.put(child.nodeId(), groupDeadlineAtMs > 0
                            ? future.get(
                                    Math.max(0, groupDeadlineAtMs - System.currentTimeMillis()),
                                    TimeUnit.MILLISECONDS)
                            : future.get());
                } catch (TimeoutException e) {
                    futures.forEach(pending -> pending.cancel(true));
                    break;
                } catch (ExecutionException e) {
                    NormalizedError error = new NormalizedError(
                            NormalizedError.ErrorCategory.INTERNAL,
                            "EXECUTION_ERROR",
                            "Parallel child failed: " + e.getCause(),
                            false, Map.of());
                    childResultsOut.put(child.nodeId(), new RunResult.NodeResult(
                            child.nodeId(), "operation", RunResult.Status.ERROR,
                            -1, System.currentTimeMillis() - groupStart,
                            List.of(), error));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancellation.set(true);
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        evidenceMap.putAll(childEvidence);

        // Children without a result never finished: the group deadline elapsed
        // or the run was cancelled while they were in flight.
        for (PlanNode.OperationNode child : group.children()) {
            if (childResultsOut.containsKey(child.nodeId())) {
                continue;
            }
            long durationSoFar = System.currentTimeMillis() - groupStart;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.TIMEOUT,
                    "DEADLINE_EXCEEDED",
                    "Parallel child did not finish within the group timeout of "
                            + group.deadlineMs() + "ms",
                    false, Map.of());
            emitEvent(journal, new RunEvent.NodeFailed(
                    "NODE_FAILED", System.currentTimeMillis(),
                    plan.runId(), child.nodeId(), error, durationSoFar));
            childResultsOut.put(child.nodeId(), new RunResult.NodeResult(
                    child.nodeId(), "operation", RunResult.Status.FAILED,
                    -1, durationSoFar, List.of(), error));
        }

        long durationMs = System.currentTimeMillis() - groupStart;
        long failedChildren = group.children().stream()
                .map(child -> childResultsOut.get(child.nodeId()))
                .filter(result -> result == null
                        || result.status() != RunResult.Status.PASSED)
                .count();

        if (failedChildren == 0) {
            emitEvent(journal, new RunEvent.NodeCompleted(
                    "NODE_COMPLETED", System.currentTimeMillis(),
                    plan.runId(), group.nodeId(), durationMs, -1, 0));
            return new RunResult.NodeResult(
                    group.nodeId(), "parallel", RunResult.Status.PASSED,
                    -1, durationMs, List.of(), null);
        }

        NormalizedError groupError = new NormalizedError(
                NormalizedError.ErrorCategory.INTERNAL,
                "PARALLEL_CHILD_FAILED",
                failedChildren + " of " + group.children().size()
                        + " parallel children failed",
                false, Map.of("failedChildren", failedChildren));
        emitEvent(journal, new RunEvent.NodeFailed(
                "NODE_FAILED", System.currentTimeMillis(),
                plan.runId(), group.nodeId(), groupError, durationMs));
        return new RunResult.NodeResult(
                group.nodeId(), "parallel", RunResult.Status.FAILED,
                -1, durationMs, List.of(), groupError);
    }

    /**
     * Execute a repeat group: the children run in declaration order once per
     * iteration, each iteration exposing {@code repeat.index} and — for
     * data-driven repeats — {@code repeat.item}. Iteration results are recorded
     * under {@code <child>:<index>} and the last completed iteration is also
     * bound under the plain child ID, so assertions and later steps can target
     * it. The group stops at the first failing iteration.
     */
    private RunResult.NodeResult executeRepeatGroup(
            PlanNode.RepeatNode group,
            ExecutionPlan plan,
            RunJournal journal,
            ExpressionContext context,
            ConnectorContext connectorContext,
            Map<NodeId, NodeEvidence> evidenceMap,
            Map<NodeId, RunResult.NodeResult> childResultsOut,
            FaultSession faultSession,
            AtomicBoolean cancellation
    ) {
        long groupStart = System.currentTimeMillis();
        emitEvent(journal, new RunEvent.NodeStarted(
                "NODE_STARTED", System.currentTimeMillis(),
                plan.runId(), group.nodeId(), "repeat", null));

        long groupDeadlineAtMs = group.deadlineMs() > 0
                ? groupStart + group.deadlineMs() : 0;
        NormalizedError failure = null;
        int completedIterations = 0;

        iterations:
        for (int index = 0; index < group.iterations(); index++) {
            if (cancellation.get()) {
                failure = new NormalizedError(
                        NormalizedError.ErrorCategory.CANCELLED, "CANCELLED",
                        "Repeat group cancelled after " + completedIterations + " iterations",
                        false, Map.of());
                break;
            }
            if (groupDeadlineAtMs > 0 && System.currentTimeMillis() >= groupDeadlineAtMs) {
                failure = new NormalizedError(
                        NormalizedError.ErrorCategory.TIMEOUT, "DEADLINE_EXCEEDED",
                        "Repeat group timeout of " + group.deadlineMs() + "ms elapsed after "
                                + completedIterations + " of " + group.iterations()
                                + " iterations",
                        false, Map.of("completedIterations", completedIterations));
                break;
            }

            // Each iteration starts from the group's context: iterations are
            // independent, and only the last one is visible to later steps.
            var repeatBinding = MAPPER.createObjectNode();
            repeatBinding.put("index", index);
            if (group.items() != null) {
                repeatBinding.set("item", MAPPER.valueToTree(group.items().get(index)));
            }
            ExpressionContext iterationContext = context.withBinding("repeat", repeatBinding);

            for (PlanNode.OperationNode child : group.children()) {
                PlanNode.OperationNode iterationChild = withNodeId(
                        child, new NodeId(child.nodeId().value() + ITERATION_SEPARATOR + index));
                RunResult.NodeResult childResult = executeNode(
                        iterationChild, plan, journal, iterationContext, connectorContext,
                        evidenceMap, faultSession, cancellation);
                childResultsOut.put(iterationChild.nodeId(), childResult);
                iterationContext = bindStepOutput(
                        iterationChild, childResult, evidenceMap, iterationContext);

                // The plain child ID always points at the most recent iteration.
                NodeEvidence iterationEvidence = evidenceMap.get(iterationChild.nodeId());
                if (iterationEvidence != null) {
                    evidenceMap.put(child.nodeId(), iterationEvidence);
                }
                childResultsOut.put(child.nodeId(), new RunResult.NodeResult(
                        child.nodeId(), childResult.nodeType(), childResult.status(),
                        childResult.statusCode(), childResult.durationMs(),
                        childResult.assertions(), childResult.error()));

                if (childResult.status() != RunResult.Status.PASSED) {
                    failure = new NormalizedError(
                            NormalizedError.ErrorCategory.INTERNAL,
                            "REPEAT_ITERATION_FAILED",
                            "Iteration " + index + " failed at step "
                                    + child.nodeId().value(),
                            false, Map.of("iteration", index,
                                    "step", child.nodeId().value()));
                    break iterations;
                }
            }
            completedIterations++;
        }

        long durationMs = System.currentTimeMillis() - groupStart;
        if (failure == null) {
            emitEvent(journal, new RunEvent.NodeCompleted(
                    "NODE_COMPLETED", System.currentTimeMillis(),
                    plan.runId(), group.nodeId(), durationMs, -1, 0));
            return new RunResult.NodeResult(
                    group.nodeId(), "repeat", RunResult.Status.PASSED,
                    -1, durationMs, List.of(), null);
        }
        emitEvent(journal, new RunEvent.NodeFailed(
                "NODE_FAILED", System.currentTimeMillis(),
                plan.runId(), group.nodeId(), failure, durationMs));
        return new RunResult.NodeResult(
                group.nodeId(), "repeat", RunResult.Status.FAILED,
                -1, durationMs, List.of(), failure);
    }

    /**
     * Execute an eventually group: poll the child operation until every
     * condition holds in the same poll, the timeout budget is spent, or the
     * poll cap is reached. A failed request is an unsatisfied poll, not a
     * failure — that is what makes the block converge on eventual consistency.
     */
    private RunResult.NodeResult executeEventually(
            PlanNode.EventuallyNode group,
            ExecutionPlan plan,
            RunJournal journal,
            ExpressionContext context,
            ConnectorContext connectorContext,
            Map<NodeId, NodeEvidence> evidenceMap,
            Map<NodeId, RunResult.NodeResult> childResultsOut,
            AtomicBoolean cancellation
    ) {
        long groupStart = System.currentTimeMillis();
        NodeId groupId = group.nodeId();
        PlanNode.OperationNode child = group.child();

        emitEvent(journal, new RunEvent.NodeStarted(
                "NODE_STARTED", System.currentTimeMillis(),
                plan.runId(), groupId, "eventually", null));

        for (PlanNode.Condition condition : group.conditions()) {
            if (!assertionProviders.containsKey(condition.assertionType())) {
                NormalizedError error = new NormalizedError(
                        NormalizedError.ErrorCategory.VALIDATION, "VALIDATION",
                        "Unknown assertion type in until condition: "
                                + condition.assertionType(),
                        false, Map.of());
                emitEvent(journal, new RunEvent.NodeFailed(
                        "NODE_FAILED", System.currentTimeMillis(),
                        plan.runId(), groupId, error,
                        System.currentTimeMillis() - groupStart));
                return new RunResult.NodeResult(
                        groupId, "eventually", RunResult.Status.FAILED,
                        -1, System.currentTimeMillis() - groupStart, List.of(), error);
            }
        }

        emitEvent(journal, new RunEvent.NodeStarted(
                "NODE_STARTED", System.currentTimeMillis(),
                plan.runId(), child.nodeId(), "operation", child.operationId()));

        List<AssertionResult> conditionResults = List.of();
        NodeEvidence evidence = null;
        boolean satisfied = false;
        int attempt = 0;

        while (attempt < group.maxPolls()) {
            if (cancellation.get()) {
                break;
            }
            attempt++;

            evidence = new NodeEvidence(connectorContext.evidencePolicy());
            OperationResult result = executeOperation(child, context, connectorContext);
            populateEvidence(evidence, result);

            conditionResults = evaluateConditions(group, evidence);
            satisfied = !evidence.hasError() && conditionResults.stream()
                    .allMatch(condition -> condition.outcome() == AssertionResult.Outcome.PASS);

            long elapsedMs = System.currentTimeMillis() - groupStart;
            emitEvent(journal, new RunEvent.ConditionPolled(
                    "CONDITION_POLLED", System.currentTimeMillis(), plan.runId(),
                    groupId, attempt, group.maxPolls(), elapsedMs, satisfied,
                    pollDetail(evidence, conditionResults)));

            if (satisfied) {
                break;
            }
            long remainingMs = group.timeoutMs() - elapsedMs;
            if (remainingMs <= 0 || attempt >= group.maxPolls()) {
                break;
            }
            try {
                waitFor(Math.min(group.intervalMs(), remainingMs), cancellation);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancellation.set(true);
                break;
            }
        }

        long durationMs = System.currentTimeMillis() - groupStart;
        int statusCode = evidence == null ? -1 : evidence.statusCode().orElse(-1);
        if (evidence != null) {
            evidenceMap.put(child.nodeId(), evidence);
        }

        // Condition results share the order of the declared conditions, so the
        // journal records which condition produced which outcome.
        for (int i = 0; i < conditionResults.size(); i++) {
            AssertionResult condition = conditionResults.get(i);
            emitEvent(journal, new RunEvent.AssertionEvaluated(
                    "ASSERTION_EVALUATED", System.currentTimeMillis(),
                    plan.runId(), groupId, group.conditions().get(i).assertionType(),
                    condition.outcome().name(), condition.message()));
        }

        if (satisfied) {
            emitEvent(journal, new RunEvent.NodeCompleted(
                    "NODE_COMPLETED", System.currentTimeMillis(),
                    plan.runId(), child.nodeId(), durationMs, statusCode, 0));
            childResultsOut.put(child.nodeId(), new RunResult.NodeResult(
                    child.nodeId(), "operation", RunResult.Status.PASSED,
                    statusCode, durationMs, List.of(), null));
            emitEvent(journal, new RunEvent.NodeCompleted(
                    "NODE_COMPLETED", System.currentTimeMillis(),
                    plan.runId(), groupId, durationMs, statusCode, 0));
            return new RunResult.NodeResult(
                    groupId, "eventually", RunResult.Status.PASSED,
                    statusCode, durationMs, conditionResults, null);
        }

        NormalizedError error = new NormalizedError(
                cancellation.get()
                        ? NormalizedError.ErrorCategory.CANCELLED
                        : NormalizedError.ErrorCategory.TIMEOUT,
                cancellation.get() ? "CANCELLED" : "EVENTUALLY_TIMEOUT",
                "Conditions were not satisfied within " + group.timeoutMs()
                        + "ms after " + attempt + " poll" + (attempt == 1 ? "" : "s")
                        + ": " + pollDetail(evidence, conditionResults),
                false, Map.of("polls", attempt, "timeoutMs", group.timeoutMs()));
        emitEvent(journal, new RunEvent.NodeFailed(
                "NODE_FAILED", System.currentTimeMillis(),
                plan.runId(), child.nodeId(), error, durationMs));
        childResultsOut.put(child.nodeId(), new RunResult.NodeResult(
                child.nodeId(), "operation", RunResult.Status.FAILED,
                statusCode, durationMs, List.of(), error));
        emitEvent(journal, new RunEvent.NodeFailed(
                "NODE_FAILED", System.currentTimeMillis(),
                plan.runId(), groupId, error, durationMs));
        return new RunResult.NodeResult(
                groupId, "eventually", RunResult.Status.FAILED,
                statusCode, durationMs, conditionResults, error);
    }

    private List<AssertionResult> evaluateConditions(
            PlanNode.EventuallyNode group, NodeEvidence evidence) {
        if (evidence.hasError()) {
            return List.of();
        }
        List<AssertionResult> results = new ArrayList<>();
        for (PlanNode.Condition condition : group.conditions()) {
            AssertionProvider provider = assertionProviders.get(condition.assertionType());
            AssertionContext conditionContext = new AssertionContext(
                    group.nodeId().value(), condition.params());
            results.add(provider.evaluate(
                    condition.assertionType(), condition.params(),
                    evidence, conditionContext));
        }
        return results;
    }

    /** Short, sanitized reason a poll did not satisfy its conditions. */
    private static String pollDetail(
            NodeEvidence evidence, List<AssertionResult> conditionResults) {
        if (evidence == null) {
            return "no poll completed";
        }
        if (evidence.hasError()) {
            return "request failed: " + evidence.error()
                    .map(NormalizedError::code).orElse("unknown");
        }
        return conditionResults.stream()
                .filter(condition -> condition.outcome() != AssertionResult.Outcome.PASS)
                .map(AssertionResult::message)
                .findFirst()
                .orElse("all conditions satisfied");
    }

    private static PlanNode.OperationNode withNodeId(
            PlanNode.OperationNode node, NodeId nodeId) {
        return new PlanNode.OperationNode(
                nodeId, node.operationId(), node.operation(),
                node.inputExpressions(), node.outputBinding(), node.expectError(),
                node.retrySpec(), node.dependencies(), node.safety(),
                node.deadlineMs(), node.maxRetries());
    }

    private RunResult.NodeResult executeNode(
            PlanNode node,
            ExecutionPlan plan,
            RunJournal journal,
            ExpressionContext context,
            ConnectorContext connectorContext,
            Map<NodeId, NodeEvidence> evidenceMap,
            FaultSession faultSession,
            AtomicBoolean cancellation
    ) {
        NodeId nodeId = node.nodeId();
        long nodeStart = System.currentTimeMillis();

        emitEvent(journal, new RunEvent.NodeStarted(
                "NODE_STARTED", System.currentTimeMillis(),
                plan.runId(), nodeId, nodeType(node), operationId(node)
        ));

        try {
            if (cancellation.get()) {
                return nodeFailed(nodeId, node, "Node cancelled",
                        NormalizedError.ErrorCategory.CANCELLED, nodeStart);
            }

            NodeEvidence evidence = new NodeEvidence(connectorContext.evidencePolicy());

            switch (node) {
                case PlanNode.OperationNode opNode -> {
                    if (opNode.operation() == null) {
                        long waitMs = toLong(opNode.inputExpressions().get("waitMs"));
                        if (waitMs <= 0) {
                            return nodeFailed(nodeId, node, "Wait duration must be positive",
                                    NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                        }
                        waitFor(waitMs, cancellation);
                        if (cancellation.get()) {
                            return nodeFailed(nodeId, node, "Wait cancelled",
                                    NormalizedError.ErrorCategory.CANCELLED, nodeStart);
                        }
                        evidence.durationMs(System.currentTimeMillis() - nodeStart);
                    } else {
                        // Execute operation via connector, retrying retryable
                        // errors when the node carries a retry spec.
                        OperationResult result = executeWithRetry(
                                opNode, plan, journal, context, connectorContext, cancellation);
                        populateEvidence(evidence, result);
                    }
                }
                case PlanNode.AssertionNode assertNode -> {
                    // Evaluate assertion against target node's evidence
                    NodeEvidence targetEvidence = assertNode.targetNode() != null ?
                            evidenceMap.get(assertNode.targetNode()) : evidence;
                    if (targetEvidence == null) {
                        targetEvidence = evidence;
                    }

                    AssertionProvider provider = assertionProviders.get(assertNode.assertionType());
                    if (provider == null) {
                        return nodeFailed(nodeId, node,
                                "Unknown assertion type: " + assertNode.assertionType(),
                                NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                    }

                    AssertionContext assertCtx = new AssertionContext(
                            nodeId.value(), assertNode.params());
                    AssertionResult result = provider.evaluate(
                            assertNode.assertionType(), assertNode.params(),
                            targetEvidence, assertCtx);

                    evidence.durationMs(System.currentTimeMillis() - nodeStart);

                    emitEvent(journal, new RunEvent.AssertionEvaluated(
                            "ASSERTION_EVALUATED", System.currentTimeMillis(),
                            plan.runId(), nodeId, assertNode.assertionType(),
                            result.outcome().name(), result.message()
                    ));

                    evidenceMap.put(nodeId, evidence);

                    // Treat INDETERMINATE as failure — an assertion that cannot be
                    // evaluated (e.g., missing evidence) must not silently pass.
                    RunResult.Status nodeStatus =
                            (result.outcome() == AssertionResult.Outcome.FAIL
                             || result.outcome() == AssertionResult.Outcome.INDETERMINATE)
                                    ? RunResult.Status.FAILED : RunResult.Status.PASSED;

                    emitEvent(journal, new RunEvent.NodeCompleted(
                            "NODE_COMPLETED", System.currentTimeMillis(),
                            plan.runId(), nodeId, evidence.durationMs(),
                            0, 0
                    ));

                    return new RunResult.NodeResult(
                            nodeId, nodeType(node), nodeStatus,
                            0, evidence.durationMs(),
                            List.of(result), null
                    );
                }
                case PlanNode.FaultStartNode faultNode -> {
                    FaultProvider provider = findFaultProvider(faultNode.faultType());
                    if (provider == null) {
                        return nodeFailed(nodeId, node,
                                "No fault provider supports fault type: " + faultNode.faultType(),
                                NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                    }
                    ActiveFault fault;
                    try {
                        fault = faultSession.start(
                                provider, nodeId, faultNode.faultType(),
                                faultNode.targetScope(), faultNode.params(),
                                faultNode.durationMs());
                    } catch (IllegalArgumentException e) {
                        return nodeFailed(nodeId, node,
                                "Fault injection rejected: " + e.getMessage(),
                                NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                    }
                    emitEvent(journal, new RunEvent.FaultInjected(
                            "FAULT_INJECTED", System.currentTimeMillis(),
                            plan.runId(), fault.handle(), fault.faultType(),
                            fault.targetScope(), fault.hardExpiryMs()
                    ));
                    evidence.durationMs(System.currentTimeMillis() - nodeStart);
                }
                case PlanNode.FaultStopNode faultStopNode -> {
                    String handle = faultSession.handleForNode(faultStopNode.faultStartNode());
                    if (handle == null) {
                        return nodeFailed(nodeId, node,
                                "No fault was started by node: "
                                        + faultStopNode.faultStartNode().value(),
                                NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                    }
                    // Idempotent: an already-expired fault is a successful stop.
                    faultSession.rollback(handle, FaultSession.REASON_FAULT_STOP);
                    evidence.durationMs(System.currentTimeMillis() - nodeStart);
                }
                case PlanNode.ParallelNode ignored -> {
                    // Grouping nodes are dispatched by the run loop, never here.
                    return nodeFailed(nodeId, node,
                            "Parallel group reached single-node execution",
                            NormalizedError.ErrorCategory.INTERNAL, nodeStart);
                }
                case PlanNode.RepeatNode ignored -> {
                    return nodeFailed(nodeId, node,
                            "Repeat group reached single-node execution",
                            NormalizedError.ErrorCategory.INTERNAL, nodeStart);
                }
                case PlanNode.EventuallyNode ignored -> {
                    return nodeFailed(nodeId, node,
                            "Eventually group reached single-node execution",
                            NormalizedError.ErrorCategory.INTERNAL, nodeStart);
                }
                case PlanNode.CleanupNode cleanupNode -> {
                    // Look up the operation definition from the catalog
                    dev.faultora.model.catalog.OperationDefinition opDef =
                            plan.catalog().operations().stream()
                                    .filter(op -> op.id().equals(cleanupNode.operationId()))
                                    .findFirst().orElse(null);
                    if (opDef == null) {
                        return nodeFailed(nodeId, node,
                                "Cleanup operation not found in catalog: " + cleanupNode.operationId(),
                                NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                    }
                    PlanNode.OperationNode asOp = new PlanNode.OperationNode(
                            cleanupNode.nodeId(), cleanupNode.operationId(),
                            opDef, cleanupNode.inputExpressions(), null,
                            cleanupNode.dependencies(), cleanupNode.safety(),
                            cleanupNode.deadlineMs(), cleanupNode.maxRetries()
                    );
                    OperationResult result = executeOperation(asOp, context, connectorContext);
                    populateEvidence(evidence, result);
                }
            }

            evidenceMap.put(nodeId, evidence);

            long durationMs = System.currentTimeMillis() - nodeStart;

            // Emit evidence captured event
            if (evidence.responseBody().isPresent()) {
                byte[] bodyBytes = evidence.responseBody().get();
                String digest = "sha256:" + sha256Hex(bodyBytes);
                emitEvent(journal, new RunEvent.EvidenceCaptured(
                        "EVIDENCE_CAPTURED", System.currentTimeMillis(),
                        plan.runId(), nodeId, "http-response",
                        digest, bodyBytes.length
                ));
            }

            boolean expectError = node instanceof PlanNode.OperationNode op && op.expectError();

            if (evidence.hasError() && !expectError) {
                NormalizedError error = evidence.error().orElse(null);
                emitEvent(journal, new RunEvent.NodeFailed(
                        "NODE_FAILED", System.currentTimeMillis(),
                        plan.runId(), nodeId, error, durationMs
                ));
                return new RunResult.NodeResult(
                        nodeId, nodeType(node), RunResult.Status.FAILED,
                        evidence.statusCode().orElse(-1), durationMs,
                        List.of(), error
                );
            }

            if (!evidence.hasError() && expectError) {
                NormalizedError error = new NormalizedError(
                        NormalizedError.ErrorCategory.VALIDATION,
                        "EXPECTED_ERROR",
                        "Step declared expectError but the operation succeeded",
                        false, Map.of());
                emitEvent(journal, new RunEvent.NodeFailed(
                        "NODE_FAILED", System.currentTimeMillis(),
                        plan.runId(), nodeId, error, durationMs
                ));
                return new RunResult.NodeResult(
                        nodeId, nodeType(node), RunResult.Status.FAILED,
                        evidence.statusCode().orElse(-1), durationMs,
                        List.of(), error
                );
            }

            emitEvent(journal, new RunEvent.NodeCompleted(
                    "NODE_COMPLETED", System.currentTimeMillis(),
                    plan.runId(), nodeId, durationMs,
                    evidence.statusCode().orElse(-1),
                    evidence.responseBody().map(b -> (long) b.length).orElse(0L)
            ));

            // expectError with an actual error: the node passes, and the
            // expected error stays visible in the result for the reports.
            return new RunResult.NodeResult(
                    nodeId, nodeType(node), RunResult.Status.PASSED,
                    evidence.statusCode().orElse(-1), durationMs,
                    List.of(), expectError ? evidence.error().orElse(null) : null
            );

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - nodeStart;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.INTERNAL,
                    "EXECUTION_ERROR",
                    "Node execution failed: " + e.getMessage(),
                    false, Map.of()
            );

            emitEvent(journal, new RunEvent.NodeFailed(
                    "NODE_FAILED", System.currentTimeMillis(),
                    plan.runId(), nodeId, error, durationMs
            ));

            return new RunResult.NodeResult(
                    nodeId, nodeType(node), RunResult.Status.ERROR,
                    -1, durationMs, List.of(), error
            );
        }
    }

    private OperationResult executeWithRetry(
            PlanNode.OperationNode node,
            ExecutionPlan plan,
            RunJournal journal,
            ExpressionContext context,
            ConnectorContext connectorContext,
            AtomicBoolean cancellation
    ) throws InterruptedException {
        PlanNode.RetrySpec retry = node.retrySpec();
        int maxAttempts = retry == null ? 1 : retry.maxAttempts();

        OperationResult result = executeOperation(node, context, connectorContext);
        for (int failedAttempt = 1; failedAttempt < maxAttempts; failedAttempt++) {
            if (result.error() == null || !result.error().retryable() || cancellation.get()) {
                return result;
            }
            long delayMs = retryDelayMs(retry, plan.seed(), node.nodeId(), failedAttempt);
            emitEvent(journal, new RunEvent.OperationRetried(
                    "OPERATION_RETRIED", System.currentTimeMillis(), plan.runId(),
                    node.nodeId(), failedAttempt, maxAttempts, delayMs,
                    result.error().code()));
            if (delayMs > 0) {
                waitFor(delayMs, cancellation);
            }
            if (cancellation.get()) {
                return result;
            }
            result = executeOperation(node, context, connectorContext);
        }
        return result;
    }

    /**
     * Backoff before the next attempt: exponential in the failed-attempt
     * number, jittered deterministically from the run seed and node ID, and
     * capped at {@code maxBackoffMs} when set. Identical seeded runs produce
     * identical delays.
     */
    static long retryDelayMs(
            PlanNode.RetrySpec retry, long seed,
            NodeId nodeId, int failedAttempt
    ) {
        double delay = retry.backoffMs()
                * Math.pow(retry.backoffMultiplier(), failedAttempt - 1);
        Random jitterSource = new Random(
                seed ^ (31L * nodeId.value().hashCode()) ^ failedAttempt);
        delay *= 0.9 + 0.2 * jitterSource.nextDouble();
        if (retry.maxBackoffMs() > 0) {
            delay = Math.min(delay, retry.maxBackoffMs());
        }
        return Math.max(0, Math.round(delay));
    }

    private OperationResult executeOperation(
            PlanNode.OperationNode node,
            ExpressionContext context,
            ConnectorContext connectorContext
    ) {
        // Resolve input expressions
        Map<String, Object> resolvedInputs = expressionEvaluator.resolveInputs(
                node.inputExpressions(), context);

        // Find the connector for this operation's protocol
        String protocol = node.operation().protocol().value();
        Connector connector = connectors.get(protocol);
        if (connector == null) {
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.INTERNAL,
                    "NO_CONNECTOR",
                    "No connector for protocol: " + protocol,
                    false, Map.of());
            return OperationResult.failure(error, 0);
        }

        // Prepare target
        var target = findTarget(node.operation().target(), connectorContext);
        if (target == null) {
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.VALIDATION,
                    "TARGET_NOT_FOUND",
                    "Target not found: " + node.operation().target().value(),
                    false, Map.of());
            return OperationResult.failure(error, 0);
        }

        ConnectorContext operationContext = connectorContext;
        if (node.deadlineMs() > 0) {
            long requestTimeout = Math.min(
                    connectorContext.requestTimeoutMs(), node.deadlineMs());
            long totalTimeout = Math.min(
                    connectorContext.totalTimeoutMs(), node.deadlineMs());
            operationContext = new ConnectorContext(
                    connectorContext.evidencePolicy(),
                    connectorContext.secretResolver(),
                    Math.min(connectorContext.connectTimeoutMs(), requestTimeout),
                    requestTimeout,
                    totalTimeout,
                    connectorContext.config());
        }

        var prepared = connector.prepare(target, operationContext);
        try {
            return connector.execute(prepared, node.operation(), resolvedInputs, operationContext);
        } finally {
            connector.release(prepared);
        }
    }

    /**
     * Bind a passed operation node's output under {@code steps.<outputAs>}:
     * an object with {@code status}, {@code body} (parsed JSON, when captured),
     * and {@code headers} (already filtered by the evidence policy).
     */
    private ExpressionContext bindStepOutput(
            PlanNode node,
            RunResult.NodeResult result,
            Map<NodeId, NodeEvidence> evidenceMap,
            ExpressionContext context
    ) {
        if (!(node instanceof PlanNode.OperationNode op)
                || op.outputBinding() == null || op.outputBinding().isBlank()
                || op.operation() == null
                || result.status() != RunResult.Status.PASSED) {
            return context;
        }
        NodeEvidence evidence = evidenceMap.get(node.nodeId());
        if (evidence == null) {
            return context;
        }
        var output = MAPPER.createObjectNode();
        output.put("status", evidence.statusCode().orElse(-1));
        evidence.responseJson().ifPresent(body -> output.set("body", body));
        output.set("headers", MAPPER.valueToTree(evidence.responseHeaders()));
        return context.withStepOutput(op.outputBinding(), output);
    }

    private FaultProvider findFaultProvider(String faultType) {
        for (FaultProvider provider : faultProviders.values()) {
            if (provider.capabilities().contains(faultType)) {
                return provider;
            }
        }
        return null;
    }

    private dev.faultora.model.catalog.TargetDefinition findTarget(
            dev.faultora.model.identifier.TargetId targetId,
            ConnectorContext context
    ) {
        // In M1, we construct a basic target from the context config
        // In M2+, this will resolve from the catalog
        String baseUrl = (String) context.config().getOrDefault("baseUrl", "http://localhost:8080");
        return new dev.faultora.model.catalog.TargetDefinition(
                targetId, targetId.value(), baseUrl,
                List.of(new dev.faultora.model.identifier.ProtocolId("http")),
                List.of(), Map.of()
        );
    }

    private void populateEvidence(NodeEvidence evidence, OperationResult result) {
        evidence.statusCode(result.statusCode());
        evidence.headers(result.headers());
        // Extract content type from response headers for contentTypeAllowlist enforcement
        String contentType = null;
        if (result.headers() != null) {
            List<String> ctValues = result.headers().get("content-type");
            if (ctValues != null && !ctValues.isEmpty()) {
                contentType = ctValues.get(0);
            }
        }
        evidence.body(result.body(), contentType);
        evidence.durationMs(result.durationMs());
        evidence.error(result.error());
    }

    private boolean dependenciesSatisfied(
            PlanNode node,
            Map<NodeId, RunResult.NodeResult> results
    ) {
        for (NodeId dep : node.dependencies()) {
            RunResult.NodeResult depResult = results.get(dep);
            if (depResult == null) {
                return false; // Dependency not yet executed
            }
            if (depResult.status() != RunResult.Status.PASSED) {
                return false;
            }
        }
        return true;
    }

    private RunResult.NodeResult nodeFailed(
            NodeId nodeId,
            PlanNode node,
            String message,
            NormalizedError.ErrorCategory category,
            long startTime
    ) {
        long durationMs = System.currentTimeMillis() - startTime;
        NormalizedError error = new NormalizedError(
                category, category.name(), message, false, Map.of());
        return new RunResult.NodeResult(
                nodeId, nodeType(node), RunResult.Status.FAILED,
                -1, durationMs, List.of(), error
        );
    }

    private String nodeType(PlanNode node) {
        return switch (node) {
            case PlanNode.OperationNode ignored -> "operation";
            case PlanNode.ParallelNode ignored -> "parallel";
            case PlanNode.RepeatNode ignored -> "repeat";
            case PlanNode.EventuallyNode ignored -> "eventually";
            case PlanNode.AssertionNode ignored -> "assertion";
            case PlanNode.FaultStartNode ignored -> "fault-start";
            case PlanNode.FaultStopNode ignored -> "fault-stop";
            case PlanNode.CleanupNode ignored -> "cleanup";
        };
    }

    private dev.faultora.model.identifier.OperationId operationId(PlanNode node) {
        return switch (node) {
            case PlanNode.OperationNode op -> op.operationId();
            case PlanNode.CleanupNode cl -> cl.operationId();
            default -> null;
        };
    }

    private long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) return Long.parseLong(s);
        return 0;
    }

    private void waitFor(long waitMs, AtomicBoolean cancellation) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        while (!cancellation.get()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) return;
            long sleepMs = Math.max(
                    1, Math.min(100, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            Thread.sleep(sleepMs);
        }
    }

    private void emitEvent(RunJournal journal, RunEvent event) {
        try {
            journal.append(event);
        } catch (IOException e) {
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("SHA-256 not available: {}", e.getMessage());
            return "error";
        }
    }
}
