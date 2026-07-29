package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.evidence.MessageEvidence;
import dev.faultora.spi.result.AssertionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * That no two observed messages carry the same value.
 * <p>
 * The value is usually a business identifier, which is what makes this the
 * assertion for duplicate delivery: an at-least-once broker may deliver a
 * command twice, and a correct consumer still emits one event per payment. A
 * scenario publishes twice on purpose and asserts uniqueness on the payment id.
 * <pre>
 * assertions:
 *   - type: event-unique
 *     by: payload:paymentId
 * </pre>
 * The locator is {@code key}, {@code header:<name>}, {@code payload:<path>},
 * or a bare path, which means a payload field.
 */
public class EventUniqueAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "event-unique";
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
                    "This step observed no messages, so there is nothing to compare");
        }
        String locator = ObservedMessages.parameter(params, "by");
        if (locator == null) {
            return AssertionResult.indeterminate(
                    "event-unique needs 'by' to name the value that must be unique");
        }

        List<MessageEvidence> messages = ObservedMessages.of(evidence);
        Map<String, Integer> firstSeenAt = new LinkedHashMap<>();
        for (int index = 0; index < messages.size(); index++) {
            MessageEvidence message = messages.get(index);
            if (!ObservedMessages.isReadable(message, locator)) {
                return AssertionResult.indeterminate(
                        "The message payload was not captured, so '" + locator
                                + "' cannot be read");
            }
            String value = ObservedMessages.value(message, locator);
            if (value == null) {
                return AssertionResult.fail(
                        "Message at offset " + message.offset() + " carries no '"
                                + locator + "', so uniqueness cannot hold",
                        Map.of("offset", message.offset()));
            }
            Integer earlier = firstSeenAt.putIfAbsent(value, index);
            if (earlier != null) {
                return AssertionResult.fail(
                        "Two messages carry the same " + locator + " '" + value
                                + "': offsets " + messages.get(earlier).offset()
                                + " and " + message.offset(),
                        Map.of("duplicate", value,
                                "offsets", List.of(
                                        messages.get(earlier).offset(), message.offset())));
            }
        }
        return AssertionResult.pass(
                messages.size() + " message" + (messages.size() == 1 ? "" : "s")
                        + " with distinct " + locator);
    }
}
