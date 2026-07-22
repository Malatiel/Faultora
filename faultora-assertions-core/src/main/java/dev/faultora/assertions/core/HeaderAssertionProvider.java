package dev.faultora.assertions.core;

import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.result.AssertionResult;

import java.util.List;
import java.util.Map;

/**
 * Asserts response headers.
 * Supports: exists (header present), equals (exact value), contains (substring), count (number of values).
 */
public class HeaderAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "header";
    }

    @Override
    public AssertionResult evaluate(
            String assertionType,
            Map<String, Object> params,
            EvidenceView evidence,
            AssertionContext context
    ) {
        String headerName = toString(params.get("name"));
        if (headerName == null || headerName.isBlank()) {
            return AssertionResult.indeterminate("Header name is required");
        }

        Map<String, List<String>> headers = evidence.responseHeaders();
        String lowerName = headerName.toLowerCase();
        List<String> values = headers.get(lowerName);

        // Existence check
        if (params.containsKey("exists")) {
            boolean shouldExist = toBoolean(params.get("exists"));
            boolean doesExist = values != null && !values.isEmpty();
            if (shouldExist == doesExist) {
                return AssertionResult.pass("Header '" + headerName + "' existence: " + doesExist);
            } else {
                return AssertionResult.fail(
                        "Header '" + headerName + "' expected existence: " + shouldExist + ", actual: " + doesExist,
                        Map.of("header", headerName, "expected", shouldExist, "actual", doesExist)
                );
            }
        }

        if (values == null || values.isEmpty()) {
            return AssertionResult.fail("Header '" + headerName + "' not found in response",
                    Map.of("header", headerName));
        }

        String actualValue = values.get(0);

        // Exact equality
        if (params.containsKey("equals")) {
            String expected = toString(params.get("equals"));
            if (actualValue.equals(expected)) {
                return AssertionResult.pass("Header '" + headerName + "' equals '" + expected + "'");
            } else {
                return AssertionResult.fail(
                        "Header '" + headerName + "' expected '" + expected + "' but got '" + actualValue + "'",
                        Map.of("header", headerName, "expected", expected, "actual", actualValue)
                );
            }
        }

        // Contains
        if (params.containsKey("contains")) {
            String substring = toString(params.get("contains"));
            if (actualValue.contains(substring)) {
                return AssertionResult.pass("Header '" + headerName + "' contains '" + substring + "'");
            } else {
                return AssertionResult.fail(
                        "Header '" + headerName + "' does not contain '" + substring + "', value: '" + actualValue + "'",
                        Map.of("header", headerName, "contains", substring, "actual", actualValue)
                );
            }
        }

        // Count
        if (params.containsKey("count")) {
            int expectedCount = toInt(params.get("count"));
            int actualCount = values.size();
            if (actualCount == expectedCount) {
                return AssertionResult.pass("Header '" + headerName + "' has " + actualCount + " values");
            } else {
                return AssertionResult.fail(
                        "Header '" + headerName + "' expected " + expectedCount + " values but got " + actualCount,
                        Map.of("header", headerName, "expected", expectedCount, "actual", actualCount)
                );
            }
        }

        return AssertionResult.indeterminate("No valid assertion parameters provided (exists, equals, contains, count)");
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
