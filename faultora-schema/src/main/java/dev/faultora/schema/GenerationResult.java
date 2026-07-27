package dev.faultora.schema;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A generated value and what was done to it.
 *
 * @param value     the generated JSON value
 * @param violation for the invalid strategy, the constraint deliberately
 *                  broken; null when the value satisfies its schema
 */
public record GenerationResult(JsonNode value, String violation) {

    public static GenerationResult valid(JsonNode value) {
        return new GenerationResult(value, null);
    }

    public boolean isViolating() {
        return violation != null;
    }
}
