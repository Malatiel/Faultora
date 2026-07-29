package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.evidence.MessageEvidence;
import dev.faultora.spi.result.AssertionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * That the observed messages tell the story the scenario expects.
 * <p>
 * A workflow is a sequence — accepted, then settled — and whether the order
 * matters is a property of the workflow, not of the assertion. Both are
 * expressible:
 * <pre>
 * assertions:
 *   - type: event-sequence
 *     ordered: true          # the default
 *     of:
 *       - { payload:status: accepted }
 *       - { payload:status: settled }
 * </pre>
 * Each step of the sequence is a set of locator/value pairs that one message
 * must satisfy; a message satisfies at most one step. Ordered means the
 * matching messages appear in the observed order, not that they are adjacent —
 * a workflow may emit events the scenario is not asserting about, and demanding
 * adjacency would make the assertion break every time the system does something
 * additional and correct.
 */
public class EventSequenceAssertionProvider implements AssertionProvider {

    @Override
    public String type() {
        return "event-sequence";
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
                    "This step observed no messages, so there is no sequence to check");
        }
        List<Map<String, String>> expected = expected(params);
        if (expected.isEmpty()) {
            return AssertionResult.indeterminate(
                    "event-sequence needs 'of' to list the messages it expects");
        }
        boolean ordered = !Boolean.FALSE.equals(params.get("ordered"));
        List<MessageEvidence> messages = ObservedMessages.of(evidence);

        return ordered
                ? inOrder(expected, messages)
                : inAnyOrder(expected, messages);
    }

    /** Each expected message appears after the one before it. */
    private AssertionResult inOrder(
            List<Map<String, String>> expected, List<MessageEvidence> messages) {
        int next = 0;
        for (int step = 0; step < expected.size(); step++) {
            Map<String, String> clauses = expected.get(step);
            int found = -1;
            for (int index = next; index < messages.size(); index++) {
                if (satisfies(messages.get(index), clauses)) {
                    found = index;
                    break;
                }
            }
            if (found < 0) {
                return AssertionResult.fail(
                        "No message matching " + clauses + " follows the "
                                + step + " already matched",
                        Map.of("step", step, "observed", messages.size()));
            }
            next = found + 1;
        }
        return AssertionResult.pass(
                "The observed messages contain the expected sequence of "
                        + expected.size());
    }

    /** Each expected message appears, and no message answers for two of them. */
    private AssertionResult inAnyOrder(
            List<Map<String, String>> expected, List<MessageEvidence> messages) {
        List<MessageEvidence> unclaimed = new ArrayList<>(messages);
        for (Map<String, String> clauses : expected) {
            boolean matched = false;
            for (int index = 0; index < unclaimed.size(); index++) {
                if (satisfies(unclaimed.get(index), clauses)) {
                    unclaimed.remove(index);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return AssertionResult.fail(
                        "No observed message matches " + clauses,
                        Map.of("observed", messages.size()));
            }
        }
        return AssertionResult.pass(
                "Every one of the " + expected.size()
                        + " expected messages was observed");
    }

    private boolean satisfies(MessageEvidence message, Map<String, String> clauses) {
        for (Map.Entry<String, String> clause : clauses.entrySet()) {
            if (!clause.getValue().equals(
                    ObservedMessages.value(message, clause.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** The expected messages, each as locator/value pairs. */
    private List<Map<String, String>> expected(Map<String, Object> params) {
        Object declared = params == null ? null : params.get("of");
        if (!(declared instanceof List<?> steps)) {
            return List.of();
        }
        List<Map<String, String>> expected = new ArrayList<>(steps.size());
        for (Object step : steps) {
            if (!(step instanceof Map<?, ?> clauses)) {
                throw new IllegalArgumentException(
                        "Each entry of 'of' must be a mapping of locator to value");
            }
            Map<String, String> parsed = new LinkedHashMap<>();
            clauses.forEach((locator, value) -> {
                if (locator != null && value != null) {
                    parsed.put(locator.toString(), value.toString());
                }
            });
            expected.add(Map.copyOf(parsed));
        }
        return List.copyOf(expected);
    }
}
