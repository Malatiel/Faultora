package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.result.AssertionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the four event assertions conclude, and what they refuse to conclude. */
class EventAssertionsTest {

    private static final AssertionContext CONTEXT =
            new AssertionContext("observe-events", Map.of());

    private final EventCountAssertionProvider count = new EventCountAssertionProvider();
    private final EventUniqueAssertionProvider unique = new EventUniqueAssertionProvider();
    private final EventCorrelationAssertionProvider correlation =
            new EventCorrelationAssertionProvider();
    private final EventSequenceAssertionProvider sequence =
            new EventSequenceAssertionProvider();

    @Test
    void aCountHoldsWhenTheRightNumberArrived() {
        var evidence = ObservedEvidence.observing()
                .message("pay-1", "{\"status\":\"settled\"}");

        assertThat(count.evaluate("event-count", Map.of("equals", 1), evidence, CONTEXT)
                .outcome()).isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(count.evaluate("event-count", Map.of("min", 1), evidence, CONTEXT)
                .outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void aDuplicatedEventFailsTheCountThatExpectedOne() {
        // The gate this release is measured by: publishing twice must not
        // produce two business effects.
        var evidence = ObservedEvidence.observing()
                .message("pay-1", "{\"paymentId\":\"pay-1\"}")
                .message("pay-1", "{\"paymentId\":\"pay-1\"}");

        AssertionResult result =
                count.evaluate("event-count", Map.of("equals", 1), evidence, CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        assertThat(result.message()).contains("exactly 1", "observed 2");
    }

    @Test
    void aCountOnAStepThatObservedNothingIsIndeterminateNotPassing() {
        // An HTTP step has no messages. Reading that as "zero messages, as
        // expected" would let an assertion pass without being evaluated.
        AssertionResult result = count.evaluate(
                "event-count", Map.of("equals", 0),
                ObservedEvidence.ofSomethingElse(), CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    @Test
    void anObservationThatFoundNothingCanStillFailACount() {
        AssertionResult result = count.evaluate(
                "event-count", Map.of("min", 1),
                ObservedEvidence.observing(), CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void uniquenessFailsWhenTwoMessagesShareTheirIdentifier() {
        var evidence = ObservedEvidence.observing()
                .message("a", "{\"paymentId\":\"pay-1\"}")
                .message("b", "{\"paymentId\":\"pay-1\"}");

        AssertionResult result = unique.evaluate(
                "event-unique", Map.of("by", "payload:paymentId"), evidence, CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        assertThat(result.message()).contains("pay-1", "offsets 0 and 1");
    }

    @Test
    void uniquenessReadsKeysAndHeadersAsWellAsPayloads() {
        var evidence = ObservedEvidence.observing()
                .message("pay-1", "{}", Map.of("correlation-id", "c-1"))
                .message("pay-2", "{}", Map.of("correlation-id", "c-2"));

        assertThat(unique.evaluate("event-unique", Map.of("by", "key"), evidence, CONTEXT)
                .outcome()).isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(unique.evaluate("event-unique",
                Map.of("by", "header:correlation-id"), evidence, CONTEXT)
                .outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void aWithheldPayloadMakesTheCheckIndeterminateRatherThanFailed() {
        // The evidence policy decides what is stored. An assertion that cannot
        // see the value it checks has not found a defect.
        var evidence = ObservedEvidence.observing().withheldMessage("pay-1");

        assertThat(unique.evaluate("event-unique",
                Map.of("by", "payload:paymentId"), evidence, CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        assertThat(correlation.evaluate("event-correlation",
                Map.of("by", "payload:paymentId"), evidence, CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    @Test
    void correlationHoldsWhenEveryMessageCarriesTheExpectedValue() {
        var evidence = ObservedEvidence.observing()
                .message("a", "{}", Map.of("correlation-id", "c-1"))
                .message("b", "{}", Map.of("correlation-id", "c-1"));

        AssertionResult result = correlation.evaluate("event-correlation",
                Map.of("by", "header:correlation-id", "equals", "c-1"), evidence, CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void correlationFailsWhenTheMessagesBelongToDifferentExchanges() {
        var evidence = ObservedEvidence.observing()
                .message("a", "{}", Map.of("correlation-id", "c-1"))
                .message("b", "{}", Map.of("correlation-id", "c-2"));

        AssertionResult result = correlation.evaluate("event-correlation",
                Map.of("by", "header:correlation-id"), evidence, CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        assertThat(result.message()).contains("disagree");
    }

    @Test
    void anOrderedSequenceAllowsEventsInBetween() {
        // A workflow that emits something extra and correct must not fail an
        // assertion about the two events the scenario is actually asserting.
        var evidence = ObservedEvidence.observing()
                .message("a", "{\"status\":\"accepted\"}")
                .message("b", "{\"status\":\"audited\"}")
                .message("c", "{\"status\":\"settled\"}");

        AssertionResult result = sequence.evaluate("event-sequence",
                Map.of("of", List.of(
                        Map.of("payload:status", "accepted"),
                        Map.of("payload:status", "settled"))),
                evidence, CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void anOrderedSequenceFailsWhenTheOrderIsWrong() {
        var evidence = ObservedEvidence.observing()
                .message("a", "{\"status\":\"settled\"}")
                .message("b", "{\"status\":\"accepted\"}");

        AssertionResult ordered = sequence.evaluate("event-sequence",
                Map.of("of", List.of(
                        Map.of("payload:status", "accepted"),
                        Map.of("payload:status", "settled"))),
                evidence, CONTEXT);
        AssertionResult unordered = sequence.evaluate("event-sequence",
                Map.of("ordered", false, "of", List.of(
                        Map.of("payload:status", "accepted"),
                        Map.of("payload:status", "settled"))),
                evidence, CONTEXT);

        assertThat(ordered.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        assertThat(unordered.outcome())
                .as("order is a property of the workflow, so it can be waived")
                .isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void anUnorderedSequenceDoesNotLetOneMessageAnswerForTwo() {
        var evidence = ObservedEvidence.observing()
                .message("a", "{\"status\":\"settled\"}");

        AssertionResult result = sequence.evaluate("event-sequence",
                Map.of("ordered", false, "of", List.of(
                        Map.of("payload:status", "settled"),
                        Map.of("payload:status", "settled"))),
                evidence, CONTEXT);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void anAssertionMissingItsOwnParametersSaysSoRatherThanPassing() {
        var evidence = ObservedEvidence.observing().message("a", "{}");

        assertThat(count.evaluate("event-count", Map.of(), evidence, CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        assertThat(unique.evaluate("event-unique", Map.of(), evidence, CONTEXT).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        assertThat(correlation.evaluate("event-correlation", Map.of(), evidence, CONTEXT)
                .outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        assertThat(sequence.evaluate("event-sequence", Map.of(), evidence, CONTEXT)
                .outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }
}
