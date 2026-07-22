package dev.faultora.spec.model;

import java.util.Map;

/**
 * Scenario metadata.
 *
 * @param name         unique scenario name
 * @param description  human-readable description
 * @param labels       key-value labels for filtering
 * @param annotations  additional metadata
 */
public record ScenarioMetadata(
        String name,
        String description,
        Map<String, String> labels,
        Map<String, String> annotations
) {}
