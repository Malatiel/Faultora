package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.evidence.MessageEvidence;
import dev.faultora.spi.result.AssertionResult;

import java.util.List;
import java.util.Map;

/**
 * How many of the messages a step observed there were.
 * <p>
 * This is the assertion most event scenarios end with, and it says more than
 * it looks like it does. {@code equals: 1} after a duplicate publish is the
 * statement that the target deduplicated. {@code min: 1} inside an
 * {@code eventually} block is "the event eventually appears" — which is why
 * this release adds no separate step type for waiting: the polling already
 * exists, and an appearance is a count that stops being zero.
 * <pre>
 * assertions:
 *   - type: event-count
 *     equals: 1
 * </pre>
 * Counts are of the messages the step's {@code match} selected, not of
 * everything in the topic. Without a selector on a shared topic the count is
 * whatever else happened to be there, which is a scenario problem rather than
 * an assertion one.
 */
public class EventCountAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "event-count";
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
                    "This step observed no messages, so there is nothing to count");
        }
        List<MessageEvidence> messages = ObservedMessages.of(evidence);
        int observed = messages.size();

        Integer equals = ObservedMessages.number(params, "equals");
        Integer min = ObservedMessages.number(params, "min");
        Integer max = ObservedMessages.number(params, "max");

        if (equals == null && min == null && max == null) {
            return AssertionResult.indeterminate(
                    "event-count needs one of equals, min or max");
        }
        Map<String, Object> details = Map.of("observed", observed);

        if (equals != null && observed != equals) {
            return AssertionResult.fail(
                    "Expected exactly " + equals + " message" + plural(equals)
                            + ", observed " + observed, details);
        }
        if (min != null && observed < min) {
            return AssertionResult.fail(
                    "Expected at least " + min + " message" + plural(min)
                            + ", observed " + observed, details);
        }
        if (max != null && observed > max) {
            return AssertionResult.fail(
                    "Expected at most " + max + " message" + plural(max)
                            + ", observed " + observed, details);
        }
        return AssertionResult.pass("Observed " + observed + " message" + plural(observed));
    }

    private static String plural(int count) {
        return count == 1 ? "" : "s";
    }
}
