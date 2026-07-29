package dev.faultora.engine;

import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.exec.EventuallyGroupExecutor;
import dev.faultora.engine.exec.GroupOutcome;
import dev.faultora.engine.exec.JournalWriter;
import dev.faultora.engine.exec.NodeContext;
import dev.faultora.engine.exec.NodeExecutor;
import dev.faultora.engine.exec.NodeResults;
import dev.faultora.engine.exec.OperationInvoker;
import dev.faultora.engine.exec.ParallelGroupExecutor;
import dev.faultora.engine.exec.RepeatGroupExecutor;
import dev.faultora.engine.exec.StepOutputBinder;
import dev.faultora.engine.fault.FaultSession;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.RunId;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.AssertionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local execution engine.
 * <p>
 * This class owns scheduling and nothing else: it walks the plan in
 * topological order, decides whether a node may start, hands it to the
 * executor that knows how to run it, and turns the accumulated results into a
 * verdict. How a node executes belongs to
 * {@link dev.faultora.engine.exec.NodeExecutor} and the group executors beside
 * it.
 */
public class LocalEngine {

    private final NodeExecutor nodeExecutor;
    private final ParallelGroupExecutor parallelGroups;
    private final RepeatGroupExecutor repeatGroups;
    private final EventuallyGroupExecutor eventuallyGroups;

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
        OperationInvoker invoker = new OperationInvoker(connectors);
        Map<String, AssertionProvider> assertions = Map.copyOf(assertionProviders);
        this.nodeExecutor = new NodeExecutor(
                invoker, assertions, Map.copyOf(faultProviders));
        this.parallelGroups = new ParallelGroupExecutor(nodeExecutor);
        this.repeatGroups = new RepeatGroupExecutor(nodeExecutor);
        this.eventuallyGroups = new EventuallyGroupExecutor(invoker, assertions);
    }

    /**
     * Execute a compiled plan.
     *
     * @param plan             the execution plan
     * @param journal          run journal for events
     * @param context          expression evaluation context
     * @param connectorContext connector context for operations
     * @param cancellation     cancellation flag
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
        JournalWriter writer = new JournalWriter(journal, runId);

        writer.runStarted(plan.scenarioDigest(), plan.catalogDigest(), plan.seed());

        Map<NodeId, RunResult.NodeResult> nodeResults = new LinkedHashMap<>();
        Map<NodeId, NodeEvidence> evidence = new LinkedHashMap<>();
        Tally tally = new Tally();

        List<PlanNode> nodes = plan.topologicalOrder();

        // Fault rollback is guaranteed by the session: fault-stop nodes,
        // the hard-expiry watchdog, and the end-of-run sweep in close().
        FaultSession faultSession = new FaultSession(
                (fault, rollbackStatus) -> writer.faultRolledBack(fault.handle(), rollbackStatus));

        // Cleanup obligations are collected before execution starts: a
        // deadline or cancellation must never drop them.
        List<PlanNode> cleanupNodes = nodes.stream()
                .filter(LocalEngine::belongsToCleanup)
                .toList();

        NodeContext nodeContext = new NodeContext(
                plan, writer, connectorContext, evidence, faultSession,
                new dev.faultora.schema.SchemaCatalog(plan.catalog().schemas()),
                cancellation);

        // Step outputs accumulate into the expression context as nodes finish.
        ExpressionContext currentContext = context;
        boolean deadlineExceeded = false;
        try {
            for (PlanNode node : nodes) {
                if (cancellation.get()) {
                    break;
                }
                if (belongsToCleanup(node)) {
                    continue;
                }
                // The scenario deadline bounds the whole run: no further node
                // starts once it elapses, and cleanup still runs below.
                if (plan.scenarioTimeoutMs() > 0
                        && System.currentTimeMillis() - startTime >= plan.scenarioTimeoutMs()) {
                    deadlineExceeded = true;
                    break;
                }
                NodeId unmet = unmetDependency(node, nodeResults);
                if (unmet != null) {
                    String reason = "depends on " + unmet.value()
                            + ", which did not pass";
                    writer.nodeSkipped(node.nodeId(), reason);
                    nodeResults.put(node.nodeId(), new RunResult.NodeResult(
                            node.nodeId(), NodeResults.typeOf(node), RunResult.Status.SKIPPED,
                            -1, 0, List.of(), null));
                    continue;
                }

                currentContext = runNode(
                        node, nodeContext, currentContext, nodeResults, evidence);
                tally.record(nodeResults.get(node.nodeId()));
            }

            if (!cancellation.get()) {
                runCleanup(cleanupNodes, nodeContext, currentContext, nodeResults, tally);
            }
        } finally {
            faultSession.close();
        }

        return finish(plan, writer, nodeResults, tally, startTime, cancellation, deadlineExceeded);
    }

    /**
     * Run one node and publish what later nodes may observe: its result, and
     * the step outputs it bound. Group nodes also publish the results of the
     * children they ran.
     */
    private ExpressionContext runNode(
            PlanNode node,
            NodeContext nodeContext,
            ExpressionContext currentContext,
            Map<NodeId, RunResult.NodeResult> nodeResults,
            Map<NodeId, NodeEvidence> evidence
    ) {
        ExpressionContext context = currentContext;
        final ExpressionContext entryContext = currentContext;
        RunResult.NodeResult result;

        switch (node) {
            case PlanNode.ParallelNode parallel -> {
                GroupOutcome outcome = guarded(
                        parallel, nodeContext, () ->
                                parallelGroups.execute(parallel, nodeContext, entryContext));
                result = outcome.group();
                nodeResults.putAll(outcome.children());
                // Children are bound in declaration order once the whole group
                // finished; they never observe each other's outputs.
                context = bindChildren(parallel.children(), outcome, evidence, context);
            }
            case PlanNode.RepeatNode repeat -> {
                GroupOutcome outcome = guarded(
                        repeat, nodeContext, () ->
                                repeatGroups.execute(repeat, nodeContext, entryContext));
                result = outcome.group();
                nodeResults.putAll(outcome.children());
                // The last completed iteration is what later steps observe.
                context = bindChildren(repeat.children(), outcome, evidence, context);
            }
            case PlanNode.EventuallyNode eventually -> {
                GroupOutcome outcome = guarded(
                        eventually, nodeContext, () ->
                                eventuallyGroups.execute(eventually, nodeContext, entryContext));
                result = outcome.group();
                nodeResults.putAll(outcome.children());
                context = bindChildren(
                        List.of(eventually.child()), outcome, evidence, context);
            }
            default -> {
                result = nodeExecutor.execute(node, nodeContext, context);
                context = StepOutputBinder.bind(node, result, evidence, context);
            }
        }

        nodeResults.put(node.nodeId(), result);
        return context;
    }

    /**
     * Run a group, turning an unexpected failure into a failed group.
     * <p>
     * A single node is already guarded by its executor; a group was not, so an
     * exception from a provider or a malformed parameter escaped the run loop
     * and took the terminal event and the cleanup phase with it. Whatever goes
     * wrong inside a group, the run still reports its own outcome.
     */
    private GroupOutcome guarded(
            PlanNode group, NodeContext context, java.util.function.Supplier<GroupOutcome> body) {
        try {
            return body.get();
        } catch (RuntimeException unexpected) {
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.INTERNAL, "GROUP_EXECUTION_ERROR",
                    "Group execution failed: " + unexpected, false, Map.of());
            context.journal().nodeFailed(group.nodeId(), error, 0);
            return new GroupOutcome(new RunResult.NodeResult(
                    group.nodeId(), NodeResults.typeOf(group), RunResult.Status.FAILED,
                    -1, 0, List.of(), error), Map.of());
        }
    }

    private ExpressionContext bindChildren(
            List<PlanNode.OperationNode> children,
            GroupOutcome outcome,
            Map<NodeId, NodeEvidence> evidence,
            ExpressionContext context
    ) {
        ExpressionContext bound = context;
        for (PlanNode.OperationNode child : children) {
            RunResult.NodeResult childResult = outcome.children().get(child.nodeId());
            if (childResult != null) {
                bound = StepOutputBinder.bind(child, childResult, evidence, bound);
            }
        }
        return bound;
    }

    /** Cleanup runs after every other node, whether or not the run succeeded. */
    private void runCleanup(
            List<PlanNode> cleanupNodes,
            NodeContext nodeContext,
            ExpressionContext context,
            Map<NodeId, RunResult.NodeResult> nodeResults,
            Tally tally
    ) {
        nodeContext.journal().cleanupStarted(cleanupNodes.size());
        long cleanupStart = System.currentTimeMillis();
        int succeeded = 0;
        int failed = 0;

        for (PlanNode cleanupNode : cleanupNodes) {
            RunResult.NodeResult result = nodeExecutor.execute(cleanupNode, nodeContext, context);
            nodeResults.put(cleanupNode.nodeId(), result);
            if (result.status() == RunResult.Status.PASSED) {
                succeeded++;
            } else {
                failed++;
                tally.cleanupFailed();
            }
        }

        nodeContext.journal().cleanupCompleted(
                succeeded, failed, System.currentTimeMillis() - cleanupStart);
    }

    private RunResult finish(
            ExecutionPlan plan,
            JournalWriter writer,
            Map<NodeId, RunResult.NodeResult> nodeResults,
            Tally tally,
            long startTime,
            AtomicBoolean cancellation,
            boolean deadlineExceeded
    ) {
        long totalDuration = System.currentTimeMillis() - startTime;

        RunResult.Status status;
        if (cancellation.get()) {
            status = RunResult.Status.CANCELLED;
        } else if (tally.hasFailure() || deadlineExceeded) {
            status = RunResult.Status.FAILED;
        } else {
            status = RunResult.Status.PASSED;
        }

        NormalizedError runError = null;
        if (status == RunResult.Status.PASSED) {
            writer.runCompleted(nodeResults.size(), tally.passedAssertions(),
                    tally.failedAssertions(), totalDuration);
        } else {
            long failedNodes = nodeResults.values().stream()
                    .filter(result -> result.status() == RunResult.Status.FAILED
                            || result.status() == RunResult.Status.ERROR)
                    .count();
            String detail = "Run " + status.name().toLowerCase(Locale.ROOT)
                    + ": " + failedNodes + " failed nodes, "
                    + tally.failedAssertions() + " failed assertions";
            if (deadlineExceeded) {
                detail += "; scenario deadline of " + plan.scenarioTimeoutMs()
                        + "ms elapsed before every node ran";
            }
            runError = new NormalizedError(
                    status == RunResult.Status.CANCELLED
                            ? NormalizedError.ErrorCategory.CANCELLED
                            : NormalizedError.ErrorCategory.INTERNAL,
                    status == RunResult.Status.CANCELLED ? "RUN_CANCELLED"
                            : (deadlineExceeded ? "SCENARIO_DEADLINE_EXCEEDED" : "RUN_FAILED"),
                    detail,
                    false,
                    Map.of("passedAssertions", tally.passedAssertions(),
                            "failedAssertions", tally.failedAssertions(),
                            "failedNodes", failedNodes,
                            "cleanupFailures", tally.cleanupFailures()));
            writer.runFailed(runError, totalDuration);
        }

        writer.flush();

        return new RunResult(
                plan.runId(), status, nodeResults.size(),
                tally.passedAssertions(), tally.failedAssertions(),
                nodeResults, totalDuration, runError);
    }

    /**
     * Whether a node runs in the cleanup phase.
     * <p>
     * A wait declared in cleanup is part of cleanup: it exists to let an
     * obligation outlive something — an injected fault window, a settling
     * target — and running it in the main phase would defeat exactly that.
     */
    private static boolean belongsToCleanup(PlanNode node) {
        return node instanceof PlanNode.CleanupNode
                || (node instanceof PlanNode.WaitNode wait && wait.cleanup());
    }

    /** The first dependency that did not pass, or null when all did. */
    private NodeId unmetDependency(
            PlanNode node, Map<NodeId, RunResult.NodeResult> results) {
        for (NodeId dependency : node.dependencies()) {
            RunResult.NodeResult result = results.get(dependency);
            if (result == null || result.status() != RunResult.Status.PASSED) {
                return dependency;
            }
        }
        return null;
    }

    /**
     * Running totals behind the final verdict. An assertion that could not be
     * evaluated counts as failed: unevaluatable is not satisfied.
     */
    private static final class Tally {
        private final List<RunResult.NodeResult> failures = new ArrayList<>();
        private int passedAssertions;
        private int failedAssertions;
        private int cleanupFailures;

        void record(RunResult.NodeResult result) {
            if (result == null) {
                return;
            }
            if (result.status() == RunResult.Status.FAILED
                    || result.status() == RunResult.Status.ERROR) {
                failures.add(result);
            }
            for (AssertionResult assertion : result.assertions()) {
                if (assertion.outcome() == AssertionResult.Outcome.PASS) {
                    passedAssertions++;
                } else {
                    failedAssertions++;
                }
            }
        }

        void cleanupFailed() {
            cleanupFailures++;
        }

        boolean hasFailure() {
            return !failures.isEmpty() || failedAssertions > 0 || cleanupFailures > 0;
        }

        int passedAssertions() {
            return passedAssertions;
        }

        int failedAssertions() {
            return failedAssertions;
        }

        int cleanupFailures() {
            return cleanupFailures;
        }
    }
}
