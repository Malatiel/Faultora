package dev.faultora.spec.model;

import java.util.List;
import java.util.Map;

/**
 * A step in the scenario (setup, execute, or cleanup).
 *
 * @param id          stable step identifier
 * @param type        step type (operation, wait, parallel, repeat, or eventually)
 * @param operationId operation to invoke (for operation steps)
 * @param inputs      input expressions
 * @param outputAs    variable name to bind output to
 * @param dependsOn   step IDs that must complete first
 * @param timeout     step timeout expression; for eventually steps, the total
 *                    polling budget
 * @param retry       retry policy
 * @param expectError when true, the step passes only if the operation fails
 *                    (used for steps executed under an injected fault)
 * @param steps       child operation steps (for parallel, repeat, and
 *                    eventually groups)
 * @param count       number of iterations (for fixed repeat groups)
 * @param forEach     literal item list driving one iteration each (for
 *                    data-driven repeat groups)
 * @param interval    delay between polls (for eventually groups)
 * @param until       conditions that must all hold in one poll (for eventually
 *                    groups)
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
        Integer count,
        List<Object> forEach,
        String interval,
        List<Condition> until,
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

    /** Convenience constructor for steps without repeat or eventually fields. */
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
            List<ScenarioStep> steps,
            Map<String, Object> metadata
    ) {
        this(id, type, operationId, inputs, outputAs, dependsOn, timeout, retry,
                expectError, steps, null, null, null, null, metadata);
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
    ) {
        /** Whether every declared value is inside its allowed range. */
        public boolean isWithinRange() {
            return maxAttempts >= 1
                    && backoffMs >= 0
                    && backoffMultiplier >= 1
                    && maxBackoffMs >= 0;
        }

        /** Whether the policy asks for more attempts than the language allows. */
        public boolean exceedsAttemptLimit() {
            return maxAttempts > ScenarioLimits.MAX_RETRY_ATTEMPTS;
        }

        /** Whether the policy would re-execute a step at all. */
        public boolean retriesAtAll() {
            return maxAttempts > 1;
        }
    }

    /**
     * A condition evaluated against the polled step's evidence inside an
     * eventually group. Conditions reuse the assertion providers, so their
     * parameters are exactly those documented for the assertion type.
     *
     * @param assertionType assertion type (status, header, jsonpath, duration)
     * @param params        assertion parameters
     * @param message       optional human-readable intent
     */
    public record Condition(
            String assertionType,
            Map<String, Object> params,
            String message
    ) {}
}
