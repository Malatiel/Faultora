package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.evidence.TableEvidence;
import dev.faultora.spi.result.AssertionResult;

import java.util.LinkedHashMap;
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
        AssertionResult missing = ObservedRows.missingColumn(rows, column);
        if (missing != null) {
            return missing;
        }

        Map<Object, Integer> firstSeenAt = new LinkedHashMap<>();
        java.util.List<Object> values = rows.column(column);
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            Integer earlier = firstSeenAt.putIfAbsent(String.valueOf(value), index);
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
}
