package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.evidence.TableEvidence;
import dev.faultora.spi.result.AssertionResult;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * That no two rows share a value.
 * <pre>
 * assertions:
 *   - type: row-unique
 *     targetStep: read-ledger
 *     column: payment_id
 * </pre>
 * The database half of the duplicate-effect question: a command delivered twice
 * may produce two rows where the invariant says one, and a uniqueness check on
 * the business identifier is how a scenario says so.
 */
public class RowUniqueAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "row-unique";
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
        String column = ObservedMessages.parameter(params, "column");
        AssertionResult unreadable = ObservedRows.unreadableColumn(rows, column);
        if (unreadable != null) {
            return unreadable;
        }

        Map<String, Integer> firstSeenAt = new LinkedHashMap<>();
        List<Object> values = rows.column(column);
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value == null) {
                // SQL says a NULL equals nothing, not even another NULL, and a
                // unique index agrees: two rows missing a value are not a
                // duplicate of each other.
                continue;
            }
            Integer earlier = firstSeenAt.putIfAbsent(identity(value), index);
            if (earlier != null) {
                return AssertionResult.fail(
                        "Rows " + earlier + " and " + index + " share " + column
                                + " '" + value + "'",
                        Map.of("duplicate", String.valueOf(value)));
            }
        }
        return AssertionResult.pass(
                rows.rowCount() + " row" + (rows.rowCount() == 1 ? "" : "s")
                        + " with distinct " + column);
    }

    /**
     * What makes two values the same value here.
     * <p>
     * Numbers compare as decimals, so a driver returning 2500 and one
     * returning 2500.00 are one identifier rather than two — the same rule
     * {@code row-value} and {@code row-balance} apply, and a uniqueness check
     * that disagreed with them would call the same pair of rows distinct and
     * unequal at once.
     */
    private static String identity(Object value) {
        BigDecimal number = ObservedRows.number(value);
        return number == null
                ? "text:" + value
                : "number:" + number.stripTrailingZeros().toPlainString();
    }
}
