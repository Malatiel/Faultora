package dev.faultora.spec.model;

import java.util.Map;

/**
 * A fault injection step in the scenario.
 *
 * @param id          stable step identifier
 * @param faultType   type of fault (e.g. "http-latency", "connection-reset")
 * @param targetScope target to apply the fault to
 * @param params      fault-specific parameters
 * @param duration    fault duration expression
 * @param dependsOn   step IDs that must complete first
 * @param metadata    additional metadata
 */
public record FaultStep(
        String id,
        String faultType,
        String targetScope,
        Map<String, Object> params,
        String duration,
        java.util.List<String> dependsOn,
        Map<String, Object> metadata
) {}
