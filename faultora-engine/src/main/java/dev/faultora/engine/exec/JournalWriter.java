package dev.faultora.engine.exec;

import dev.faultora.engine.journal.RunJournal;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.spi.result.ActiveFault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * The only place that turns execution facts into run events.
 * <p>
 * Executors describe what happened; this writer owns the event shapes, the
 * run identity, and the fact that journal I/O must never fail a run. Keeping
 * that in one class is what lets the event schema evolve without touching the
 * executors.
 */
public final class JournalWriter {

    private static final Logger LOG = LoggerFactory.getLogger(JournalWriter.class);

    private final RunJournal journal;
    private final RunId runId;

    public JournalWriter(RunJournal journal, RunId runId) {
        this.journal = journal;
        this.runId = runId;
    }

    public RunId runId() {
        return runId;
    }

    public void runStarted(String scenarioDigest, String catalogDigest, long seed) {
        append(new RunEvent.RunStarted(
                "RUN_STARTED", now(), runId, scenarioDigest, catalogDigest, seed, Map.of()));
    }

    public void runCompleted(int totalNodes, int passed, int failed, long durationMs) {
        append(new RunEvent.RunCompleted(
                "RUN_COMPLETED", now(), runId, totalNodes, passed, failed, durationMs));
    }

    public void runFailed(NormalizedError error, long durationMs) {
        append(new RunEvent.RunFailed("RUN_FAILED", now(), runId, error, durationMs));
    }

    public void nodeStarted(NodeId nodeId, String nodeType, OperationId operationId) {
        append(new RunEvent.NodeStarted(
                "NODE_STARTED", now(), runId, nodeId, nodeType, operationId));
    }

    public void nodeCompleted(NodeId nodeId, long durationMs, int statusCode, long responseBytes) {
        append(new RunEvent.NodeCompleted(
                "NODE_COMPLETED", now(), runId, nodeId, durationMs, statusCode, responseBytes));
    }

    public void nodeFailed(NodeId nodeId, NormalizedError error, long durationMs) {
        append(new RunEvent.NodeFailed("NODE_FAILED", now(), runId, nodeId, error, durationMs));
    }

    public void assertionEvaluated(
            NodeId nodeId, String assertionType, String outcome, String message) {
        append(new RunEvent.AssertionEvaluated(
                "ASSERTION_EVALUATED", now(), runId, nodeId, assertionType, outcome, message));
    }

    public void conditionPolled(
            NodeId nodeId, int attempt, int maxPolls,
            long elapsedMs, boolean satisfied, String detail) {
        append(new RunEvent.ConditionPolled(
                "CONDITION_POLLED", now(), runId, nodeId,
                attempt, maxPolls, elapsedMs, satisfied, detail));
    }

    public void operationRetried(
            NodeId nodeId, int failedAttempt, int maxAttempts,
            long nextDelayMs, String errorCode) {
        append(new RunEvent.OperationRetried(
                "OPERATION_RETRIED", now(), runId, nodeId,
                failedAttempt, maxAttempts, nextDelayMs, errorCode));
    }

    public void faultInjected(ActiveFault fault) {
        append(new RunEvent.FaultInjected(
                "FAULT_INJECTED", now(), runId, fault.handle(), fault.faultType(),
                fault.targetScope(), fault.hardExpiryMs()));
    }

    public void faultRolledBack(String faultHandle, String rollbackStatus) {
        append(new RunEvent.FaultRolledBack(
                "FAULT_ROLLED_BACK", now(), runId, faultHandle, rollbackStatus));
    }

    /** Record captured evidence by digest and size; the body itself never enters the journal. */
    public void evidenceCaptured(NodeId nodeId, byte[] body) {
        append(new RunEvent.EvidenceCaptured(
                "EVIDENCE_CAPTURED", now(), runId, nodeId, "http-response",
                ContentDigest.sha256Uri(body), body.length));
    }

    public void cleanupStarted(int pendingObligations) {
        append(new RunEvent.CleanupStarted("CLEANUP_STARTED", now(), runId, pendingObligations));
    }

    public void cleanupCompleted(int succeeded, int failed, long durationMs) {
        append(new RunEvent.CleanupCompleted(
                "CLEANUP_COMPLETED", now(), runId, succeeded, failed, durationMs));
    }

    public void flush() {
        try {
            journal.flush();
        } catch (IOException e) {
            LOG.warn("Failed to flush journal: {}", e.getMessage());
        }
    }

    private void append(RunEvent event) {
        try {
            journal.append(event);
        } catch (IOException e) {
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
