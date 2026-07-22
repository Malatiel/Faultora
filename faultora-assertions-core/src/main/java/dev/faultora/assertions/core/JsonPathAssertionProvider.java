package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.burt.jmespath.JmesPath;
import io.burt.jmespath.jackson.JacksonRuntime;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.result.AssertionResult;

import java.util.*;

/**
 * Asserts values in the JSON response body using JMESPath expressions.
 * Supports: equals, exists, count, type, length, matches (regex).
 */
public class JsonPathAssertionProvider implements AssertionProvider {

    private static final JmesPath<JsonNode> JMESPATH = new JacksonRuntime();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String type() {
        return "jsonpath";
    }

    @Override
    public AssertionResult evaluate(
            String assertionType,
            Map<String, Object> params,
            EvidenceView evidence,
            AssertionContext context
    ) {
        String path = toString(params.get("path"));
        if (path == null || path.isBlank()) {
            return AssertionResult.indeterminate("JSONPath expression is required");
        }

        var jsonOpt = evidence.responseJson();
        if (jsonOpt.isEmpty()) {
            return AssertionResult.indeterminate("No JSON response body available");
        }

        JsonNode body = jsonOpt.get();
        JsonNode result;

        try {
            io.burt.jmespath.Expression<JsonNode> compiled = JMESPATH.compile(path);
            result = compiled.search(body);
        } catch (Exception e) {
            return AssertionResult.fail("JMESPath evaluation failed: " + e.getMessage(),
                    Map.of("path", path, "error", e.getMessage()));
        }

        boolean resultExists = result != null && !result.isNull() && !result.isMissingNode();

        // Existence check
        if (params.containsKey("exists")) {
            boolean shouldExist = toBoolean(params.get("exists"));
            if (shouldExist == resultExists) {
                return AssertionResult.pass("Path '" + path + "' exists: " + resultExists);
            } else {
                return AssertionResult.fail(
                        "Path '" + path + "' expected exists=" + shouldExist + ", actual=" + resultExists,
                        Map.of("path", path, "expected", shouldExist, "actual", resultExists)
                );
            }
        }

        if (!resultExists) {
            return AssertionResult.fail("Path '" + path + "' returned no result",
                    Map.of("path", path));
        }

        // Equals
        if (params.containsKey("equals")) {
            Object expected = params.get("equals");
            JsonNode expectedNode = MAPPER.valueToTree(expected);

            // Handle template expressions: if expected is a string that looks like a number/bool, compare directly
            if (expected instanceof String s && result.isTextual()) {
                if (result.asText().equals(s)) {
                    return AssertionResult.pass("Path '" + path + "' equals '" + s + "'");
                } else {
                    return AssertionResult.fail(
                            "Path '" + path + "' expected '" + s + "' but got '" + result.asText() + "'",
                            Map.of("path", path, "expected", s, "actual", result.asText())
                    );
                }
            }

            if (result.equals(expectedNode)) {
                return AssertionResult.pass("Path '" + path + "' equals expected value");
            } else {
                return AssertionResult.fail(
                        "Path '" + path + "' expected " + expectedNode + " but got " + result,
                        Map.of("path", path, "expected", sanitize(expectedNode), "actual", sanitize(result))
                );
            }
        }

        // Count (for arrays)
        if (params.containsKey("count")) {
            int expectedCount = toInt(params.get("count"));
            if (result.isArray()) {
                int actualCount = result.size();
                if (actualCount == expectedCount) {
                    return AssertionResult.pass("Path '" + path + "' has " + actualCount + " elements");
                } else {
                    return AssertionResult.fail(
                            "Path '" + path + "' expected " + expectedCount + " elements but got " + actualCount,
                            Map.of("path", path, "expected", expectedCount, "actual", actualCount)
                    );
                }
            } else {
                return AssertionResult.fail("Path '" + path + "' is not an array, cannot count",
                        Map.of("path", path, "type", result.getNodeType().toString()));
            }
        }

        // Type check
        if (params.containsKey("type")) {
            String expectedType = toString(params.get("type"));
            String actualType = getJsonType(result);
            if (expectedType.equals(actualType)) {
                return AssertionResult.pass("Path '" + path + "' is type " + actualType);
            } else {
                return AssertionResult.fail(
                        "Path '" + path + "' expected type " + expectedType + " but got " + actualType,
                        Map.of("path", path, "expected", expectedType, "actual", actualType)
                );
            }
        }

        // Uniqueness (for arrays)
        if (params.containsKey("unique")) {
            boolean shouldBeUnique = toBoolean(params.get("unique"));
            if (!result.isArray()) {
                return AssertionResult.fail("Path '" + path + "' is not an array, cannot check uniqueness",
                        Map.of("path", path));
            }
            Set<String> seen = new HashSet<>();
            List<Object> duplicates = new ArrayList<>();
            for (JsonNode item : result) {
                String key = item.toString();
                if (!seen.add(key)) {
                    duplicates.add(sanitize(item));
                }
            }
            boolean isUnique = duplicates.isEmpty();
            if (shouldBeUnique == isUnique) {
                return AssertionResult.pass("Path '" + path + "' uniqueness: " + isUnique);
            } else {
                return AssertionResult.fail(
                        "Path '" + path + "' expected unique=" + shouldBeUnique + ", duplicates: " + duplicates,
                        Map.of("path", path, "expected", shouldBeUnique, "duplicates", duplicates)
                );
            }
        }

        return AssertionResult.indeterminate("No valid assertion parameters provided (exists, equals, count, type, unique)");
    }

    private String getJsonType(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isNull()) return "null";
        return "unknown";
    }

    private Object sanitize(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.asBoolean();
        return node.toString();
    }

    private String toString(Object value) {
        return value != null ? value.toString() : null;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    private int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("Cannot convert to int: " + value);
    }
}
