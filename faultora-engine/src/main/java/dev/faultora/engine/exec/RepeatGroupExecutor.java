package dev.faultora.engine.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.spec.expression.ExpressionContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the children of a repeat group once per iteration.
 * <p>
 * Iterations are independent: each starts from the group's context, so an
 * output bound in one iteration cannot leak into the next and make a run
 * depend on iteration order. Results are recorded per iteration, and the plain
 * child ID always points at the most recent one.
 */
public final class RepeatGroupExecutor {

    /** Separator between a repeat child's step ID and its iteration index. */
    public static final String ITERATION_SEPARATOR = ":";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NodeExecutor nodeExecutor;

    public RepeatGroupExecutor(NodeExecutor nodeExecutor) {
        this.nodeExecutor = nodeExecutor;
    }

    public GroupOutcome execute(
            PlanNode.RepeatNode group,
            NodeContext context,
            ExpressionContext expressionContext
    ) {
        long groupStart = System.currentTimeMillis();
        context.journal().nodeStarted(group.nodeId(), "repeat", null);

        Map<NodeId, RunResult.NodeResult> childResults = new LinkedHashMap<>();
        long deadlineAtMs = group.deadlineMs() > 0 ? groupStart + group.deadlineMs() : 0;
        NormalizedError failure = null;
        int completedIterations = 0;

        iterations:
        for (int index = 0; index < group.iterations(); index++) {
            if (context.cancelled()) {
                failure = new NormalizedError(
                        NormalizedError.ErrorCategory.CANCELLED, "CANCELLED",
                        "Repeat group cancelled after " + completedIterations + " iterations",
                        false, Map.of());
                break;
            }
            if (deadlineAtMs > 0 && System.currentTimeMillis() >= deadlineAtMs) {
                failure = new NormalizedError(
                        NormalizedError.ErrorCategory.TIMEOUT, "DEADLINE_EXCEEDED",
                        "Repeat group timeout of " + group.deadlineMs() + "ms elapsed after "
                                + completedIterations + " of " + group.iterations()
                                + " iterations",
                        false, Map.of("completedIterations", completedIterations));
                break;
            }

            ExpressionContext iterationContext =
                    expressionContext.withBinding("repeat", iterationBinding(group, index));

            for (PlanNode.OperationNode child : group.children()) {
                PlanNode.OperationNode iterationChild =
                        child.withNodeId(iterationNodeId(child.nodeId(), index));
                RunResult.NodeResult childResult = nodeExecutor.execute(
                        iterationChild, context, iterationContext);
                childResults.put(iterationChild.nodeId(), childResult);
                iterationContext = StepOutputBinder.bind(
                        iterationChild, childResult, context.evidence(), iterationContext);
                publishAsLatestIteration(child, iterationChild, childResult, context, childResults);

                if (childResult.status() != RunResult.Status.PASSED) {
                    failure = new NormalizedError(
                            NormalizedError.ErrorCategory.INTERNAL,
                            "REPEAT_ITERATION_FAILED",
                            "Iteration " + index + " failed at step " + child.nodeId().value(),
                            false, Map.of("iteration", index, "step", child.nodeId().value()));
                    break iterations;
                }
            }
            completedIterations++;
        }

        long durationMs = System.currentTimeMillis() - groupStart;
        if (failure == null) {
            context.journal().nodeCompleted(group.nodeId(), durationMs, -1, 0);
            return new GroupOutcome(new RunResult.NodeResult(
                    group.nodeId(), "repeat", RunResult.Status.PASSED,
                    -1, durationMs, List.of(), null), childResults);
        }
        context.journal().nodeFailed(group.nodeId(), failure, durationMs);
        return new GroupOutcome(new RunResult.NodeResult(
                group.nodeId(), "repeat", RunResult.Status.FAILED,
                -1, durationMs, List.of(), failure), childResults);
    }

    /** Node ID of one child in one iteration, e.g. {@code create-payment:2}. */
    public static NodeId iterationNodeId(NodeId childId, int index) {
        return new NodeId(childId.value() + ITERATION_SEPARATOR + index);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode iterationBinding(
            PlanNode.RepeatNode group, int index) {
        var binding = MAPPER.createObjectNode();
        binding.put("index", index);
        if (group.items() != null) {
            binding.set("item", MAPPER.valueToTree(group.items().get(index)));
        }
        return binding;
    }

    /**
     * Make the iteration visible under the plain child ID, so assertions and
     * later steps can talk about "the step" without knowing the iteration
     * count.
     */
    private void publishAsLatestIteration(
            PlanNode.OperationNode child,
            PlanNode.OperationNode iterationChild,
            RunResult.NodeResult iterationResult,
            NodeContext context,
            Map<NodeId, RunResult.NodeResult> childResults
    ) {
        NodeEvidence iterationEvidence = context.evidence().get(iterationChild.nodeId());
        if (iterationEvidence != null) {
            context.evidence().put(child.nodeId(), iterationEvidence);
        }
        childResults.put(child.nodeId(),
                NodeResults.renamed(iterationResult, child.nodeId()));
    }
}
