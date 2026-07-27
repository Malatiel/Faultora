package dev.faultora.engine.exec;

import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.spec.expression.ExpressionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the children of a parallel group concurrently.
 * <p>
 * Children start together on a pool bounded by the execution policy and all
 * run to completion even if a sibling fails — a race is only meaningful when
 * every participant actually ran. A group timeout is a hard bound: children
 * still in flight when it elapses are cancelled and reported as deadline
 * failures rather than silently awaited.
 */
public final class ParallelGroupExecutor {

    private final NodeExecutor nodeExecutor;

    public ParallelGroupExecutor(NodeExecutor nodeExecutor) {
        this.nodeExecutor = nodeExecutor;
    }

    public GroupOutcome execute(
            PlanNode.ParallelNode group,
            NodeContext context,
            ExpressionContext expressionContext
    ) {
        long groupStart = System.currentTimeMillis();
        context.journal().nodeStarted(group.nodeId(), "parallel", null);

        Map<NodeId, RunResult.NodeResult> childResults = new LinkedHashMap<>();
        ConcurrentMap<NodeId, NodeEvidence> childEvidence = new ConcurrentHashMap<>();
        NodeContext childContext = context.withEvidence(childEvidence);

        long deadlineAtMs = group.deadlineMs() > 0 ? groupStart + group.deadlineMs() : 0;
        ExecutorService pool = newPool(group, context);
        List<Future<RunResult.NodeResult>> futures = new ArrayList<>();
        try {
            for (PlanNode.OperationNode child : group.children()) {
                futures.add(pool.submit(() ->
                        nodeExecutor.execute(child, childContext, expressionContext)));
            }
            for (int i = 0; i < futures.size(); i++) {
                PlanNode.OperationNode child = group.children().get(i);
                try {
                    Future<RunResult.NodeResult> future = futures.get(i);
                    childResults.put(child.nodeId(), deadlineAtMs > 0
                            ? future.get(
                                    Math.max(0, deadlineAtMs - System.currentTimeMillis()),
                                    TimeUnit.MILLISECONDS)
                            : future.get());
                } catch (TimeoutException deadlineElapsed) {
                    futures.forEach(pending -> pending.cancel(true));
                    break;
                } catch (ExecutionException e) {
                    childResults.put(child.nodeId(), new RunResult.NodeResult(
                            child.nodeId(), "operation", RunResult.Status.ERROR,
                            -1, System.currentTimeMillis() - groupStart, List.of(),
                            new NormalizedError(
                                    NormalizedError.ErrorCategory.INTERNAL,
                                    "EXECUTION_ERROR",
                                    "Parallel child failed: " + e.getCause(),
                                    false, Map.of())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    context.cancellation().set(true);
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        context.evidence().putAll(childEvidence);
        reportUnfinishedChildren(group, context, childResults, groupStart);

        long durationMs = System.currentTimeMillis() - groupStart;
        long failedChildren = group.children().stream()
                .map(child -> childResults.get(child.nodeId()))
                .filter(result -> result == null || result.status() != RunResult.Status.PASSED)
                .count();

        if (failedChildren == 0) {
            context.journal().nodeCompleted(group.nodeId(), durationMs, -1, 0);
            return new GroupOutcome(new RunResult.NodeResult(
                    group.nodeId(), "parallel", RunResult.Status.PASSED,
                    -1, durationMs, List.of(), null), childResults);
        }

        NormalizedError groupError = new NormalizedError(
                NormalizedError.ErrorCategory.INTERNAL,
                "PARALLEL_CHILD_FAILED",
                failedChildren + " of " + group.children().size()
                        + " parallel children failed",
                false, Map.of("failedChildren", failedChildren));
        context.journal().nodeFailed(group.nodeId(), groupError, durationMs);
        return new GroupOutcome(new RunResult.NodeResult(
                group.nodeId(), "parallel", RunResult.Status.FAILED,
                -1, durationMs, List.of(), groupError), childResults);
    }

    private ExecutorService newPool(PlanNode.ParallelNode group, NodeContext context) {
        int policyConcurrency = context.plan().targetPolicy() != null
                ? context.plan().targetPolicy().maxConcurrency() : group.children().size();
        int poolSize = Math.max(1, Math.min(group.children().size(), policyConcurrency));
        return Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "faultora-parallel");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * A child without a result never finished: the group deadline elapsed or
     * the run was cancelled while it was in flight. Either way it is reported,
     * never dropped.
     */
    private void reportUnfinishedChildren(
            PlanNode.ParallelNode group,
            NodeContext context,
            Map<NodeId, RunResult.NodeResult> childResults,
            long groupStart
    ) {
        for (PlanNode.OperationNode child : group.children()) {
            if (childResults.containsKey(child.nodeId())) {
                continue;
            }
            long durationSoFar = System.currentTimeMillis() - groupStart;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.TIMEOUT,
                    "DEADLINE_EXCEEDED",
                    "Parallel child did not finish within the group timeout of "
                            + group.deadlineMs() + "ms",
                    false, Map.of());
            context.journal().nodeFailed(child.nodeId(), error, durationSoFar);
            childResults.put(child.nodeId(), new RunResult.NodeResult(
                    child.nodeId(), "operation", RunResult.Status.FAILED,
                    -1, durationSoFar, List.of(), error));
        }
    }
}
