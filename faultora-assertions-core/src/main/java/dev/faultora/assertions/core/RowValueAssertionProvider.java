package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.evidence.TableEvidence;
import dev.faultora.spi.result.AssertionResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * That a column holds the value it should.
 * <pre>
 * assertions:
 *   - type: row-value
 *     targetStep: read-ledger
 *     column: amount
 *     equals: "{{steps.created.body.amount}}"
 * </pre>
 * With assertion parameters being expressions, this is the cross-component
 * comparison itself: the amount the API accepted, read back out of the ledger.
 * By default every row must match; {@code row: 0} checks one.
 * <p>
 * Numbers compare as decimals and everything else as text, so a driver
 * returning 2500 and a scenario writing "2500" agree, and a column that is not
 * a number is compared as what it is.
 */
public class RowValueAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "row-value";
    }

    @Override
    public AssertionResult evaluate(
            String assertionType,
            Map<String, Object> params,
            EvidenceView evidence,
            AssertionContext context
    ) {
        TableEvidence rows = ObservedRows.of(evidence);
        // A value check reads what is here rather than counting it, so a
        // truncated result is only a problem when every row must match.
        Integer row = ObservedMessages.number(params, "row");
        AssertionResult refusal = ObservedRows.refusal(rows, row == null);
        if (refusal != null) {
            return refusal;
        }
        String column = ObservedMessages.parameter(params, "column");
        AssertionResult unreadable = ObservedRows.unreadableColumn(rows, column);
        if (unreadable != null) {
            return unreadable;
        }
        String expected = ObservedMessages.parameter(params, "equals");
        if (expected == null) {
            return AssertionResult.indeterminate(
                    "row-value needs 'equals' to say what the column should hold");
        }

        List<Object> values = rows.column(column);
        if (row != null) {
            if (row < 0 || row >= values.size()) {
                return AssertionResult.fail(
                        "The observation returned " + values.size() + " rows, so there is "
                                + "no row " + row, Map.of("rows", values.size()));
            }
            return compare(values.get(row), expected, column, "row " + row);
        }
        if (values.isEmpty()) {
            return AssertionResult.fail(
                    "The observation returned no rows to check '" + column + "' in",
                    Map.of("rows", 0));
        }
        for (int index = 0; index < values.size(); index++) {
            AssertionResult result =
                    compare(values.get(index), expected, column, "row " + index);
            if (result.outcome() != AssertionResult.Outcome.PASS) {
                return result;
            }
        }
        return AssertionResult.pass(
                "Every one of the " + values.size() + " rows has " + column
                        + " '" + expected + "'");
    }

    private AssertionResult compare(
            Object actual, String expected, String column, String where) {
        BigDecimal actualNumber = ObservedRows.number(actual);
        BigDecimal expectedNumber = ObservedRows.number(expected);
        boolean equal = actualNumber != null && expectedNumber != null
                ? actualNumber.compareTo(expectedNumber) == 0
                : expected.equals(String.valueOf(actual));

        return equal
                ? AssertionResult.pass(column + " is '" + expected + "'")
                : AssertionResult.fail(
                        column + " at " + where + " is '" + actual + "', expected '"
                                + expected + "'",
                        Map.of("expected", expected, "actual", String.valueOf(actual)));
    }
}
