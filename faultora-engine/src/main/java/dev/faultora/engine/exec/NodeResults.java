package dev.faultora.engine.exec;

import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;

import java.util.List;
import java.util.Map;

/**
 * Naming and shaping of node results, shared by every executor.
 * <p>
 * The node type strings appear in the run journal and therefore in every
 * report, so they are defined once rather than spelled out at each emission
 * site.
 */
public final class NodeResults {

    private NodeResults() {
    }

    /** Stable node-type label of a plan node, as recorded in the journal. */
    public static String typeOf(PlanNode node) {
        return switch (node) {
            case PlanNode.OperationNode ignored -> "operation";
            case PlanNode.WaitNode ignored -> "wait";
            case PlanNode.ParallelNode ignored -> "parallel";
            case PlanNode.RepeatNode ignored -> "repeat";
            case PlanNode.EventuallyNode ignored -> "eventually";
            case PlanNode.AssertionNode ignored -> "assertion";
            case PlanNode.FaultStartNode ignored -> "fault-start";
            case PlanNode.FaultStopNode ignored -> "fault-stop";
            case PlanNode.CleanupNode ignored -> "cleanup";
        };
    }

    /** Operation a node invokes, or null for nodes that invoke none. */
    public static OperationId operationOf(PlanNode node) {
        return switch (node) {
            case PlanNode.OperationNode operation -> operation.operationId();
            case PlanNode.CleanupNode cleanup -> cleanup.operationId();
            default -> null;
        };
    }

    /** A failed node result carrying a normalized error of the given category. */
    public static RunResult.NodeResult failed(
            PlanNode node, String message,
            NormalizedError.ErrorCategory category, long startedAtMs) {
        NormalizedError error = new NormalizedError(
                category, category.name(), message, false, Map.of());
        return new RunResult.NodeResult(
                node.nodeId(), typeOf(node), RunResult.Status.FAILED,
                -1, System.currentTimeMillis() - startedAtMs, List.of(), error);
    }

    /** The same result under a different node ID, used for repeat iterations. */
    public static RunResult.NodeResult renamed(RunResult.NodeResult result, NodeId nodeId) {
        return new RunResult.NodeResult(
                nodeId, result.nodeType(), result.status(), result.statusCode(),
                result.durationMs(), result.assertions(), result.error());
    }
}
