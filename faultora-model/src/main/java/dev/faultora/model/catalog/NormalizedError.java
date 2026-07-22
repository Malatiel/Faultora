package dev.faultora.model.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Protocol-neutral error representation.
 * Connectors normalize their specific exceptions into this form.
 * Never contains connector-specific exception types.
 *
 * @param category     broad error category
 * @param code         connector-specific error code (e.g. HTTP status, Kafka error code)
 * @param message      human-readable description (sanitized, no secrets)
 * @param retryable    whether the operation may succeed on retry
 * @param metadata     additional structured error data
 */
public record NormalizedError(
        ErrorCategory category,
        String code,
        String message,
        boolean retryable,
        Map<String, Object> metadata
) {
    public enum ErrorCategory {
        /** DNS resolution, TLS handshake, connection refused */
        NETWORK,
        /** Request or response deadline exceeded */
        TIMEOUT,
        /** Operation was cancelled */
        CANCELLED,
        /** Request or response validation failed */
        VALIDATION,
        /** Target returned an error status */
        TARGET_ERROR,
        /** Policy violation (destination, safety, resource limits) */
        POLICY_VIOLATION,
        /** Extension or connector internal error */
        INTERNAL,
        /** Unknown or unclassified error */
        UNKNOWN
    }
}
