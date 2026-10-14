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
        context = context.within(node.deadlineMs());
        if (context.connectorContext().totalTimeoutMs() <= 0) {
            return stopped(NormalizedError.ErrorCategory.TIMEOUT);
        }
        context = context.within(context.connectorContext().totalTimeoutMs());
        OperationResult halt = halt(context);
        if (halt != null) {
            return halt;
        }
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

        halt = halt(context);
        if (halt != null) {
            return halt;
        }
        ConnectorContext operationContext = remainingContext(context);
        if (operationContext == null) {
            return stopped(NormalizedError.ErrorCategory.TIMEOUT);
        }
        var prepared = connector.prepare(target, operationContext);
        try {
            halt = halt(context);
            if (halt != null) {
                return halt;
            }
            operationContext = remainingContext(context);
            if (operationContext == null) {
                return stopped(NormalizedError.ErrorCategory.TIMEOUT);
            }
            OperationResult result = connector.execute(prepared, node.operation(), inputs, operationContext);
            halt = halt(context);
            return halt == null ? result : OperationResult.failure(halt.error(), result.durationMs());
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
        context = context.within(node.deadlineMs()).within(context.connectorContext().totalTimeoutMs());
        PlanNode.RetrySpec retry = node.retrySpec();
        int maxAttempts = retry == null ? 1 : retry.maxAttempts();

        OperationResult result = invoke(node, context, inputs);
        for (int failedAttempt = 1; failedAttempt < maxAttempts; failedAttempt++) {
            OperationResult halt = halt(context);
            if (halt != null) {
                return halt;
            }
            if (result.error() == null || !result.error().retryable()) {
                return result;
            }
            long delayMs = RetryBackoff.delayMs(
                    retry, context.plan().seed(), node.nodeId(), failedAttempt);
            context.journal().operationRetried(
                    node.nodeId(), failedAttempt, maxAttempts, delayMs, result.error().code());
            if (delayMs > 0) {
                Waits.sleep(delayMs, context.cancellation(), context.deadline());
            }
            halt = halt(context);
            if (halt != null) {
                return halt;
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
    private ConnectorContext remainingContext(NodeContext context) {
        long remaining = context.deadline().remainingMillis();
        if (remaining <= 0) {
            return null;
        }
        ConnectorContext connectorContext = context.connectorContext();
        long requestTimeout = boundedTimeout(connectorContext.requestTimeoutMs(), remaining);
        long totalTimeout = Math.min(connectorContext.totalTimeoutMs(), remaining);
        return new ConnectorContext(
                connectorContext.evidencePolicy(),
                connectorContext.secretResolver(),
                boundedTimeout(connectorContext.connectTimeoutMs(), Math.min(requestTimeout, remaining)),
                requestTimeout,
                totalTimeout,
                connectorContext.config());
    }

    private static long boundedTimeout(long configured, long remaining) {
        return configured > 0 ? Math.min(configured, remaining) : remaining;
    }

    private static OperationResult halt(NodeContext context) {
        if (context.cancelled()) {
            return stopped(NormalizedError.ErrorCategory.CANCELLED);
        }
        return context.deadline().expired() ? stopped(NormalizedError.ErrorCategory.TIMEOUT) : null;
    }

    private static OperationResult stopped(NormalizedError.ErrorCategory category) {
        return OperationResult.failure(new NormalizedError(category,
                category == NormalizedError.ErrorCategory.TIMEOUT ? "DEADLINE_EXCEEDED" : "CANCELLED",
                category == NormalizedError.ErrorCategory.TIMEOUT
                        ? "Operation deadline exhausted" : "Operation cancelled",
                false, Map.of()), 0);
    }

}
