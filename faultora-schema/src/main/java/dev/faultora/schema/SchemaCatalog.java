package dev.faultora.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.identifier.SchemaId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The schemas of one catalog, resolvable by ID and by {@code $ref}.
 * <p>
 * Schemas arrive as raw JSON because that is what the source specification
 * said; this class is the single place that turns them into a navigable tree
 * and follows references between them. Reference depth is bounded, so a
 * self-referential schema — legal and common — cannot make resolution loop
 * forever.
 */
public final class SchemaCatalog {

    /** Longest chain of {@code $ref} hops followed before giving up. */
    public static final int MAX_REFERENCE_DEPTH = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMPONENT_PREFIX = "#/components/schemas/";

    private final Map<String, JsonNode> schemasById = new LinkedHashMap<>();

    public SchemaCatalog(Map<SchemaId, DataSchema> schemas) {
        if (schemas == null) {
            return;
        }
        schemas.forEach((id, schema) ->
                schemasById.put(id.value(), MAPPER.valueToTree(schema.definition())));
    }

    /** The schema with this ID, or null when the catalog does not declare it. */
    public JsonNode schema(SchemaId id) {
        return id == null ? null : schemasById.get(id.value());
    }

    /**
     * Follow {@code $ref} until an inline schema is reached.
     *
     * @param schema the schema node, possibly a reference
     * @return the referenced schema, or the input when it is not a reference
     * @throws SchemaException when a reference is unresolvable or cyclic
     */
    public JsonNode dereference(JsonNode schema, String path) {
        JsonNode current = schema;
        for (int depth = 0; depth < MAX_REFERENCE_DEPTH; depth++) {
            if (current == null || !current.isObject()) {
                return current;
            }
            JsonNode ref = current.get("$ref");
            if (ref == null || !ref.isTextual()) {
                return current;
            }
            String reference = ref.asText();
            JsonNode target = schemasById.get(nameOf(reference));
            if (target == null) {
                throw new SchemaException(path, "unresolvable reference: " + reference);
            }
            current = target;
        }
        throw new SchemaException(path,
                "reference chain deeper than " + MAX_REFERENCE_DEPTH + " hops");
    }

    private static String nameOf(String reference) {
        return reference.startsWith(COMPONENT_PREFIX)
                ? reference.substring(COMPONENT_PREFIX.length())
                : reference.substring(reference.lastIndexOf('/') + 1);
    }
}
