package dev.faultora.spi.context;

import java.util.Map;

/**
 * Context provided to assertion providers during evaluation.
 * <p>
 * The schema, when present, is the part of the contract this assertion checks
 * against, resolved during plan compilation and passed as plain JSON data.
 * Resolving it early means an assertion that names a schema the catalog does
 * not declare fails before the run starts, and it keeps the assertion contract
 * free of catalog and specification types.
 *
 * @param nodeId node being asserted on
 * @param config assertion-specific configuration
 * @param schema resolved schema for this assertion, or null when it needs none
 */
public record AssertionContext(
        String nodeId,
        Map<String, Object> config,
        Map<String, Object> schema
) {
    /** Context for an assertion that checks evidence without a schema. */
    public AssertionContext(String nodeId, Map<String, Object> config) {
        this(nodeId, config, null);
    }
}
