package dev.faultora.model.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.RunId;

import java.util.Map;

/**
 * Append-only run event recorded to events.ndjson.
 * Each event has a stable type discriminator and timestamp.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType", include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = RunEvent.RunStarted.class, name = "RUN_STARTED"),
        @JsonSubTypes.Type(value = RunEvent.RunCompleted.class, name = "RUN_COMPLETED"),
        @JsonSubTypes.Type(value = RunEvent.RunFailed.class, name = "RUN_FAILED"),
        @JsonSubTypes.Type(value = RunEvent.NodeStarted.class, name = "NODE_STARTED"),
        @JsonSubTypes.Type(value = RunEvent.NodeCompleted.class, name = "NODE_COMPLETED"),
        @JsonSubTypes.Type(value = RunEvent.NodeFailed.class, name = "NODE_FAILED"),
        @JsonSubTypes.Type(value = RunEvent.FaultInjected.class, name = "FAULT_INJECTED"),
        @JsonSubTypes.Type(value = RunEvent.FaultRolledBack.class, name = "FAULT_ROLLED_BACK"),
        @JsonSubTypes.Type(value = RunEvent.AssertionEvaluated.class, name = "ASSERTION_EVALUATED"),
        @JsonSubTypes.Type(value = RunEvent.EvidenceCaptured.class, name = "EVIDENCE_CAPTURED"),
        @JsonSubTypes.Type(value = RunEvent.CleanupStarted.class, name = "CLEANUP_STARTED"),
        @JsonSubTypes.Type(value = RunEvent.CleanupCompleted.class, name = "CLEANUP_COMPLETED")
})
public sealed interface RunEvent {

    String eventType();
    long timestamp();
    RunId runId();

    /** Run lifecycle started. */
    record RunStarted(
            String eventType,
            long timestamp,
            RunId runId,
            String scenarioDigest,
            String catalogDigest,
            long seed,
            Map<String, Object> resolvedConfig
    ) implements RunEvent {
        public RunStarted {
            eventType = "RUN_STARTED";
        }
    }

    /** Run completed successfully. */
    record RunCompleted(
            String eventType,
            long timestamp,
            RunId runId,
            int totalNodes,
            int passedAssertions,
            int failedAssertions,
            long durationMs
    ) implements RunEvent {
        public RunCompleted {
            eventType = "RUN_COMPLETED";
        }
    }

    /** Run failed. */
    record RunFailed(
            String eventType,
            long timestamp,
            RunId runId,
            NormalizedError error,
            long durationMs
    ) implements RunEvent {
        public RunFailed {
            eventType = "RUN_FAILED";
        }
    }

    /** A plan node started executing. */
    record NodeStarted(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            String nodeType,
            OperationId operationId
    ) implements RunEvent {
        public NodeStarted {
            eventType = "NODE_STARTED";
        }
    }

    /** A plan node completed successfully. */
    record NodeCompleted(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            long durationMs,
            int statusCode,
            long responseBytes
    ) implements RunEvent {
        public NodeCompleted {
            eventType = "NODE_COMPLETED";
        }
    }

    /** A plan node failed. */
    record NodeFailed(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            NormalizedError error,
            long durationMs
    ) implements RunEvent {
        public NodeFailed {
            eventType = "NODE_FAILED";
        }
    }

    /** A fault was injected. */
    record FaultInjected(
            String eventType,
            long timestamp,
            RunId runId,
            String faultHandle,
            String faultType,
            String targetScope,
            long hardExpiryMs
    ) implements RunEvent {
        public FaultInjected {
            eventType = "FAULT_INJECTED";
        }
    }

    /** A fault was rolled back. */
    record FaultRolledBack(
            String eventType,
            long timestamp,
            RunId runId,
            String faultHandle,
            String rollbackStatus
    ) implements RunEvent {
        public FaultRolledBack {
            eventType = "FAULT_ROLLED_BACK";
        }
    }

    /** An assertion was evaluated. */
    record AssertionEvaluated(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            String assertionType,
            String outcome,
            String message
    ) implements RunEvent {
        public AssertionEvaluated {
            eventType = "ASSERTION_EVALUATED";
        }
    }

    /** Evidence was captured for a node. */
    record EvidenceCaptured(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            String evidenceType,
            String digest,
            long sizeBytes
    ) implements RunEvent {
        public EvidenceCaptured {
            eventType = "EVIDENCE_CAPTURED";
        }
    }

    /** Cleanup phase started. */
    record CleanupStarted(
            String eventType,
            long timestamp,
            RunId runId,
            int pendingObligations
    ) implements RunEvent {
        public CleanupStarted {
            eventType = "CLEANUP_STARTED";
        }
    }

    /** Cleanup phase completed. */
    record CleanupCompleted(
            String eventType,
            long timestamp,
            RunId runId,
            int succeeded,
            int failed,
            long durationMs
    ) implements RunEvent {
        public CleanupCompleted {
            eventType = "CLEANUP_COMPLETED";
        }
    }
}
