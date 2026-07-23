package dev.faultora.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.expression.ExpressionEvaluator;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.AssertionResult;
import dev.faultora.spi.result.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local execution engine.
 * Executes plan nodes in topological order, maintains the run journal,
 * and handles failure, cancellation, deadlines, and cleanup continuation.
 */
public class LocalEngine {

    private static final Logger LOG = LoggerFactory.getLogger(LocalEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Connector> connectors;
    private final Map<String, AssertionProvider> assertionProviders;
    private final ExpressionEvaluator expressionEvaluator;

    public LocalEngine(
            Map<String, Connector> connectors,
            Map<String, AssertionProvider> assertionProviders
    ) {
        this.connectors = Map.copyOf(connectors);
        this.assertionProviders = Map.copyOf(assertionProviders);
        this.expressionEvaluator = new ExpressionEvaluator();
    }

    /**
     * Execute a compiled plan.
     *
     * @param plan       the execution plan
     * @param journal    run journal for events
     * @param context    expression evaluation context
     * @param connectorContext connector context for operations
     * @param cancellation    cancellation flag
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

        // Emit run started event
        emitEvent(journal, new RunEvent.RunStarted(
                "RUN_STARTED", System.currentTimeMillis(), runId,
                plan.scenarioDigest(), plan.catalogDigest(), plan.seed(),
                Map.of()
        ));

        Map<NodeId, RunResult.NodeResult> nodeResults = new LinkedHashMap<>();
        Map<NodeId, NodeEvidence> nodeEvidenceMap = new LinkedHashMap<>();
        AtomicInteger passedAssertions = new AtomicInteger();
        AtomicInteger failedAssertions = new AtomicInteger();
        AtomicBoolean hasFailure = new AtomicBoolean(false);
        AtomicInteger cleanupFailedCount = new AtomicInteger(0);

        // Build execution order (topological)
        List<PlanNode> nodes = plan.topologicalOrder();

        // Phase 1: Execute all non-cleanup nodes
        List<PlanNode> cleanupNodes = new ArrayList<>();
        for (PlanNode node : nodes) {
            if (cancellation.get()) {
                break;
            }

            if (node instanceof PlanNode.CleanupNode) {
                cleanupNodes.add(node);
                continue;
            }

            // Check if dependencies completed successfully
            if (!dependenciesSatisfied(node, nodeResults)) {
                continue;
            }

            RunResult.NodeResult result = executeNode(
                    node, plan, journal, context, connectorContext,
                    nodeEvidenceMap, cancellation
            );

            nodeResults.put(node.nodeId(), result);

            if (result.status() == RunResult.Status.FAILED ||
                    result.status() == RunResult.Status.ERROR) {
                hasFailure.set(true);
            }

            // Count assertions
            for (AssertionResult ar : result.assertions()) {
                if (ar.outcome() == AssertionResult.Outcome.PASS) {
                    passedAssertions.incrementAndGet();
                } else if (ar.outcome() == AssertionResult.Outcome.FAIL
                        || ar.outcome() == AssertionResult.Outcome.INDETERMINATE) {
                    failedAssertions.incrementAndGet();
                }
            }
        }

        // Phase 2: Always run cleanup nodes, even if there were failures
        if (!cancellation.get()) {
            emitEvent(journal, new RunEvent.CleanupStarted(
                    "CLEANUP_STARTED", System.currentTimeMillis(), runId,
                    cleanupNodes.size()
            ));

            long cleanupStart = System.currentTimeMillis();
            int cleanupSucceeded = 0;
            int cleanupFailed = 0;

            for (PlanNode cleanupNode : cleanupNodes) {
                RunResult.NodeResult result = executeNode(
                        cleanupNode, plan, journal, context, connectorContext,
                        nodeEvidenceMap, cancellation
                );
                nodeResults.put(cleanupNode.nodeId(), result);

                if (result.status() == RunResult.Status.PASSED) {
                    cleanupSucceeded++;
                } else {
                    cleanupFailed++;
                    cleanupFailedCount.incrementAndGet();
                }
            }

            long cleanupDuration = System.currentTimeMillis() - cleanupStart;
            emitEvent(journal, new RunEvent.CleanupCompleted(
                    "CLEANUP_COMPLETED", System.currentTimeMillis(), runId,
                    cleanupSucceeded, cleanupFailed, cleanupDuration
            ));
        }

        long totalDuration = System.currentTimeMillis() - startTime;

        // Determine overall status
        RunResult.Status status;
        if (cancellation.get()) {
            status = RunResult.Status.CANCELLED;
        } else if (hasFailure.get() || failedAssertions.get() > 0 || cleanupFailedCount.get() > 0) {
            status = RunResult.Status.FAILED;
        } else {
            status = RunResult.Status.PASSED;
        }

        long failedNodes = nodeResults.values().stream()
                .filter(result -> result.status() != RunResult.Status.PASSED)
                .count();
        NormalizedError runError = null;
        if (status == RunResult.Status.PASSED) {
            emitEvent(journal, new RunEvent.RunCompleted(
                    "RUN_COMPLETED", System.currentTimeMillis(), runId,
                    nodeResults.size(), passedAssertions.get(),
                    failedAssertions.get(), totalDuration
            ));
        } else {
            runError = new NormalizedError(
                    status == RunResult.Status.CANCELLED
                            ? NormalizedError.ErrorCategory.CANCELLED
                            : NormalizedError.ErrorCategory.INTERNAL,
                    status == RunResult.Status.CANCELLED ? "RUN_CANCELLED" : "RUN_FAILED",
                    "Run " + status.name().toLowerCase(Locale.ROOT)
                            + ": " + failedNodes + " failed nodes, "
                            + failedAssertions.get() + " failed assertions",
                    false,
                    Map.of("passedAssertions", passedAssertions.get(),
                            "failedAssertions", failedAssertions.get(),
                            "failedNodes", failedNodes,
                            "cleanupFailures", cleanupFailedCount.get())
            );
            emitEvent(journal, new RunEvent.RunFailed(
                    "RUN_FAILED", System.currentTimeMillis(), runId,
                    runError, totalDuration
            ));
        }

        try {
            journal.flush();
        } catch (IOException e) {
            LOG.warn("Failed to flush journal: {}", e.getMessage());
        }

        return new RunResult(
                runId, status, nodeResults.size(),
                passedAssertions.get(), failedAssertions.get(),
                nodeResults, totalDuration,
                runError
        );
    }

    private RunResult.NodeResult executeNode(
            PlanNode node,
            ExecutionPlan plan,
            RunJournal journal,
            ExpressionContext context,
            ConnectorContext connectorContext,
            Map<NodeId, NodeEvidence> evidenceMap,
            AtomicBoolean cancellation
    ) {
        NodeId nodeId = node.nodeId();
        long nodeStart = System.currentTimeMillis();

        emitEvent(journal, new RunEvent.NodeStarted(
                "NODE_STARTED", System.currentTimeMillis(),
                plan.runId(), nodeId, nodeType(node), operationId(node)
        ));

        try {
            if (cancellation.get()) {
                return nodeFailed(nodeId, node, "Node cancelled",
                        NormalizedError.ErrorCategory.CANCELLED, nodeStart);
            }

            NodeEvidence evidence = new NodeEvidence(connectorContext.evidencePolicy());

            switch (node) {
                case PlanNode.OperationNode opNode -> {
                    if (opNode.operation() == null) {
                        long waitMs = toLong(opNode.inputExpressions().get("waitMs"));
                        if (waitMs <= 0) {
                            return nodeFailed(nodeId, node, "Wait duration must be positive",
                                    NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                        }
                        waitFor(waitMs, cancellation);
                        if (cancellation.get()) {
                            return nodeFailed(nodeId, node, "Wait cancelled",
                                    NormalizedError.ErrorCategory.CANCELLED, nodeStart);
                        }
                        evidence.durationMs(System.currentTimeMillis() - nodeStart);
                    } else {
                        // Execute operation via connector
                        OperationResult result = executeOperation(opNode, context, connectorContext);
                        populateEvidence(evidence, result);
                    }
                }
                case PlanNode.AssertionNode assertNode -> {
                    // Evaluate assertion against target node's evidence
                    NodeEvidence targetEvidence = assertNode.targetNode() != null ?
                            evidenceMap.get(assertNode.targetNode()) : evidence;
                    if (targetEvidence == null) {
                        targetEvidence = evidence;
                    }

                    AssertionProvider provider = assertionProviders.get(assertNode.assertionType());
                    if (provider == null) {
                        return nodeFailed(nodeId, node,
                                "Unknown assertion type: " + assertNode.assertionType(),
                                NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                    }

                    AssertionContext assertCtx = new AssertionContext(
                            nodeId.value(), assertNode.params());
                    AssertionResult result = provider.evaluate(
                            assertNode.assertionType(), assertNode.params(),
                            targetEvidence, assertCtx);

                    evidence.durationMs(System.currentTimeMillis() - nodeStart);

                    emitEvent(journal, new RunEvent.AssertionEvaluated(
                            "ASSERTION_EVALUATED", System.currentTimeMillis(),
                            plan.runId(), nodeId, assertNode.assertionType(),
                            result.outcome().name(), result.message()
                    ));

                    evidenceMap.put(nodeId, evidence);

                    // Treat INDETERMINATE as failure — an assertion that cannot be
                    // evaluated (e.g., missing evidence) must not silently pass.
                    RunResult.Status nodeStatus =
                            (result.outcome() == AssertionResult.Outcome.FAIL
                             || result.outcome() == AssertionResult.Outcome.INDETERMINATE)
                                    ? RunResult.Status.FAILED : RunResult.Status.PASSED;

                    emitEvent(journal, new RunEvent.NodeCompleted(
                            "NODE_COMPLETED", System.currentTimeMillis(),
                            plan.runId(), nodeId, evidence.durationMs(),
                            0, 0
                    ));

                    return new RunResult.NodeResult(
                            nodeId, nodeType(node), nodeStatus,
                            0, evidence.durationMs(),
                            List.of(result), null
                    );
                }
                case PlanNode.FaultStartNode faultNode -> {
                    return nodeFailed(nodeId, node,
                            "Fault injection is not supported in 0.1.0",
                            NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                }
                case PlanNode.FaultStopNode faultStopNode -> {
                    return nodeFailed(nodeId, node,
                            "Fault rollback is not supported in 0.1.0",
                            NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                }
                case PlanNode.CleanupNode cleanupNode -> {
                    // Look up the operation definition from the catalog
                    dev.faultora.model.catalog.OperationDefinition opDef =
                            plan.catalog().operations().stream()
                                    .filter(op -> op.id().equals(cleanupNode.operationId()))
                                    .findFirst().orElse(null);
                    if (opDef == null) {
                        return nodeFailed(nodeId, node,
                                "Cleanup operation not found in catalog: " + cleanupNode.operationId(),
                                NormalizedError.ErrorCategory.VALIDATION, nodeStart);
                    }
                    PlanNode.OperationNode asOp = new PlanNode.OperationNode(
                            cleanupNode.nodeId(), cleanupNode.operationId(),
                            opDef, cleanupNode.inputExpressions(), null,
                            cleanupNode.dependencies(), cleanupNode.safety(),
                            cleanupNode.deadlineMs(), cleanupNode.maxRetries()
                    );
                    OperationResult result = executeOperation(asOp, context, connectorContext);
                    populateEvidence(evidence, result);
                }
            }

            evidenceMap.put(nodeId, evidence);

            long durationMs = System.currentTimeMillis() - nodeStart;

            // Emit evidence captured event
            if (evidence.responseBody().isPresent()) {
                byte[] bodyBytes = evidence.responseBody().get();
                String digest = "sha256:" + sha256Hex(bodyBytes);
                emitEvent(journal, new RunEvent.EvidenceCaptured(
                        "EVIDENCE_CAPTURED", System.currentTimeMillis(),
                        plan.runId(), nodeId, "http-response",
                        digest, bodyBytes.length
                ));
            }

            emitEvent(journal, new RunEvent.NodeCompleted(
                    "NODE_COMPLETED", System.currentTimeMillis(),
                    plan.runId(), nodeId, durationMs,
                    evidence.statusCode().orElse(-1),
                    evidence.responseBody().map(b -> (long) b.length).orElse(0L)
            ));

            if (evidence.hasError()) {
                return new RunResult.NodeResult(
                        nodeId, nodeType(node), RunResult.Status.FAILED,
                        evidence.statusCode().orElse(-1), durationMs,
                        List.of(), evidence.error().orElse(null)
                );
            }

            return new RunResult.NodeResult(
                    nodeId, nodeType(node), RunResult.Status.PASSED,
                    evidence.statusCode().orElse(-1), durationMs,
                    List.of(), null
            );

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - nodeStart;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.INTERNAL,
                    "EXECUTION_ERROR",
                    "Node execution failed: " + e.getMessage(),
                    false, Map.of()
            );

            emitEvent(journal, new RunEvent.NodeFailed(
                    "NODE_FAILED", System.currentTimeMillis(),
                    plan.runId(), nodeId, error, durationMs
            ));

            return new RunResult.NodeResult(
                    nodeId, nodeType(node), RunResult.Status.ERROR,
                    -1, durationMs, List.of(), error
            );
        }
    }

    private OperationResult executeOperation(
            PlanNode.OperationNode node,
            ExpressionContext context,
            ConnectorContext connectorContext
    ) {
        // Resolve input expressions
        Map<String, Object> resolvedInputs = expressionEvaluator.resolveInputs(
                node.inputExpressions(), context);

        // Find the connector for this operation's protocol
        String protocol = node.operation().protocol().value();
        Connector connector = connectors.get(protocol);
        if (connector == null) {
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.INTERNAL,
                    "NO_CONNECTOR",
                    "No connector for protocol: " + protocol,
                    false, Map.of());
            return OperationResult.failure(error, 0);
        }

        // Prepare target
        var target = findTarget(node.operation().target(), connectorContext);
        if (target == null) {
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.VALIDATION,
                    "TARGET_NOT_FOUND",
                    "Target not found: " + node.operation().target().value(),
                    false, Map.of());
            return OperationResult.failure(error, 0);
        }

        ConnectorContext operationContext = connectorContext;
        if (node.deadlineMs() > 0) {
            long requestTimeout = Math.min(
                    connectorContext.requestTimeoutMs(), node.deadlineMs());
            long totalTimeout = Math.min(
                    connectorContext.totalTimeoutMs(), node.deadlineMs());
            operationContext = new ConnectorContext(
                    connectorContext.evidencePolicy(),
                    connectorContext.secretResolver(),
                    Math.min(connectorContext.connectTimeoutMs(), requestTimeout),
                    requestTimeout,
                    totalTimeout,
                    connectorContext.config());
        }

        var prepared = connector.prepare(target, operationContext);
        try {
            return connector.execute(prepared, node.operation(), resolvedInputs, operationContext);
        } finally {
            connector.release(prepared);
        }
    }

    private dev.faultora.model.catalog.TargetDefinition findTarget(
            dev.faultora.model.identifier.TargetId targetId,
            ConnectorContext context
    ) {
        // In M1, we construct a basic target from the context config
        // In M2+, this will resolve from the catalog
        String baseUrl = (String) context.config().getOrDefault("baseUrl", "http://localhost:8080");
        return new dev.faultora.model.catalog.TargetDefinition(
                targetId, targetId.value(), baseUrl,
                List.of(new dev.faultora.model.identifier.ProtocolId("http")),
                List.of(), Map.of()
        );
    }

    private void populateEvidence(NodeEvidence evidence, OperationResult result) {
        evidence.statusCode(result.statusCode());
        evidence.headers(result.headers());
        // Extract content type from response headers for contentTypeAllowlist enforcement
        String contentType = null;
        if (result.headers() != null) {
            List<String> ctValues = result.headers().get("content-type");
            if (ctValues != null && !ctValues.isEmpty()) {
                contentType = ctValues.get(0);
            }
        }
        evidence.body(result.body(), contentType);
        evidence.durationMs(result.durationMs());
        evidence.error(result.error());
    }

    private boolean dependenciesSatisfied(
            PlanNode node,
            Map<NodeId, RunResult.NodeResult> results
    ) {
        for (NodeId dep : node.dependencies()) {
            RunResult.NodeResult depResult = results.get(dep);
            if (depResult == null) {
                return false; // Dependency not yet executed
            }
            if (depResult.status() != RunResult.Status.PASSED) {
                return false;
            }
        }
        return true;
    }

    private RunResult.NodeResult nodeFailed(
            NodeId nodeId,
            PlanNode node,
            String message,
            NormalizedError.ErrorCategory category,
            long startTime
    ) {
        long durationMs = System.currentTimeMillis() - startTime;
        NormalizedError error = new NormalizedError(
                category, category.name(), message, false, Map.of());
        return new RunResult.NodeResult(
                nodeId, nodeType(node), RunResult.Status.FAILED,
                -1, durationMs, List.of(), error
        );
    }

    private String nodeType(PlanNode node) {
        return switch (node) {
            case PlanNode.OperationNode ignored -> "operation";
            case PlanNode.AssertionNode ignored -> "assertion";
            case PlanNode.FaultStartNode ignored -> "fault-start";
            case PlanNode.FaultStopNode ignored -> "fault-stop";
            case PlanNode.CleanupNode ignored -> "cleanup";
        };
    }

    private dev.faultora.model.identifier.OperationId operationId(PlanNode node) {
        return switch (node) {
            case PlanNode.OperationNode op -> op.operationId();
            case PlanNode.CleanupNode cl -> cl.operationId();
            default -> null;
        };
    }

    private long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) return Long.parseLong(s);
        return 0;
    }

    private void waitFor(long waitMs, AtomicBoolean cancellation) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        while (!cancellation.get()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) return;
            long sleepMs = Math.max(
                    1, Math.min(100, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            Thread.sleep(sleepMs);
        }
    }

    private void emitEvent(RunJournal journal, RunEvent event) {
        try {
            journal.append(event);
        } catch (IOException e) {
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("SHA-256 not available: {}", e.getMessage());
            return "error";
        }
    }
}
