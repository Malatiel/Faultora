package dev.faultora.spi.result;

import dev.faultora.model.catalog.NormalizedError;

import java.util.List;
import java.util.Map;

/**
 * Result of executing a single operation through a connector.
 *
 * @param statusCode      protocol-specific status (e.g. HTTP status code), -1 if not applicable
 * @param headers         response headers (only if evidence policy allows)
 * @param body            response body bytes (only if evidence policy allows), null otherwise
 * @param durationMs      elapsed time in milliseconds
 * @param error           normalized error if the operation failed, null on success
 * @param protocolEvidence protocol-specific evidence values
 * @param evidenceDigests digests of captured evidence blobs
 */
public record OperationResult(
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body,
        long durationMs,
        NormalizedError error,
        Map<String, Object> protocolEvidence,
        Map<String, String> evidenceDigests
) {
    public boolean isSuccess() {
        return error == null;
    }

    public static OperationResult success(
            int statusCode,
            Map<String, List<String>> headers,
            byte[] body,
            long durationMs,
            Map<String, Object> protocolEvidence
    ) {
        return new OperationResult(statusCode, headers, body, durationMs, null, protocolEvidence, Map.of());
    }

    public static OperationResult failure(NormalizedError error, long durationMs) {
        return new OperationResult(-1, Map.of(), null, durationMs, error, Map.of(), Map.of());
    }
}
