package dev.faultora.model.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.model.identifier.TargetId;

import java.util.Map;

/**
 * A single callable operation in the canonical catalog.
 * Protocol metadata is validated and interpreted by the owning connector.
 *
 * @param id              stable operation identifier
 * @param protocol        protocol this operation speaks (e.g. "http", "kafka")
 * @param target          which target hosts this operation
 * @param safety          safety classification (importer-proposed, scenario-confirmed)
 * @param inputs          named input definitions
 * @param requestSchemaId schema ID for the request body, null if none
 * @param outcomes        mapping from outcome selector to response schema ID
 * @param protocolMetadata connector-specific structured metadata (versioned, validated by connector)
 */
public record OperationDefinition(
        OperationId id,
        ProtocolId protocol,
        TargetId target,
        SafetyClassification safety,
        Map<String, InputDefinition> inputs,
        SchemaId requestSchemaId,
        Map<String, SchemaId> outcomes,
        Map<String, Object> protocolMetadata
) {}
