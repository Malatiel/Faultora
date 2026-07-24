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
     * Retry behavior for an operation node. Delays follow exponential backoff
     * with deterministic seed-derived jitter, capped at {@code maxBackoffMs}
     * when it is positive.
     */
    record RetrySpec(
            int maxAttempts,
            long backoffMs,
            double backoffMultiplier,
            long maxBackoffMs
    ) {
        public RetrySpec {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            if (backoffMs < 0 || maxBackoffMs < 0 || backoffMultiplier < 1) {
                throw new IllegalArgumentException("retry values are out of range");
            }
        }
    }

    /**
     * Node representing an operation execution.
     * When {@code expectError} is set, the node passes only if the operation
     * fails with a normalized error (used for steps run under injected faults).
     * A non-null {@code retrySpec} re-executes the operation on retryable
     * errors.
     */
    record OperationNode(
            NodeId nodeId,
            OperationId operationId,
            OperationDefinition operation,
            Map<String, Object> inputExpressions,
            String outputBinding,
            boolean expectError,
            RetrySpec retrySpec,
            List<NodeId> dependencies,
            SafetyClassification safety,
            long deadlineMs,
            int maxRetries
    ) implements PlanNode {
        /** Convenience constructor for nodes that expect success and never retry. */
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
                    false, null, dependencies, safety, deadlineMs, maxRetries);
        }

        /** Convenience constructor for nodes without retry. */
        public OperationNode(
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
        ) {
            this(nodeId, operationId, operation, inputExpressions, outputBinding,
                    expectError, null, dependencies, safety, deadlineMs, maxRetries);
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
