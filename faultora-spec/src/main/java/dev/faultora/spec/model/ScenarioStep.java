package dev.faultora.spec.model;

import java.util.List;
import java.util.Map;

/**
 * A step in the scenario (setup, execute, or cleanup).
 *
 * @param id          stable step identifier
 * @param type        step type (operation or wait in the current format)
 * @param operationId operation to invoke (for operation steps)
 * @param inputs      input expressions
 * @param outputAs    variable name to bind output to
 * @param dependsOn   step IDs that must complete first
 * @param timeout     step timeout expression
 * @param retry       retry policy
 * @param metadata    additional step metadata
 */
public record ScenarioStep(
        String id,
        String type,
        String operationId,
        Map<String, Object> inputs,
        String outputAs,
        List<String> dependsOn,
        String timeout,
        RetryPolicy retry,
        Map<String, Object> metadata
) {
    /**
     * Retry policy for a step.
     *
     * @param maxAttempts      maximum number of attempts
     * @param backoffMs        initial backoff in milliseconds
     * @param backoffMultiplier backoff multiplier for exponential backoff
     * @param maxBackoffMs     maximum backoff in milliseconds
     */
    public record RetryPolicy(
            int maxAttempts,
            long backoffMs,
            double backoffMultiplier,
            long maxBackoffMs
    ) {}
}
