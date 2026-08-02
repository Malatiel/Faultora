package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.burt.jmespath.JmesPath;
import io.burt.jmespath.jackson.JacksonRuntime;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.result.AssertionResult;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Asserts values in the JSON response body.
 * <p>
 * <b>The {@code path} is a JMESPath expression</b>, not JSONPath, and has been
 * since this was written. The type is named {@code jsonpath} and stays named
 * that: renaming it means either two names frozen in the scenario contract at
 * 1.0 or breaking every scenario that exists, and a wart with a signpost is
 * cheaper than either. ADR-019 records the decision.
 * <p>
 * Supports exactly: {@code exists}, {@code equals}, {@code count},
 * {@code type}, {@code unique}, and {@code matches}. The list used to name
 * {@code length} as well, which was never implemented — JMESPath's own
 * {@code length()} in the path is how a scenario asks for that.
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
            JsonNode expectedNode = MAPPER.valueToTree(params.get("equals"));

            if (sameValue(result, expectedNode)) {
                return AssertionResult.pass(
                        "Path '" + path + "' equals " + display(expectedNode));
            }
            return AssertionResult.fail(
                    "Path '" + path + "' expected " + display(expectedNode)
                            + " but got " + display(result),
                    Map.of("path", path, "expected", sanitize(expectedNode),
                            "actual", sanitize(result))
            );
        }

        // Regex
        if (params.containsKey("matches")) {
            String pattern = toString(params.get("matches"));
            Pattern compiled;
            try {
                compiled = Pattern.compile(pattern);
            } catch (PatternSyntaxException unusable) {
                // A pattern that does not compile says nothing about the
                // response, so it is indeterminate rather than a failure.
                return AssertionResult.indeterminate(
                        "'" + pattern + "' is not a regular expression: "
                                + unusable.getDescription());
            }
            String actual = textOf(result);
            if (compiled.matcher(actual).find()) {
                return AssertionResult.pass(
                        "Path '" + path + "' matches /" + pattern + "/");
            }
            return AssertionResult.fail(
                    "Path '" + path + "' is '" + actual + "', which does not match /"
                            + pattern + "/",
                    Map.of("path", path, "pattern", pattern, "actual", actual));
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

        return AssertionResult.indeterminate(
                "No valid assertion parameters provided "
                        + "(exists, equals, matches, count, type, unique)");
    }

    /**
     * Whether two values are the same value.
     * <p>
     * Numbers compare as decimals, so 5 and 5.0 are one number and a
     * template-resolved {@code "2500"} is the amount the API returned rather
     * than a different kind of thing. That is the rule the tabular assertions
     * already apply, and an assertion language with two answers to "is this
     * equal" has one answer too many.
     * <p>
     * A number and a string that spells it are also equal here, because a
     * scenario writes {@code equals: "2500"} whenever the value came through a
     * template — and {@code type} is the assertion that distinguishes 5 from
     * "5" for anyone who needs the distinction.
     */
    private static boolean sameValue(JsonNode actual, JsonNode expected) {
        BigDecimal actualNumber = numberOf(actual);
        BigDecimal expectedNumber = numberOf(expected);
        if (actualNumber != null && expectedNumber != null) {
            return actualNumber.compareTo(expectedNumber) == 0;
        }
        if (actual.isTextual() || expected.isTextual()) {
            return textOf(actual).equals(textOf(expected));
        }
        return actual.equals(expected);
    }

    /** A node as a decimal, or null when it does not spell a number. */
    private static BigDecimal numberOf(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (!node.isTextual()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText().trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String textOf(JsonNode node) {
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String display(JsonNode node) {
        return node.isTextual() ? "'" + node.asText() + "'" : node.toString();
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
