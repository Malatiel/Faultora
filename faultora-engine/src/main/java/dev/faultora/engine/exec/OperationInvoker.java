package dev.faultora.engine.exec;

import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;

import java.util.List;
import java.util.Map;

/**
 * Invokes one operation through its protocol connector.
 * <p>
 * This is the only class that knows how a plan node turns into a request:
 * expression resolution, connector selection, per-node deadlines, and the
 * retry loop. Executors above it deal in results, not in connectors.
 */
public final class OperationInvoker {

    private final Map<String, Connector> connectors;

    public OperationInvoker(Map<String, Connector> connectors) {
        this.connectors = Map.copyOf(connectors);
    }

    /**
     * Execute the node once with inputs already resolved.
     * <p>
     * Inputs arrive resolved rather than being resolved here, so that every
     * attempt of one node sends the same request: see {@link InputResolver}.
     */
    public OperationResult invoke(
            PlanNode.OperationNode node,
            NodeContext context,
            Map<String, Object> inputs
    ) {
        ConnectorContext connectorContext = context.connectorContext();

        String protocol = node.operation().protocol().value();
        Connector connector = connectors.get(protocol);
        if (connector == null) {
            return OperationResult.failure(new NormalizedError(
                    NormalizedError.ErrorCategory.INTERNAL,
                    "NO_CONNECTOR",
                    "No connector for protocol: " + protocol,
                    false, Map.of()), 0);
        }

        TargetDefinition target = TargetResolver.resolve(
                node.operation().target(), context.plan().catalog(), connectorContext);
        if (target == null) {
            return OperationResult.failure(new NormalizedError(
                    NormalizedError.ErrorCategory.VALIDATION,
                    "TARGET_NOT_FOUND",
                    "Target is neither declared in the catalog nor bound to a URL: "
                            + node.operation().target().value(),
                    false, Map.of()), 0);
        }

        ConnectorContext operationContext = withNodeDeadline(node, connectorContext);
        var prepared = connector.prepare(target, operationContext);
        try {
            return connector.execute(
                    prepared, node.operation(), inputs, operationContext);
        } finally {
            connector.release(prepared);
        }
    }

    /**
     * Execute the node, re-running it while the error is retryable and the
     * node's retry policy still allows an attempt. Evidence always comes from
     * the final attempt.
     */
    public OperationResult invokeWithRetry(
            PlanNode.OperationNode node,
            NodeContext context,
            Map<String, Object> inputs
    ) throws InterruptedException {
        PlanNode.RetrySpec retry = node.retrySpec();
        int maxAttempts = retry == null ? 1 : retry.maxAttempts();

        OperationResult result = invoke(node, context, inputs);
        for (int failedAttempt = 1; failedAttempt < maxAttempts; failedAttempt++) {
            if (result.error() == null || !result.error().retryable() || context.cancelled()) {
                return result;
            }
            long delayMs = RetryBackoff.delayMs(
                    retry, context.plan().seed(), node.nodeId(), failedAttempt);
            context.journal().operationRetried(
                    node.nodeId(), failedAttempt, maxAttempts, delayMs, result.error().code());
            if (delayMs > 0) {
                Waits.sleep(delayMs, context.cancellation());
            }
            if (context.cancelled()) {
                return result;
            }
            result = invoke(node, context, inputs);
        }
        return result;
    }

    /** Copy an operation result into the node's evidence, honouring the evidence policy. */
    public static void populateEvidence(NodeEvidence evidence, OperationResult result) {
        evidence.statusCode(result.statusCode());
        evidence.headers(result.headers());
        String contentType = null;
        if (result.headers() != null) {
            List<String> values = result.headers().get("content-type");
            if (values != null && !values.isEmpty()) {
                contentType = values.get(0);
            }
        }
        evidence.body(result.body(), contentType);
        evidence.durationMs(result.durationMs());
        evidence.error(result.error());
        // Whatever the protocol contributed beyond a response: broker offsets,
        // observed messages, anything a later protocol adds. The engine does
        // not interpret it; assertions and later steps do.
        if (result.protocolEvidence() != null) {
            result.protocolEvidence().forEach(evidence::protocolEvidence);
        }
    }

    /**
     * A node deadline may only tighten the run's timeouts, never widen them.
     */
    private ConnectorContext withNodeDeadline(
            PlanNode.OperationNode node, ConnectorContext connectorContext) {
        if (node.deadlineMs() <= 0) {
            return connectorContext;
        }
        long requestTimeout = Math.min(connectorContext.requestTimeoutMs(), node.deadlineMs());
        long totalTimeout = Math.min(connectorContext.totalTimeoutMs(), node.deadlineMs());
        return new ConnectorContext(
                connectorContext.evidencePolicy(),
                connectorContext.secretResolver(),
                Math.min(connectorContext.connectTimeoutMs(), requestTimeout),
                requestTimeout,
                totalTimeout,
                connectorContext.config());
    }

}
