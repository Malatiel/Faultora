package dev.faultora.model.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultora.model.identifier.AuthSchemeId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;

import java.util.List;
import java.util.Map;

/**
 * Describes a reachable target endpoint.
 *
 * @param id             stable target identifier
 * @param name           human-readable name
 * @param baseUrl        base URL or connection string
 * @param protocols      protocols supported by this target
 * @param authSchemeIds  authentication schemes that apply
 * @param metadata       target-level metadata from the source specification
 */
public record TargetDefinition(
        TargetId id,
        String name,
        String baseUrl,
        List<ProtocolId> protocols,
        List<AuthSchemeId> authSchemeIds,
        Map<String, Object> metadata
) {}
