package dev.faultora.model.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultora.model.identifier.RunId;

import java.util.Map;

/**
 * Security-relevant audit event recorded during a run lifecycle.
 * Append-only, never contains secrets or captured payload bodies.
 *
 * @param eventId      unique event identifier
 * @param eventType    type of audit event
 * @param timestamp    epoch millis
 * @param runId        associated run, if any
 * @param actor        identity that triggered the event
 * @param decision     policy decision outcome
 * @param targetId     target involved, if any
 * @param extensionId  extension involved, if any
 * @param details      additional sanitized structured data
 */
public record RunAuditEvent(
        String eventId,
        AuditEventType eventType,
        long timestamp,
        RunId runId,
        String actor,
        String decision,
        String targetId,
        String extensionId,
        Map<String, Object> details
) {
    public enum AuditEventType {
        /** Run lifecycle events */
        RUN_STARTED,
        RUN_COMPLETED,
        RUN_FAILED,
        RUN_CANCELLED,
        /** Policy decisions */
        POLICY_EVALUATED,
        POLICY_VIOLATION,
        TARGET_DESTINATION_BLOCKED,
        /** Secret lifecycle */
        SECRET_ACCESSED,
        SECRET_DENIED,
        SECRET_EXPIRED,
        /** Extension lifecycle */
        EXTENSION_LOADED,
        EXTENSION_DENIED,
        EXTENSION_FAILED,
        /** Evidence lifecycle */
        EVIDENCE_CAPTURED,
        EVIDENCE_REDACTED,
        EVIDENCE_EXPORTED,
        /** Fault lifecycle */
        FAULT_INJECTED,
        FAULT_ROLLED_BACK,
        FAULT_EXPIRED,
        /** Administrative */
        CONFIGURATION_CHANGED,
        ARTIFACT_EXPORTED
    }
}
