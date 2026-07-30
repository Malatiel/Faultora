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
        @JsonSubTypes.Type(value = RunEvent.NodeSkipped.class, name = "NODE_SKIPPED"),
        @JsonSubTypes.Type(value = RunEvent.OperationRetried.class, name = "OPERATION_RETRIED"),
        @JsonSubTypes.Type(value = RunEvent.ConditionPolled.class, name = "CONDITION_POLLED"),
        @JsonSubTypes.Type(value = RunEvent.InputsGenerated.class, name = "INPUTS_GENERATED"),
        @JsonSubTypes.Type(value = RunEvent.MessagePublished.class, name = "MESSAGE_PUBLISHED"),
        @JsonSubTypes.Type(value = RunEvent.MessagesObserved.class, name = "MESSAGES_OBSERVED"),
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

    /**
     * A plan node did not run because something it depends on did not pass.
     * <p>
     * Recorded rather than left out: a reader of the report cannot otherwise
     * tell a step that was skipped from one that was never written.
     */
    record NodeSkipped(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            String reason
    ) implements RunEvent {
        public NodeSkipped {
            eventType = "NODE_SKIPPED";
        }
    }

    /** An operation attempt failed with a retryable error and will be retried. */
    record OperationRetried(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            int failedAttempt,
            int maxAttempts,
            long nextDelayMs,
            String errorCode
    ) implements RunEvent {
        public OperationRetried {
            eventType = "OPERATION_RETRIED";
        }
    }

    /**
     * A request value was generated from a schema.
     * <p>
     * The value itself is referenced by digest, never recorded: a generated
     * payload is request data like any other, and the evidence policy decides
     * whether request data is kept. The seed and the schema are what a replay
     * needs.
     *
     * @param field     name of the generated input
     * @param strategy  generation strategy used
     * @param seed      seed the value was derived from
     * @param schemaId  schema the value was generated from
     * @param digest    digest of the generated value
     * @param violation constraint deliberately broken, or null
     */
    record InputsGenerated(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            String field,
            String strategy,
            long seed,
            String schemaId,
            String digest,
            String violation
    ) implements RunEvent {
        public InputsGenerated {
            eventType = "INPUTS_GENERATED";
        }
    }

    /**
     * A message was published to a channel, and acknowledged.
     * <p>
     * The coordinates are what makes a run investigable afterwards: they say
     * exactly which record on the broker this step wrote, without the journal
     * holding the payload the evidence policy governs.
     *
     * @param channel   topic or channel written to
     * @param partition partition the broker chose, -1 when unknown
     * @param offset    position the record was written at, -1 when unknown
     * @param key       message key, null when it had none
     * @param digest    digest of the payload that was sent
     */
    record MessagePublished(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            String channel,
            int partition,
            long offset,
            String key,
            String digest
    ) implements RunEvent {
        public MessagePublished {
            eventType = "MESSAGE_PUBLISHED";
        }
    }

    /**
     * An observation window closed, and this is what it saw.
     * <p>
     * {@code observed} counts everything the window contained and
     * {@code matched} counts what the step's selector picked out of it. The two
     * differing is the normal case on a shared channel, and their being equal
     * on a busy channel is the sign of a scenario that forgot to select.
     *
     * @param channel  topic or channel read from
     * @param observed messages the window contained
     * @param matched  messages the step's selector accepted
     * @param waitedMs how long the window was open for
     * @param requestedWaitMs how long the step asked for, which the run's
     *                        per-request timeout may have shortened; a
     *                        scenario's stated wait must not read as fact when
     *                        it was not honoured
     */
    record MessagesObserved(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            String channel,
            long observed,
            int matched,
            long waitedMs,
            long requestedWaitMs
    ) implements RunEvent {
        public MessagesObserved {
            eventType = "MESSAGES_OBSERVED";
        }
    }

    /**
     * One poll of an eventually group was evaluated. {@code satisfied} reports
     * whether every condition held in this poll.
     */
    record ConditionPolled(
            String eventType,
            long timestamp,
            RunId runId,
            NodeId nodeId,
            int attempt,
            int maxPolls,
            long elapsedMs,
            boolean satisfied,
            String detail
    ) implements RunEvent {
        public ConditionPolled {
            eventType = "CONDITION_POLLED";
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
