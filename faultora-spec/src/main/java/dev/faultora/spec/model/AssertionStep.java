package dev.faultora.spec.model;

import java.util.Map;

/**
 * An assertion step in the scenario.
 *
 * @param id             stable step identifier
 * @param assertionType  type of assertion (status, header, jsonpath, duration)
 * @param params         assertion parameters
 * @param targetStep     step ID whose output to assert on (default: last execute step)
 * @param dependsOn      step IDs that must complete first
 * @param message        custom assertion message
 * @param metadata       additional metadata
 */
public record AssertionStep(
        String id,
        String assertionType,
        Map<String, Object> params,
        String targetStep,
        java.util.List<String> dependsOn,
        String message,
        Map<String, Object> metadata
) {}
