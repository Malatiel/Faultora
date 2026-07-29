package dev.faultora.importer.asyncapi;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.importer.source.SourceDocument;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.identifier.SchemaId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The schemas a document declares under {@code components}.
 * <p>
 * Only named schemas are collected here; a payload written inline on a message
 * is registered by the operation that carries it, under a name derived from
 * that message. Either way every schema an operation references is resolvable
 * from the catalog alone, which is what lets a scenario generate a payload and
 * assert against a contract without the original document.
 */
final class SchemaCollector {

    private static final String SOURCE_PATH = "#/components/schemas/";

    private final JsonNode root;

    SchemaCollector(JsonNode root) {
        this.root = root;
    }

    Map<SchemaId, DataSchema> collect() {
        Map<SchemaId, DataSchema> schemas = new LinkedHashMap<>();
        JsonNode components = SourceDocument.object(root, "components");
        JsonNode declared = SourceDocument.object(components, "schemas");
        if (declared == null) {
            return schemas;
        }
        declared.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode schema = entry.getValue();
            SchemaId id = new SchemaId(name);
            schemas.put(id, new DataSchema(
                    id, typeOf(schema), SOURCE_PATH + name,
                    AsyncApiImporter.definitionOf(schema)));
        });
        return schemas;
    }

    /** The declared type, or {@code object} when the schema leaves it implicit. */
    static String typeOf(JsonNode schema) {
        String type = SourceDocument.text(schema, "type");
        return type != null ? type : "object";
    }
}
