package dev.faultora.engine.plan;

import java.util.List;

/**
 * Result of plan compilation.
 *
 * @param plan        the compiled plan (null if compilation failed)
 * @param diagnostics compilation diagnostics
 */
public record PlanCompilationResult(
        ExecutionPlan plan,
        List<PlanDiagnostic> diagnostics
) {
    public boolean isSuccess() {
        return plan != null && diagnostics.stream().noneMatch(PlanDiagnostic::isError);
    }

    public List<PlanDiagnostic> errors() {
        return diagnostics.stream().filter(PlanDiagnostic::isError).toList();
    }
}
