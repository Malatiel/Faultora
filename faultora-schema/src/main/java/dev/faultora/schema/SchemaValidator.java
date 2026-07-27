package dev.faultora.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks a JSON value against a catalog schema.
 * <p>
 * It covers the same constructs {@link ValueGenerator} understands — which is
 * what makes it a real check on the generator rather than a restatement of it:
 * a generated payload that this validator rejects is a generator defect, and
 * the acceptance test says so.
 * <p>
 * Violations are collected rather than thrown, because a caller wants to see
 * everything wrong with a payload at once, not the first problem only.
 */
public final class SchemaValidator {

    private final SchemaCatalog catalog;

    public SchemaValidator(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    /** Everything wrong with {@code value} under {@code schema}; empty when valid. */
    public List<Violation> validate(JsonNode value, JsonNode schema) {
        List<Violation> violations = new ArrayList<>();
        check(value, schema, "$", violations, 0);
        return violations;
    }

    /** Whether the value satisfies the schema. */
    public boolean isValid(JsonNode value, JsonNode schema) {
        return validate(value, schema).isEmpty();
    }

    private void check(
            JsonNode value, JsonNode rawSchema, String path,
            List<Violation> violations, int depth) {
        if (depth > ValueGenerator.MAX_DEPTH) {
            return;
        }
        JsonNode schema = catalog.dereference(rawSchema, path);
        if (schema == null || !schema.isObject() || value == null) {
            return;
        }

        JsonNode constant = schema.get("const");
        if (constant != null && !constant.equals(value)) {
            violations.add(new Violation(path, "must equal " + constant));
            return;
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            boolean matches = false;
            for (JsonNode allowed : enumValues) {
                matches |= allowed.equals(value);
            }
            if (!matches) {
                violations.add(new Violation(path, "is not one of " + enumValues));
            }
            return;
        }
        JsonNode allOf = schema.get("allOf");
        if (allOf != null && allOf.isArray()) {
            allOf.forEach(branch -> check(value, branch, path, violations, depth + 1));
            return;
        }
        JsonNode branches = schema.has("oneOf") ? schema.get("oneOf") : schema.get("anyOf");
        if (branches != null && branches.isArray() && !branches.isEmpty()) {
            boolean anyBranchAccepts = false;
            for (JsonNode branch : branches) {
                List<Violation> branchViolations = new ArrayList<>();
                check(value, branch, path, branchViolations, depth + 1);
                anyBranchAccepts |= branchViolations.isEmpty();
            }
            if (!anyBranchAccepts) {
                violations.add(new Violation(path, "matches none of the declared alternatives"));
            }
            return;
        }

        String type = declaredType(schema);
        if (type == null) {
            return;
        }
        if (!matchesType(value, type)) {
            violations.add(new Violation(path, "is " + value.getNodeType()
                    + " where " + type + " is declared"));
            return;
        }

        switch (type) {
            case "object" -> checkObject(value, schema, path, violations, depth);
            case "array" -> checkArray(value, schema, path, violations, depth);
            case "string" -> checkString(value, schema, path, violations);
            case "integer", "number" -> checkNumber(value, schema, path, violations);
            default -> { /* boolean and null carry no further constraints */ }
        }
    }

    private void checkObject(
            JsonNode value, JsonNode schema, String path,
            List<Violation> violations, int depth) {
        for (String required : ValueGenerator.requiredProperties(schema)) {
            if (!value.hasNonNull(required)) {
                violations.add(new Violation(
                        path + "." + required, "is required but missing"));
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }
        properties.properties().forEach(property -> {
            JsonNode child = value.get(property.getKey());
            if (child != null) {
                check(child, property.getValue(), path + "." + property.getKey(),
                        violations, depth + 1);
            }
        });
    }

    private void checkArray(
            JsonNode value, JsonNode schema, String path,
            List<Violation> violations, int depth) {
        if (schema.hasNonNull("minItems") && value.size() < schema.get("minItems").asInt()) {
            violations.add(new Violation(path,
                    "has " + value.size() + " items, fewer than the required "
                            + schema.get("minItems").asInt()));
        }
        if (schema.hasNonNull("maxItems") && value.size() > schema.get("maxItems").asInt()) {
            violations.add(new Violation(path,
                    "has " + value.size() + " items, more than the permitted "
                            + schema.get("maxItems").asInt()));
        }
        JsonNode items = schema.get("items");
        if (items == null) {
            return;
        }
        for (int index = 0; index < value.size(); index++) {
            check(value.get(index), items, path + "[" + index + "]", violations, depth + 1);
        }
    }

    private void checkString(
            JsonNode value, JsonNode schema, String path, List<Violation> violations) {
        String text = value.asText();
        if (schema.hasNonNull("minLength") && text.length() < schema.get("minLength").asInt()) {
            violations.add(new Violation(path,
                    "is shorter than minLength " + schema.get("minLength").asInt()));
        }
        if (schema.hasNonNull("maxLength") && text.length() > schema.get("maxLength").asInt()) {
            violations.add(new Violation(path,
                    "is longer than maxLength " + schema.get("maxLength").asInt()));
        }
        if (schema.hasNonNull("pattern")) {
            try {
                if (!Pattern.compile(schema.get("pattern").asText()).matcher(text).find()) {
                    violations.add(new Violation(path,
                            "does not match pattern " + schema.get("pattern").asText()));
                }
            } catch (PatternSyntaxException unusable) {
                // A pattern the platform cannot compile says nothing about the
                // value; reporting it as a violation would blame the payload.
                violations.add(new Violation(path,
                        "declares a pattern this runtime cannot compile: "
                                + unusable.getDescription()));
            }
        }
    }

    private void checkNumber(
            JsonNode value, JsonNode schema, String path, List<Violation> violations) {
        double number = value.asDouble();
        boolean exclusiveMinimumFlag = isExclusiveFlag(schema, "exclusiveMinimum");
        boolean exclusiveMaximumFlag = isExclusiveFlag(schema, "exclusiveMaximum");

        if (schema.hasNonNull("minimum")) {
            double minimum = schema.get("minimum").asDouble();
            if (exclusiveMinimumFlag ? number <= minimum : number < minimum) {
                violations.add(new Violation(path,
                        "is below " + (exclusiveMinimumFlag ? "exclusive " : "")
                                + "minimum " + minimum));
            }
        }
        if (schema.hasNonNull("maximum")) {
            double maximum = schema.get("maximum").asDouble();
            if (exclusiveMaximumFlag ? number >= maximum : number > maximum) {
                violations.add(new Violation(path,
                        "is above " + (exclusiveMaximumFlag ? "exclusive " : "")
                                + "maximum " + maximum));
            }
        }
        if (isExclusiveBound(schema, "exclusiveMinimum")
                && number <= schema.get("exclusiveMinimum").asDouble()) {
            violations.add(new Violation(path, "is not above exclusive minimum "
                    + schema.get("exclusiveMinimum").asDouble()));
        }
        if (isExclusiveBound(schema, "exclusiveMaximum")
                && number >= schema.get("exclusiveMaximum").asDouble()) {
            violations.add(new Violation(path, "is not below exclusive maximum "
                    + schema.get("exclusiveMaximum").asDouble()));
        }
        if (schema.hasNonNull("multipleOf")) {
            double multipleOf = schema.get("multipleOf").asDouble();
            if (multipleOf > 0 && Math.abs(number % multipleOf) > 1e-9) {
                violations.add(new Violation(path, "is not a multiple of " + multipleOf));
            }
        }
    }

    /** The OpenAPI 3.0 spelling: a flag beside the inclusive bound. */
    private boolean isExclusiveFlag(JsonNode schema, String field) {
        JsonNode exclusive = schema.get(field);
        return exclusive != null && exclusive.isBoolean() && exclusive.asBoolean();
    }

    /** The JSON Schema 2020 spelling: a bound with a value of its own. */
    private boolean isExclusiveBound(JsonNode schema, String field) {
        JsonNode exclusive = schema.get(field);
        return exclusive != null && exclusive.isNumber();
    }

    private String declaredType(JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type != null && type.isTextual()) {
            return type.asText();
        }
        if (schema.has("properties")) return "object";
        if (schema.has("items")) return "array";
        return null;
    }

    private boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    /**
     * One way in which a value fails its schema.
     *
     * @param path    JSON path of the offending value
     * @param message what is wrong with it
     */
    public record Violation(String path, String message) {
        @Override
        public String toString() {
            return path + " " + message;
        }
    }
}
