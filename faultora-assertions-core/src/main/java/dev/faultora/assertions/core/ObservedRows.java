package dev.faultora.assertions.core;

import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.evidence.TableEvidence;
import dev.faultora.spi.result.AssertionResult;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Reading the rows a tabular assertion is about.
 * <p>
 * Every one of them needs the same two things: the rows a step observed, and a
 * refusal when the row limit cut the result. The second is what keeps these
 * assertions honest — a count taken from a truncated result is a count of the
 * limit, and "three rows" against a truncated ten must never read as satisfied.
 */
final class ObservedRows {

    private ObservedRows() {
    }

    /** The rows the step observed, or null when it observed none. */
    static TableEvidence of(EvidenceView evidence) {
        return TableEvidence.observedIn(evidence.protocolEvidence());
    }

    /**
     * Why these rows cannot be reasoned about, or null when they can.
     * <p>
     * A step that made no observation is indeterminate rather than failed: an
     * HTTP step has no rows, and reading that as "no rows, as expected" would
     * let an assertion pass without being evaluated.
     */
    static AssertionResult refusal(TableEvidence rows, boolean countSensitive) {
        if (rows == null) {
            return AssertionResult.indeterminate(
                    "This step observed no rows, so there is nothing to check");
        }
        if (countSensitive && rows.truncated()) {
            return AssertionResult.indeterminate(
                    "The row limit cut this result, so what is here is the limit rather "
                            + "than the answer. Raise the evidence policy's row limit or "
                            + "narrow the observation");
        }
        return null;
    }

    /** A column the rows must have, or an explanation of why they do not. */
    static AssertionResult missingColumn(TableEvidence rows, String column) {
        if (column == null || column.isBlank()) {
            return AssertionResult.indeterminate(
                    "This assertion needs 'column' to name the column it reads");
        }
        if (!rows.columns().contains(column)) {
            return AssertionResult.fail(
                    "The observation returned no column '" + column + "'; it returned "
                            + rows.columns(), Map.of("columns", rows.columns()));
        }
        return null;
    }

    /**
     * A value as a number, or null when it is not one.
     * <p>
     * A database returns its own numeric types, and a scenario writes decimals
     * as text. Comparing them as {@link BigDecimal} means 2500 and 2500.00 are
     * the same amount, which is what a ledger means by them.
     */
    static BigDecimal number(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal decimal -> decimal;
            case Number number -> new BigDecimal(number.toString());
            case CharSequence text -> parse(text.toString());
            default -> parse(value.toString());
        };
    }

    private static BigDecimal parse(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
