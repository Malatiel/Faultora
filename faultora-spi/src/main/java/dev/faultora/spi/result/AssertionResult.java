package dev.faultora.spi.result;

import java.util.Map;

/**
 * Result of evaluating an assertion against collected evidence.
 * Provider diagnostics may contain values authorized for in-memory computation.
 * Apply {@link dev.faultora.spi.evidence.EvidenceExport#assertion(AssertionResult)}
 * before journaling, reporting, or otherwise exporting a provider result.
 *
 * @param outcome    assertion outcome
 * @param message    provider's human-readable diagnostic
 * @param details    provider's structured comparison details, internal until projected
 */
public record AssertionResult(
        Outcome outcome,
        String message,
        Map<String, Object> details
) {
    public enum Outcome {
        /** The assertion passed. */
        PASS,
        /** The assertion failed. */
        FAIL,
        /** The assertion could not be evaluated (missing evidence, unsupported type). */
        INDETERMINATE
    }

    public static AssertionResult pass(String message) {
        return new AssertionResult(Outcome.PASS, message, Map.of());
    }

    public static AssertionResult fail(String message, Map<String, Object> details) {
        return new AssertionResult(Outcome.FAIL, message, details);
    }

    public static AssertionResult indeterminate(String message) {
        return new AssertionResult(Outcome.INDETERMINATE, message, Map.of());
    }
}
