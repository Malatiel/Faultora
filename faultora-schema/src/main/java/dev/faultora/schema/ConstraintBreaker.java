package dev.faultora.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Breaks exactly one constraint of an otherwise valid payload.
 * <p>
 * Negative testing is only informative when the payload violates one thing:
 * if several constraints are broken at once, a rejecting target proves nothing
 * about the constraint under test, and an accepting one is impossible to
 * diagnose. The chosen violation is described in words so the report can say
 * what the target was asked to reject.
 */
final class ConstraintBreaker {

    private ConstraintBreaker() {
    }

    /**
     * Mutate {@code value} so that it violates its schema in one way.
     *
     * @return a description of the violation introduced
     * @throws SchemaException when the schema constrains nothing that can be
     *                         broken without inventing a different document
     */
    static String breakOne(
            JsonNode value, JsonNode schema, SchemaCatalog catalog, java.util.Random random) {
        if (!(value instanceof ObjectNode object) || schema == null || !schema.isObject()) {
            throw new SchemaException("$",
                    "the invalid strategy needs an object schema to break a constraint in");
        }

        List<String> required = ValueGenerator.requiredProperties(schema);
        if (!required.isEmpty()) {
            String omitted = required.get(random.nextInt(required.size()));
            object.remove(omitted);
            return "required property '" + omitted + "' omitted";
        }

        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            for (var property : properties.properties()) {
                String name = property.getKey();
                if (!object.has(name)) {
                    continue;
                }
                String violation = breakProperty(
                        object, name, catalog.dereference(property.getValue(), "$." + name));
                if (violation != null) {
                    return violation;
                }
            }
        }
        throw new SchemaException("$",
                "the schema declares no constraint that can be violated; "
                        + "use an explicit input to send a malformed request");
    }

    /** Break one property's constraint, or return null when it has none. */
    private static String breakProperty(ObjectNode object, String name, JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return null;
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray() && !enumValues.isEmpty()) {
            object.put(name, "not-a-permitted-value");
            return "property '" + name + "' set outside its enum";
        }
        if (schema.hasNonNull("maximum")) {
            long maximum = schema.get("maximum").asLong();
            object.put(name, maximum + 1);
            return "property '" + name + "' set above its maximum of " + maximum;
        }
        if (schema.hasNonNull("minimum")) {
            long minimum = schema.get("minimum").asLong();
            object.put(name, minimum - 1);
            return "property '" + name + "' set below its minimum of " + minimum;
        }
        if (schema.hasNonNull("maxLength")) {
            int maxLength = schema.get("maxLength").asInt();
            object.put(name, "x".repeat(maxLength + 1));
            return "property '" + name + "' set longer than its maxLength of " + maxLength;
        }
        String type = schema.path("type").asText("");
        if ("string".equals(type)) {
            object.put(name, 42);
            return "property '" + name + "' set to a number where a string is declared";
        }
        if ("integer".equals(type) || "number".equals(type) || "boolean".equals(type)) {
            object.put(name, "not-a-" + type);
            return "property '" + name + "' set to a string where a " + type + " is declared";
        }
        return null;
    }
}
