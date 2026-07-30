package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.evidence.MessageEvidence;
import dev.faultora.spi.result.AssertionResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * That every observed message belongs to the same exchange.
 * <p>
 * Correlation continuity is what makes a cross-component claim mean anything:
 * an event that appears after a command is only evidence of that command if it
 * carries the same correlation value. Without this, a scenario that observes
 * "an event arrived" is really observing "the system was busy".
 * <pre>
 * assertions:
 *   - type: event-correlation
 *     by: header:correlation-id
 *     equals: "{{ inputs.paymentId }}"
 * </pre>
 * With {@code equals}, every message must carry that value. Without it, the
 * messages must merely agree with each other — which is the check to use when
 * the value was generated during the run and the scenario never named it.
 */
public class EventCorrelationAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "event-correlation";
    }

    @Override
    public AssertionResult evaluate(
            String assertionType,
            Map<String, Object> params,
            EvidenceView evidence,
            AssertionContext context
    ) {
        if (!ObservedMessages.isObservation(evidence)) {
            return AssertionResult.indeterminate(
                    "This step observed no messages, so there is no correlation to follow");
        }
        String locator = ObservedMessages.parameter(params, "by");
        if (locator == null) {
            return AssertionResult.indeterminate(
                    "event-correlation needs 'by' to name where the correlation value lives");
        }
        String expected = ObservedMessages.parameter(params, "equals");

        List<MessageEvidence> messages = ObservedMessages.of(evidence);
        if (messages.isEmpty()) {
            // Nothing arrived. Whether that is a failure is event-count's
            // question, and answering it twice would report one problem twice.
            return AssertionResult.indeterminate(
                    "No messages were observed, so there is no correlation to follow");
        }

        Set<String> values = new LinkedHashSet<>();
        for (MessageEvidence message : messages) {
            if (!ObservedMessages.isReadable(message, locator)) {
                return AssertionResult.indeterminate(ObservedMessages.unreadable(locator));
            }
            String value = ObservedMessages.value(message, locator);
            if (value == null) {
                return AssertionResult.fail(
                        "Message at offset " + message.offset() + " carries no '"
                                + locator + "'",
                        Map.of("offset", message.offset()));
            }
            if (expected != null && !expected.equals(value)) {
                return AssertionResult.fail(
                        "Message at offset " + message.offset() + " has " + locator
                                + " '" + value + "', expected '" + expected + "'",
                        Map.of("expected", expected, "actual", value));
            }
            values.add(value);
        }

        if (values.size() > 1) {
            return AssertionResult.fail(
                    "Messages disagree on " + locator + ": " + values,
                    Map.of("values", List.copyOf(values)));
        }
        return AssertionResult.pass(
                messages.size() + " message" + (messages.size() == 1 ? " carries " : "s share ")
                        + locator + " '" + values.iterator().next() + "'");
    }
}
