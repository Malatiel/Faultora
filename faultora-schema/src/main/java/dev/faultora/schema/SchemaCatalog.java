package dev.faultora.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        ObjectNode refinements = null;
        for (int depth = 0; depth < MAX_REFERENCE_DEPTH; depth++) {
            if (current == null || !current.isObject()) {
                return current;
            }
            JsonNode ref = current.get("$ref");
            if (ref == null || !ref.isTextual()) {
                return refinements == null ? current : refine(current, refinements);
            }
            // Keywords written beside a $ref refine what it points at — that
            // is how an example declared on a media type reaches a shared
            // component schema without altering the component itself.
            for (var field : current.properties()) {
                if (!"$ref".equals(field.getKey())) {
                    if (refinements == null) {
                        refinements = MAPPER.createObjectNode();
                    }
                    refinements.set(field.getKey(), field.getValue());
                }
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

    private JsonNode refine(JsonNode target, ObjectNode refinements) {
        ObjectNode refined = target.deepCopy();
        refinements.properties().forEach(field -> refined.set(field.getKey(), field.getValue()));
        return refined;
    }

    /**
     * A copy of the schema with every {@code $ref} replaced by what it points
     * at, so the result can be understood without the catalog.
     * <p>
     * Consumers that receive a schema on its own — an assertion checking a
     * response, a plan travelling to another process — cannot resolve
     * references later. Inlining at the point where the catalog is still
     * available is what makes the schema self-contained.
     * <p>
     * A schema that refers to itself, directly or through a chain, is legal
     * and cannot be inlined to the end. Expansion stops at the repeat and
     * leaves an unconstrained schema there: it accepts what it can no longer
     * describe, rather than rejecting a document for the shape of its own
     * definition.
     */
    public JsonNode inline(JsonNode schema) {
        return inline(schema, new java.util.LinkedHashSet<>(), 0);
    }

    private JsonNode inline(JsonNode schema, java.util.Set<String> expanding, int depth) {
        if (schema == null || !schema.isContainerNode() || depth > MAX_REFERENCE_DEPTH) {
            return schema;
        }
        if (schema.isArray()) {
            ArrayNode inlined = MAPPER.createArrayNode();
            schema.forEach(item -> inlined.add(inline(item, expanding, depth + 1)));
            return inlined;
        }

        JsonNode ref = schema.get("$ref");
        if (ref != null && ref.isTextual()) {
            String name = nameOf(ref.asText());
            JsonNode target = schemasById.get(name);
            if (target == null || !expanding.add(name)) {
                // Unknown, or already being expanded on this path.
                return target == null ? MAPPER.createObjectNode() : MAPPER.createObjectNode();
            }
            ObjectNode expanded = (ObjectNode) inline(target, expanding, depth + 1);
            expanding.remove(name);
            // Keywords beside a $ref refine the target; JSON Schema 2020 says
            // they apply, and a media-type example is attached exactly so.
            schema.properties().forEach(field -> {
                if (!"$ref".equals(field.getKey())) {
                    expanded.set(field.getKey(), inline(field.getValue(), expanding, depth + 1));
                }
            });
            return expanded;
        }

        ObjectNode inlined = MAPPER.createObjectNode();
        schema.properties().forEach(field ->
                inlined.set(field.getKey(), inline(field.getValue(), expanding, depth + 1)));
        return inlined;
    }

    private static String nameOf(String reference) {
        return reference.startsWith(COMPONENT_PREFIX)
                ? reference.substring(COMPONENT_PREFIX.length())
                : reference.substring(reference.lastIndexOf('/') + 1);
    }
}
