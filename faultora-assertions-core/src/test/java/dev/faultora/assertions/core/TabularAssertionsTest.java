package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.result.AssertionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the four tabular assertions conclude, and what they refuse to. */
class TabularAssertionsTest {

    private static final AssertionContext CONTEXT =
            new AssertionContext("read-ledger", Map.of());

    private final RowCountAssertionProvider count = new RowCountAssertionProvider();
    private final RowBalanceAssertionProvider balance = new RowBalanceAssertionProvider();
    private final RowUniqueAssertionProvider unique = new RowUniqueAssertionProvider();
    private final RowValueAssertionProvider value = new RowValueAssertionProvider();

    /** Two entries of a correct double-entry booking. */
    private static ObservedTableEvidence booking() {
        return ObservedTableEvidence.observing("payment_id", "account", "amount")
                .row("pay-1", "receivable", 2500)
                .row("pay-1", "revenue", -2500);
    }

    @Test
    void aLedgerThatBalancesSumsToZero() {
        // The reason tabular assertions exist: a ledger whose entries do not
        // sum to zero has lost or invented money, and no single request says so.
        AssertionResult result = balance.evaluate("row-balance",
                Map.of("column", "amount", "equals", 0), booking(), CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void aLedgerThatDoesNotBalanceSaysByHowMuch() {
        var lopsided = ObservedTableEvidence.observing("account", "amount")
                .row("receivable", 2500)
                .row("revenue", -2400);

        AssertionResult result = balance.evaluate("row-balance",
                Map.of("column", "amount", "equals", 0), lopsided, CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        assertThat(result.message()).contains("sums to 100");
    }

    @Test
    void amountsCompareAsDecimalsWhateverTheDriverReturned() {
        var mixed = ObservedTableEvidence.observing("amount")
                .row(new BigDecimal("2500.00"))
                .row("-2500");

        assertThat(balance.evaluate("row-balance",
                Map.of("column", "amount", "equals", "0.00"), mixed, CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void aCountAgainstATruncatedResultIsIndeterminate() {
        // The number there is the row limit, not the answer. Reading it as the
        // answer is the mistake this refusal exists to prevent.
        AssertionResult result = count.evaluate("row-count",
                Map.of("equals", 2), booking().truncated(), CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        assertThat(result.message()).contains("row limit");
    }

    @Test
    void aCountHoldsWhenTheResultIsWhole() {
        assertThat(count.evaluate("row-count", Map.of("equals", 2), booking(), CONTEXT)
                .outcome()).isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(count.evaluate("row-count", Map.of("min", 1), booking(), CONTEXT)
                .outcome()).isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(count.evaluate("row-count", Map.of("equals", 1), booking(), CONTEXT)
                .outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void aStepThatObservedNoRowsIsIndeterminateNotEmpty() {
        assertThat(count.evaluate("row-count", Map.of("equals", 0),
                ObservedTableEvidence.ofSomethingElse(), CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    @Test
    void aPaymentBookedTwiceFailsUniqueness() {
        var duplicated = ObservedTableEvidence.observing("payment_id")
                .row("pay-1").row("pay-1");

        AssertionResult result = unique.evaluate("row-unique",
                Map.of("column", "payment_id"), duplicated, CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        assertThat(result.message()).contains("Rows 0 and 1", "pay-1");
    }

    @Test
    void aValueIsComparedAcrossComponents() {
        // What the API accepted, read back out of the ledger. The parameter is
        // an expression in a scenario; here it is what one resolved to.
        assertThat(value.evaluate("row-value",
                Map.of("column", "amount", "equals", "2500", "row", 0),
                booking(), CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void everyRowMustMatchWhenNoRowIsNamed() {
        var sameEverywhere = ObservedTableEvidence.observing("payment_id")
                .row("pay-1").row("pay-1");

        assertThat(value.evaluate("row-value",
                Map.of("column", "payment_id", "equals", "pay-1"),
                sameEverywhere, CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(value.evaluate("row-value",
                Map.of("column", "payment_id", "equals", "pay-1"),
                booking(), CONTEXT).outcome())
                .as("the booking's second row is a different account")
                .isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void aColumnTheObservationDidNotReturnIsNamed() {
        AssertionResult result = balance.evaluate("row-balance",
                Map.of("column", "total", "equals", 0), booking(), CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        assertThat(result.message()).contains("no column 'total'", "payment_id");
    }

    @Test
    void anAssertionMissingItsOwnParametersSaysSoRatherThanPassing() {
        assertThat(count.evaluate("row-count", Map.of(), booking(), CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        assertThat(balance.evaluate("row-balance", Map.of("column", "amount"),
                booking(), CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        assertThat(unique.evaluate("row-unique", Map.of(), booking(), CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        assertThat(value.evaluate("row-value", Map.of("column", "amount"),
                booking(), CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    @Test
    void aValueCheckOnOneRowSurvivesATruncatedResult() {
        // Reading row 0 of a cut result is still reading row 0; only a claim
        // about all the rows depends on having them all.
        assertThat(value.evaluate("row-value",
                Map.of("column", "amount", "equals", "2500", "row", 0),
                booking().truncated(), CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(value.evaluate("row-value",
                Map.of("column", "payment_id", "equals", "pay-1"),
                booking().truncated(), CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }
}
