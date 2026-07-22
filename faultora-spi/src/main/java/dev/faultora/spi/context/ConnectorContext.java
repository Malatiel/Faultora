package dev.faultora.spi.context;

import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.SecretHandle;

import java.util.Map;
import java.util.function.Function;

/**
 * Context provided to connectors when preparing targets and executing operations.
 *
 * @param evidencePolicy    effective evidence policy
 * @param secretResolver    function to resolve secret handle IDs
 * @param connectTimeoutMs  TCP connect timeout
 * @param requestTimeoutMs  per-request timeout
 * @param totalTimeoutMs    total operation timeout
 * @param config            connector-specific configuration
 */
public record ConnectorContext(
        EvidencePolicy evidencePolicy,
        Function<String, SecretHandle> secretResolver,
        long connectTimeoutMs,
        long requestTimeoutMs,
        long totalTimeoutMs,
        Map<String, Object> config
) {}
