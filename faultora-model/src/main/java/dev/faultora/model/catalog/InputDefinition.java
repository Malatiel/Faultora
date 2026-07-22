package dev.faultora.model.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultora.model.identifier.SchemaId;

import java.util.Map;

/**
 * Describes a single input parameter for an operation.
 *
 * @param name         parameter name
 * @param location     where the value is placed (path, query, header, cookie, body)
 * @param required     whether the input is mandatory
 * @param schemaId     reference to the schema for this input, if any
 * @param defaultValue default value expression, if any
 * @param metadata     additional parameter-level metadata from the source
 */
public record InputDefinition(
        String name,
        InputLocation location,
        boolean required,
        SchemaId schemaId,
        String defaultValue,
        Map<String, Object> metadata
) {
    public enum InputLocation {
        PATH,
        QUERY,
        HEADER,
        COOKIE,
        BODY
    }
}
