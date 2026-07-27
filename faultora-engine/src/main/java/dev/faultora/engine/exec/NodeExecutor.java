package dev.faultora.engine.exec;

import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.result.OperationResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes one plan node and reports its outcome.
 * <p>
 * The lifecycle around a node — start event, cancellation check, evidence
 * capture, {@code expectError} interpretation, completion or failure event —
 * is identical for every node kind and lives here. What a node actually
 * <em>does</em> lives in the kind-specific executors this class delegates to.
 * Group nodes are scheduled by {@link dev.faultora.engine.LocalEngine} and
 * never reach this method.
 */
public final class NodeExecutor {

    private final OperationInvoker invoker;
    private final InputResolver inputResolver = new InputResolver();
    private final AssertionNodeExecutor assertions;
    private final FaultNodeExecutor faults;

    public NodeExecutor(
            OperationInvoker invoker,
            Map<String, AssertionProvider> assertionProviders,
            Map<String, FaultProvider> faultProviders
    ) {
        this.invoker = invoker;
        this.assertions = new AssertionNodeExecutor(assertionProviders);
        this.faults = new FaultNodeExecutor(faultProviders);
    }

    public RunResult.NodeResult execute(
            PlanNode node, NodeContext context, ExpressionContext expressionContext) {
        NodeId nodeId = node.nodeId();
        long nodeStart = System.currentTimeMillis();

        context.journal().nodeStarted(
                nodeId, NodeResults.typeOf(node), NodeResults.operationOf(node));

        try {
            if (context.cancelled()) {
                return NodeResults.failed(node, "Node cancelled",
                        NormalizedError.ErrorCategory.CANCELLED, nodeStart);
            }

            NodeEvidence evidence = new NodeEvidence(context.connectorContext().evidencePolicy());

            Optional<RunResult.NodeResult> earlyResult = switch (node) {
                case PlanNode.AssertionNode assertion -> Optional.of(
                        assertions.execute(assertion, context, evidence, nodeStart));
                case PlanNode.WaitNode wait ->
                        awaitLocally(wait, context, evidence, nodeStart);
                case PlanNode.OperationNode operation ->
                        invokeOperation(operation, context, expressionContext, evidence);
                case PlanNode.CleanupNode cleanup ->
                        invokeCleanup(cleanup, context, expressionContext, evidence, nodeStart);
                case PlanNode.FaultStartNode fault ->
                        faults.start(fault, context, evidence, nodeStart);
                case PlanNode.FaultStopNode fault ->
                        faults.stop(fault, context, evidence, nodeStart);
                case PlanNode.ParallelNode ignored -> Optional.of(NodeResults.failed(node,
                        "Parallel group reached single-node execution",
                        NormalizedError.ErrorCategory.INTERNAL, nodeStart));
                case PlanNode.RepeatNode ignored -> Optional.of(NodeResults.failed(node,
                        "Repeat group reached single-node execution",
                        NormalizedError.ErrorCategory.INTERNAL, nodeStart));
                case PlanNode.EventuallyNode ignored -> Optional.of(NodeResults.failed(node,
                        "Eventually group reached single-node execution",
                        NormalizedError.ErrorCategory.INTERNAL, nodeStart));
            };
            if (earlyResult.isPresent()) {
                return earlyResult.get();
            }

            return report(node, context, evidence, nodeStart);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - nodeStart;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.INTERNAL,
                    "EXECUTION_ERROR",
                    "Node execution failed: " + e.getMessage(),
                    false, Map.of());
            context.journal().nodeFailed(nodeId, error, durationMs);
            return new RunResult.NodeResult(
                    nodeId, NodeResults.typeOf(node), RunResult.Status.ERROR,
                    -1, durationMs, List.of(), error);
        }
    }

    private Optional<RunResult.NodeResult> awaitLocally(
            PlanNode.WaitNode node, NodeContext context,
            NodeEvidence evidence, long nodeStart) throws InterruptedException {
        Waits.sleep(node.waitMs(), context.cancellation());
        if (context.cancelled()) {
            return Optional.of(NodeResults.failed(node, "Wait cancelled",
                    NormalizedError.ErrorCategory.CANCELLED, nodeStart));
        }
        evidence.durationMs(System.currentTimeMillis() - nodeStart);
        return Optional.empty();
    }

    private Optional<RunResult.NodeResult> invokeOperation(
            PlanNode.OperationNode node, NodeContext context,
            ExpressionContext expressionContext, NodeEvidence evidence)
            throws InterruptedException {
        // Inputs are resolved once: every attempt of this node sends the
        // identical request.
        OperationResult result = invoker.invokeWithRetry(
                node, context, inputResolver.resolve(node, context, expressionContext));
        OperationInvoker.populateEvidence(evidence, result);
        return Optional.empty();
    }

    /**
     * Cleanup steps run their operation once: a cleanup obligation that needs
     * retrying is a scenario defect, not something to paper over at run time.
     */
    private Optional<RunResult.NodeResult> invokeCleanup(
            PlanNode.CleanupNode node, NodeContext context,
            ExpressionContext expressionContext, NodeEvidence evidence, long nodeStart) {
        OperationDefinition definition = context.plan().catalog().operations().stream()
                .filter(operation -> operation.id().equals(node.operationId()))
                .findFirst().orElse(null);
        if (definition == null) {
            return Optional.of(NodeResults.failed(node,
                    "Cleanup operation not found in catalog: " + node.operationId(),
                    NormalizedError.ErrorCategory.VALIDATION, nodeStart));
        }
        PlanNode.OperationNode asOperation = new PlanNode.OperationNode(
                node.nodeId(), node.operationId(), definition, node.inputExpressions(),
                null, node.dependencies(), node.safety(), node.deadlineMs(), node.maxRetries());
        OperationResult result = invoker.invoke(asOperation, context,
                inputResolver.resolve(asOperation, context, expressionContext));
        OperationInvoker.populateEvidence(evidence, result);
        return Optional.empty();
    }

    /**
     * Store evidence and turn it into a node result. A node that declared
     * {@code expectError} inverts the verdict: it passes only when the
     * operation failed, and the expected error stays visible in the result.
     */
    private RunResult.NodeResult report(
            PlanNode node, NodeContext context, NodeEvidence evidence, long nodeStart) {
        NodeId nodeId = node.nodeId();
        context.evidence().put(nodeId, evidence);
        long durationMs = System.currentTimeMillis() - nodeStart;

        evidence.responseBody().ifPresent(body -> context.journal().evidenceCaptured(nodeId, body));

        boolean expectError = node instanceof PlanNode.OperationNode operation
                && operation.expectError();

        if (evidence.hasError() && !expectError) {
            NormalizedError error = evidence.error().orElse(null);
            context.journal().nodeFailed(nodeId, error, durationMs);
            return new RunResult.NodeResult(
                    nodeId, NodeResults.typeOf(node), RunResult.Status.FAILED,
                    evidence.statusCode().orElse(-1), durationMs, List.of(), error);
        }

        if (!evidence.hasError() && expectError) {
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.VALIDATION,
                    "EXPECTED_ERROR",
                    "Step declared expectError but the operation succeeded",
                    false, Map.of());
            context.journal().nodeFailed(nodeId, error, durationMs);
            return new RunResult.NodeResult(
                    nodeId, NodeResults.typeOf(node), RunResult.Status.FAILED,
                    evidence.statusCode().orElse(-1), durationMs, List.of(), error);
        }

        context.journal().nodeCompleted(
                nodeId, durationMs, evidence.statusCode().orElse(-1),
                evidence.responseBody().map(body -> (long) body.length).orElse(0L));

        return new RunResult.NodeResult(
                nodeId, NodeResults.typeOf(node), RunResult.Status.PASSED,
                evidence.statusCode().orElse(-1), durationMs, List.of(),
                expectError ? evidence.error().orElse(null) : null);
    }
}
