package dev.faultora.model.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.TargetId;

import java.util.List;
import java.util.Set;

/**
 * Execution policy constraining what a run can do to targets.
 *
 * @param allowedTargets           explicit target allowlist (empty = all allowed)
 * @param allowedOperationClasses  permitted safety classifications
 * @param maxRequests              maximum total requests in a run
 * @param maxConcurrency           maximum concurrent operations
 * @param maxDurationMs            maximum run duration in milliseconds
 * @param maxPayloadBytes          maximum request/response body size
 * @param allowedFaultTypes        fault type allowlist (empty = no faults)
 * @param allowedEnvironments      environment name allowlist
 */
public record TargetPolicy(
        Set<TargetId> allowedTargets,
        Set<SafetyClassification> allowedOperationClasses,
        int maxRequests,
        int maxConcurrency,
        long maxDurationMs,
        long maxPayloadBytes,
        Set<String> allowedFaultTypes,
        Set<String> allowedEnvironments
) {
    public TargetPolicy {
        allowedTargets = allowedTargets == null ? Set.of() : Set.copyOf(allowedTargets);
        allowedOperationClasses = allowedOperationClasses == null
                ? Set.of()
                : Set.copyOf(allowedOperationClasses);
        allowedFaultTypes = allowedFaultTypes == null ? Set.of() : Set.copyOf(allowedFaultTypes);
        allowedEnvironments = allowedEnvironments == null
                ? Set.of()
                : Set.copyOf(allowedEnvironments);
        if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
        if (maxConcurrency <= 0) throw new IllegalArgumentException("maxConcurrency must be positive");
        if (maxDurationMs <= 0) throw new IllegalArgumentException("maxDurationMs must be positive");
        if (maxPayloadBytes <= 0) throw new IllegalArgumentException("maxPayloadBytes must be positive");
    }
}
