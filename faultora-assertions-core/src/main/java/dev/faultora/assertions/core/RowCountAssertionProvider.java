package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.evidence.TableEvidence;
import dev.faultora.spi.result.AssertionResult;

import java.util.Map;

/**
 * How many rows an observation returned.
 * <pre>
 * assertions:
 *   - type: row-count
 *     targetStep: read-ledger
 *     equals: 2
 * </pre>
 * Needs one of {@code equals}, {@code min}, or {@code max}. A result the row
 * limit cut is indeterminate rather than counted: the number there would be the
 * limit, not the answer.
 */
public class RowCountAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "row-count";
    }

    @Override
    public AssertionResult evaluate(
            String assertionType,
            Map<String, Object> params,
            EvidenceView evidence,
            AssertionContext context
    ) {
        TableEvidence rows = ObservedRows.of(evidence);
        AssertionResult refusal = ObservedRows.refusal(rows, true);
        if (refusal != null) {
            return refusal;
        }
        Integer equals = ObservedMessages.number(params, "equals");
        Integer min = ObservedMessages.number(params, "min");
        Integer max = ObservedMessages.number(params, "max");
        if (equals == null && min == null && max == null) {
            return AssertionResult.indeterminate(
                    "row-count needs one of equals, min or max");
        }

        int observed = rows.rowCount();
        Map<String, Object> details = Map.of("rows", observed);
        if (equals != null && observed != equals) {
            return AssertionResult.fail(
                    "Expected exactly " + equals + " row" + plural(equals)
                            + ", observed " + observed, details);
        }
        if (min != null && observed < min) {
            return AssertionResult.fail(
                    "Expected at least " + min + " row" + plural(min)
                            + ", observed " + observed, details);
        }
        if (max != null && observed > max) {
            return AssertionResult.fail(
                    "Expected at most " + max + " row" + plural(max)
                            + ", observed " + observed, details);
        }
        return AssertionResult.pass("Observed " + observed + " row" + plural(observed));
    }

    private static String plural(int count) {
        return count == 1 ? "" : "s";
    }
}
