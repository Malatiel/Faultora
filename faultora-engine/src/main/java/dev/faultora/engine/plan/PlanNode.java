package dev.faultora.engine.plan;

import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;

import java.util.List;
import java.util.Map;

/**
 * A node in the compiled execution plan DAG.
 * Each node has a stable ID, dependencies, and execution metadata.
 */
public sealed interface PlanNode permits
        PlanNode.OperationNode,
        PlanNode.AssertionNode,
        PlanNode.FaultStartNode,
        PlanNode.FaultStopNode,
        PlanNode.CleanupNode {

    /**
     * Stable node identifier.
     */
    NodeId nodeId();

    /**
     * IDs of nodes that must complete before this node starts.
     */
    List<NodeId> dependencies();

    /**
     * Safety classification for this node.
     */
    SafetyClassification safety();

    /**
     * Deadline in milliseconds from run start (0 = no deadline).
     */
    long deadlineMs();

    /**
     * Maximum retry attempts (0 = no retry).
     */
    int maxRetries();

    /**
     * Node representing an operation execution.
     * When {@code expectError} is set, the node passes only if the operation
     * fails with a normalized error (used for steps run under injected faults).
     */
    record OperationNode(
            NodeId nodeId,
            OperationId operationId,
            OperationDefinition operation,
            Map<String, Object> inputExpressions,
            String outputBinding,
            boolean expectError,
            List<NodeId> dependencies,
            SafetyClassification safety,
            long deadlineMs,
            int maxRetries
    ) implements PlanNode {
        /** Convenience constructor for nodes that expect success. */
        public OperationNode(
                NodeId nodeId,
                OperationId operationId,
                OperationDefinition operation,
                Map<String, Object> inputExpressions,
                String outputBinding,
                List<NodeId> dependencies,
                SafetyClassification safety,
                long deadlineMs,
                int maxRetries
        ) {
            this(nodeId, operationId, operation, inputExpressions, outputBinding,
                    false, dependencies, safety, deadlineMs, maxRetries);
        }
    }

    /**
     * Node representing an assertion evaluation.
     */
    record AssertionNode(
            NodeId nodeId,
            String assertionType,
            Map<String, Object> params,
            NodeId targetNode,
            String message,
            List<NodeId> dependencies,
            SafetyClassification safety,
            long deadlineMs,
            int maxRetries
    ) implements PlanNode {}

    /**
     * Node representing fault injection start.
     */
    record FaultStartNode(
            NodeId nodeId,
            String faultType,
            String targetScope,
            Map<String, Object> params,
            long durationMs,
            List<NodeId> dependencies,
            SafetyClassification safety,
            long deadlineMs,
            int maxRetries
    ) implements PlanNode {}

    /**
     * Node representing fault rollback.
     */
    record FaultStopNode(
            NodeId nodeId,
            NodeId faultStartNode,
            List<NodeId> dependencies,
            SafetyClassification safety,
            long deadlineMs,
            int maxRetries
    ) implements PlanNode {}

    /**
     * Node representing a cleanup action.
     */
    record CleanupNode(
            NodeId nodeId,
            OperationId operationId,
            Map<String, Object> inputExpressions,
            List<NodeId> dependencies,
            SafetyClassification safety,
            long deadlineMs,
            int maxRetries
    ) implements PlanNode {}
}
