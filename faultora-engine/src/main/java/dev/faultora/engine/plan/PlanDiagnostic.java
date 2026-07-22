package dev.faultora.engine.plan;

/**
 * Diagnostic message from plan compilation.
 */
public record PlanDiagnostic(
        Severity severity,
        String message,
        String stepId,
        String phase
) {
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public static PlanDiagnostic error(String phase, String stepId, String message) {
        return new PlanDiagnostic(Severity.ERROR, message, stepId, phase);
    }

    public static PlanDiagnostic warning(String phase, String stepId, String message) {
        return new PlanDiagnostic(Severity.WARNING, message, stepId, phase);
    }
}
