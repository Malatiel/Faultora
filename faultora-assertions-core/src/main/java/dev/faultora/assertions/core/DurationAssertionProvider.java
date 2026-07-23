package dev.faultora.assertions.core;

import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.result.AssertionResult;

import java.util.Map;

/**
 * Asserts response duration using monotonic timing.
 * Supports: max (upper bound in ms), min (lower bound in ms).
 */
public class DurationAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "duration";
    }

    @Override
    public AssertionResult evaluate(
            String assertionType,
            Map<String, Object> params,
            EvidenceView evidence,
            AssertionContext context
    ) {
        long actualMs = evidence.durationMs();

        // Range check
        if (params.containsKey("min") && params.containsKey("max")) {
            long minMs = toLong(params.get("min"));
            long maxMs = toLong(params.get("max"));
            if (actualMs >= minMs && actualMs <= maxMs) {
                return AssertionResult.pass("Duration " + actualMs + "ms is in range [" + minMs + ", " + maxMs + "]");
            } else {
                return AssertionResult.fail(
                        "Duration " + actualMs + "ms is outside range [" + minMs + ", " + maxMs + "]",
                        Map.of("min", minMs, "max", maxMs, "actual", actualMs)
                );
            }
        }

        // Max duration check
        if (params.containsKey("max")) {
            long maxMs = toLong(params.get("max"));
            if (actualMs <= maxMs) {
                return AssertionResult.pass("Duration " + actualMs + "ms is within limit of " + maxMs + "ms");
            } else {
                return AssertionResult.fail(
                        "Duration " + actualMs + "ms exceeds limit of " + maxMs + "ms",
                        Map.of("max", maxMs, "actual", actualMs)
                );
            }
        }

        // Min duration check
        if (params.containsKey("min")) {
            long minMs = toLong(params.get("min"));
            if (actualMs >= minMs) {
                return AssertionResult.pass("Duration " + actualMs + "ms is above minimum of " + minMs + "ms");
            } else {
                return AssertionResult.fail(
                        "Duration " + actualMs + "ms is below minimum of " + minMs + "ms",
                        Map.of("min", minMs, "actual", actualMs)
                );
            }
        }

        return AssertionResult.indeterminate("No valid assertion parameters provided (max, min)");
    }

    private long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) return Long.parseLong(s);
        throw new IllegalArgumentException("Cannot convert to long: " + value);
    }
}
