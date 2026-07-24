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
 * @param expectError when true, the step passes only if the operation fails
 *                    (used for steps executed under an injected fault)
 * @param steps       child operation steps (for parallel groups)
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
        boolean expectError,
        List<ScenarioStep> steps,
        Map<String, Object> metadata
) {
    /** Convenience constructor for steps without {@code expectError} or children. */
    public ScenarioStep(
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
        this(id, type, operationId, inputs, outputAs, dependsOn, timeout, retry,
                false, null, metadata);
    }

    /** Convenience constructor for steps without children. */
    public ScenarioStep(
            String id,
            String type,
            String operationId,
            Map<String, Object> inputs,
            String outputAs,
            List<String> dependsOn,
            String timeout,
            RetryPolicy retry,
            boolean expectError,
            Map<String, Object> metadata
    ) {
        this(id, type, operationId, inputs, outputAs, dependsOn, timeout, retry,
                expectError, null, metadata);
    }

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
