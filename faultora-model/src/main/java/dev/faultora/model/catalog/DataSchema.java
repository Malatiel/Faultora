package dev.faultora.model.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultora.model.identifier.SchemaId;

import java.util.Map;

/**
 * A JSON Schema definition normalized from the source specification.
 *
 * @param id          stable schema identifier
 * @param schemaType  type name (e.g. "object", "string")
 * @param sourcePath  path in the original specification
 * @param definition  raw schema JSON for downstream validation
 */
public record DataSchema(
        SchemaId id,
        String schemaType,
        String sourcePath,
        Map<String, Object> definition
) {}
