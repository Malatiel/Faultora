package dev.faultora.spi.context;

import java.util.Map;

/**
 * Context provided to fault providers during injection and rollback.
 *
 * @param targetScope      target identifier the fault applies to
 * @param hardExpiryMs     absolute deadline (epoch millis) for the fault
 * @param config           fault-specific configuration
 */
public record FaultContext(
        String targetScope,
        long hardExpiryMs,
        Map<String, Object> config
) {}
