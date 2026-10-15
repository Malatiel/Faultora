package dev.faultora.spi.evidence;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.result.AssertionResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Structural export policy, independent of the policy permitting evidence in memory.
 * Comparison values and provider prose are never exported. Error metadata is an
 * allowlist of bounded, typed facts; arbitrary strings and nested objects are not
 * diagnostic data. This is a projection, not a search for known secret strings.
 */
public final class EvidenceExport {
    private static final Set<String> CONNECTOR_CODES = Set.of(
            "CONNECTION_REFUSED", "CROSS_ORIGIN_BODY_REDIRECT", "DATABASE_TRANSIENT_ERROR",
            "DATABASE_UNAVAILABLE", "DESTINATION_BLOCKED", "EXECUTION_ERROR", "INSECURE_REDIRECT",
            "INVALID_PAYLOAD_LIMIT", "KAFKA_ACCESS_DENIED", "KAFKA_CANCELLED", "KAFKA_ERROR",
            "KAFKA_INVALID_OPERATION", "KAFKA_RETRIABLE_ERROR", "KAFKA_TIMEOUT", "KAFKA_UNAVAILABLE",
            "OBSERVATION_FAILED", "OBSERVATION_REFUSED", "OBSERVATION_TIMEOUT", "PARAMETER_MISSING",
            "REQUEST_TIMEOUT", "REQUEST_TOO_LARGE", "RESPONSE_TOO_LARGE", "SECRET_UNAVAILABLE",
            "TLS_ERROR", "TOO_MANY_REDIRECTS", "TOTAL_TIMEOUT", "DEADLINE_EXCEEDED", "CANCELLED",
            "REQUEST_BUDGET_EXCEEDED", "NO_CONNECTOR", "TARGET_NOT_FOUND",
            "FAULT_INJECTED_ERROR", "FAULT_DELAY_INTERRUPTED", "FAULT_RESPONSE_LOSS");
    private static final Set<String> INTEGER_FIELDS = Set.of("vendorCode", "maxBytes", "statusCode");

    private EvidenceExport() { }

    /** Preserve the verdict, without persisting values embedded in messages or details. */
    public static AssertionResult assertion(AssertionResult result) {
        String message = switch (result.outcome()) {
            case PASS -> "Assertion passed";
            case FAIL -> "Assertion failed";
            case INDETERMINATE -> "Assertion could not be evaluated: evidence unavailable or configuration invalid";
        };
        return new AssertionResult(result.outcome(), message, Map.of());
    }

    /** Only call for connector-origin errors; engine lifecycle errors have their own fixed diagnostics. */
    public static NormalizedError connectorError(NormalizedError error) {
        if (error == null) { return null; }
        String code = error.code();
        if (code == null || !(CONNECTOR_CODES.contains(code) || code.matches("HTTP_[1-5][0-9]{2}"))) {
            code = "CONNECTOR_ERROR";
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (error.metadata() != null) {
            for (String field : INTEGER_FIELDS) {
                Object value = error.metadata().get(field);
                if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
                    metadata.put(field, value);
                }
            }
            Object state = error.metadata().get("sqlState");
            if (state instanceof String text && text.matches("[A-Z0-9]{5}")) { metadata.put("sqlState", text); }
            Object closeFailed = error.metadata().get("connectionCloseFailed");
            if (closeFailed instanceof Boolean) { metadata.put("connectionCloseFailed", closeFailed); }
        }
        NormalizedError.ErrorCategory category = error.category() == null
                ? NormalizedError.ErrorCategory.UNKNOWN : error.category();
        return new NormalizedError(category, code,
                "Operation failed (" + code + "); provider details withheld by export policy",
                error.retryable(), Map.copyOf(metadata));
    }
}
