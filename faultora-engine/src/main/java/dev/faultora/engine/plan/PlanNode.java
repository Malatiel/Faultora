package dev.faultora.engine.plan;

import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A node in the compiled execution plan DAG.
 * <p>
 * Every node has an identity, its place in the graph, and a safety
 * classification the policy can reason about. Everything else — deadlines,
 * retries, poll budgets — belongs to the node kinds that actually have it, so
 * a node never carries a field it cannot honour.
 */
public sealed interface PlanNode permits
        PlanNode.OperationNode,
        PlanNode.WaitNode,
        PlanNode.ParallelNode,
        PlanNode.RepeatNode,
        PlanNode.EventuallyNode,
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
     * Request values an operation node generates from the catalog's schemas.
     * <p>
     * The named inputs are generated at execution time; values the step states
     * explicitly are applied over them, so a scenario can generate a payload
     * and still pin what it asserts on.
     *
     * @param fields         declared inputs to generate, such as {@code body}
     * @param strategy       relation between generated values and constraints
     * @param preferExamples whether authored examples are used verbatim
     */
    record GenerationRequest(
            List<String> fields,
            dev.faultora.schema.GenerationStrategy strategy,
            boolean preferExamples
    ) {
        public GenerationRequest {
            if (fields == null || fields.isEmpty()) {
                throw new IllegalArgumentException("generation requires at least one field");
            }
            fields = List.copyOf(fields);
        }
    }

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
            int maxRetries,
            GenerationRequest generation
    ) implements PlanNode {
        /** Convenience constructor for nodes without generated inputs. */
        public OperationNode(
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
        ) {
            this(nodeId, operationId, operation, inputExpressions, outputBinding,
                    expectError, retrySpec, dependencies, safety, deadlineMs, maxRetries, null);
        }

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
                    false, null, dependencies, safety, deadlineMs, maxRetries, null);
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
                    expectError, null, dependencies, safety, deadlineMs, maxRetries, null);
        }

        /** The same node under a different ID, used for repeat iterations. */
        public OperationNode withNodeId(NodeId iterationNodeId) {
            return new OperationNode(
                    iterationNodeId, operationId, operation, inputExpressions,
                    outputBinding, expectError, retrySpec, dependencies, safety,
                    deadlineMs, maxRetries, generation);
        }
    }

    /**
     * Node representing a local pause. A wait makes no request and therefore
     * has neither a connector nor a retry policy.
     */
    record WaitNode(
            NodeId nodeId,
            long waitMs,
            List<NodeId> dependencies,
            SafetyClassification safety
    ) implements PlanNode {
        public WaitNode {
            if (waitMs < 1) {
                throw new IllegalArgumentException("wait node requires a positive duration");
            }
        }
    }

    /**
     * Node representing a bounded parallel group. Children start together once
     * the group's dependencies are satisfied and execute concurrently; the
     * group passes only if every child passes.
     */
    record ParallelNode(
            NodeId nodeId,
            List<OperationNode> children,
            List<NodeId> dependencies,
            SafetyClassification safety,
            long deadlineMs
    ) implements PlanNode {
        public ParallelNode {
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("parallel node requires children");
            }
            children = List.copyOf(children);
        }
    }

    /**
     * Node representing a repeat group. The children run in declaration order
     * once per iteration; the iteration count is fixed at compile time, either
     * from a literal count or from a literal item list. The group fails on the
     * first failing iteration and reports its index.
     */
    record RepeatNode(
            NodeId nodeId,
            List<OperationNode> children,
            List<Object> items,
            int iterations,
            List<NodeId> dependencies,
            SafetyClassification safety,
            long deadlineMs
    ) implements PlanNode {
        public RepeatNode {
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("repeat node requires children");
            }
            if (iterations < 1) {
                throw new IllegalArgumentException("repeat node requires at least one iteration");
            }
            children = List.copyOf(children);
            items = items == null ? null : Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    /**
     * Node representing an eventually (poll-until) group. The child operation
     * is polled every {@code intervalMs} until every condition holds in the
     * same poll or the {@code timeoutMs} budget is spent.
     */
    record EventuallyNode(
            NodeId nodeId,
            OperationNode child,
            List<Condition> conditions,
            long timeoutMs,
            long intervalMs,
            int maxPolls,
            List<NodeId> dependencies,
            SafetyClassification safety
    ) implements PlanNode {
        public EventuallyNode {
            if (child == null) {
                throw new IllegalArgumentException("eventually node requires a child step");
            }
            if (conditions == null || conditions.isEmpty()) {
                throw new IllegalArgumentException("eventually node requires conditions");
            }
            if (timeoutMs < 1 || intervalMs < 1 || maxPolls < 1) {
                throw new IllegalArgumentException("eventually values are out of range");
            }
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * A condition evaluated against polled evidence by an assertion provider.
     */
    record Condition(
            String assertionType,
            Map<String, Object> params,
            String message
    ) {
        public Condition {
            params = params == null ? Map.of() : Map.copyOf(params);
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
            Map<String, Object> schema
    ) implements PlanNode {
        /** Convenience constructor for assertions that need no schema. */
        public AssertionNode(
                NodeId nodeId,
                String assertionType,
                Map<String, Object> params,
                NodeId targetNode,
                String message,
                List<NodeId> dependencies,
                SafetyClassification safety
        ) {
            this(nodeId, assertionType, params, targetNode, message,
                    dependencies, safety, null);
        }
    }

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
            SafetyClassification safety
    ) implements PlanNode {}

    /**
     * Node representing fault rollback.
     */
    record FaultStopNode(
            NodeId nodeId,
            NodeId faultStartNode,
            List<NodeId> dependencies,
            SafetyClassification safety
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
