package dev.faultora.spi.context;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only view of evidence collected during a run.
 * Assertions consume evidence through this interface; they cannot mutate it.
 */
public interface EvidenceView {

    /**
     * Get the HTTP status code, if this was an HTTP operation.
     */
    Optional<Integer> statusCode();

    /**
     * Get response headers (only if evidence policy allows header capture).
     */
    Map<String, List<String>> responseHeaders();

    /**
     * Get response body as bytes (only if evidence policy allows body capture).
     * Returns empty if bodies are not captured.
     */
    Optional<byte[]> responseBody();

    /**
     * Get response body parsed as a JSON tree (only if body is JSON and captured).
     */
    Optional<com.fasterxml.jackson.databind.JsonNode> responseJson();

    /**
     * Get the elapsed duration of the operation in milliseconds.
     */
    long durationMs();

    /**
     * Get a normalized error, if the operation failed.
     */
    Optional<dev.faultora.model.catalog.NormalizedError> error();

    /**
     * Get protocol-specific evidence values.
     */
    Map<String, Object> protocolEvidence();
}
