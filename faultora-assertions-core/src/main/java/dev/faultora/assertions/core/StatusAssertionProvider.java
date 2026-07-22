package dev.faultora.assertions.core;

import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.result.AssertionResult;

import java.util.Map;

/**
 * Asserts HTTP status codes.
 * Supports: expected (exact match), range (min/max), documented (set of expected codes).
 */
public class StatusAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "status";
    }

    @Override
    public AssertionResult evaluate(
            String assertionType,
            Map<String, Object> params,
            EvidenceView evidence,
            AssertionContext context
    ) {
        var statusCode = evidence.statusCode();
        if (statusCode.isEmpty()) {
            return AssertionResult.indeterminate("No status code available in evidence");
        }

        int actual = statusCode.get();

        // Exact match
        if (params.containsKey("expected")) {
            int expected = toInt(params.get("expected"));
            if (actual == expected) {
                return AssertionResult.pass("Status " + actual + " matches expected " + expected);
            } else {
                return AssertionResult.fail(
                        "Expected status " + expected + " but got " + actual,
                        Map.of("expected", expected, "actual", actual)
                );
            }
        }

        // Range check
        if (params.containsKey("min") || params.containsKey("max")) {
            int min = params.containsKey("min") ? toInt(params.get("min")) : 0;
            int max = params.containsKey("max") ? toInt(params.get("max")) : 999;
            if (actual >= min && actual <= max) {
                return AssertionResult.pass("Status " + actual + " is in range [" + min + ", " + max + "]");
            } else {
                return AssertionResult.fail(
                        "Status " + actual + " is outside range [" + min + ", " + max + "]",
                        Map.of("min", min, "max", max, "actual", actual)
                );
            }
        }

        // Documented status (set of expected codes)
        if (params.containsKey("documented")) {
            Object documented = params.get("documented");
            if (documented instanceof java.util.List<?> codes) {
                boolean found = codes.stream()
                        .anyMatch(c -> toInt(c) == actual);
                if (found) {
                    return AssertionResult.pass("Status " + actual + " is documented");
                } else {
                    return AssertionResult.fail(
                            "Status " + actual + " is not in documented codes: " + codes,
                            Map.of("documented", codes, "actual", actual)
                    );
                }
            }
        }

        return AssertionResult.indeterminate("No valid assertion parameters provided (expected, min/max, or documented)");
    }

    private int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("Cannot convert to int: " + value);
    }
}
