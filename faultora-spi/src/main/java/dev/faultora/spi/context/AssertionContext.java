package dev.faultora.spi.context;

import java.util.Map;

/**
 * Context provided to assertion providers during evaluation.
 *
 * @param nodeId          node being asserted on
 * @param config          assertion-specific configuration
 */
public record AssertionContext(
        String nodeId,
        Map<String, Object> config
) {}
