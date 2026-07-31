package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.evidence.TableEvidence;
import dev.faultora.spi.result.AssertionResult;

import java.math.BigDecimal;
import java.util.Map;

/**
 * That a column of numbers sums to what it should.
 * <pre>
 * assertions:
 *   - type: row-balance
 *     targetStep: read-ledger
 *     column: amount
 *     equals: 0
 * </pre>
 * This is the double-entry check, and it is the reason tabular assertions exist
 * at all: a ledger whose entries sum to anything but zero has lost or invented
 * money, and no single request can tell you that.
 * <p>
 * Values are compared as decimals, so 2500 and 2500.00 are the same amount —
 * which is what a ledger means by them, whatever the driver returned.
 */
public class RowBalanceAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "row-balance";
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
        BigDecimal expected = ObservedRows.number(
                ObservedMessages.parameter(params, "equals"));
        if (expected == null) {
            return AssertionResult.indeterminate(
                    "row-balance needs 'equals' to say what the column should sum to");
        }

        BigDecimal total = BigDecimal.ZERO;
        for (Object value : rows.column(column)) {
            BigDecimal amount = ObservedRows.number(value);
            if (amount == null) {
                return AssertionResult.indeterminate(
                        "Column '" + column + "' holds '" + value
                                + "', which is not a number to add up");
            }
            total = total.add(amount);
        }

        if (total.compareTo(expected) != 0) {
            return AssertionResult.fail(
                    "Column '" + column + "' sums to " + total.toPlainString()
                            + ", expected " + expected.toPlainString(),
                    Map.of("total", total.toPlainString(), "rows", rows.rowCount()));
        }
        return AssertionResult.pass(
                "Column '" + column + "' over " + rows.rowCount() + " rows sums to "
                        + expected.toPlainString());
    }
}
