package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.evidence.MessageEvidence;

import java.util.List;
import java.util.Map;

/**
 * Reading the messages an event assertion is about.
 * <p>
 * Every event assertion needs the same two things: the messages a step
 * observed, and one value out of a message. Both are here so that the four
 * assertions differ only in what they conclude.
 */
final class ObservedMessages {

    /** Locator prefix selecting the message key. */
    private static final String KEY = "key";
    /** Locator prefix selecting a message header. */
    private static final String HEADER = "header:";
    /** Locator prefix selecting a payload field. */
    private static final String PAYLOAD = "payload:";

    private ObservedMessages() {
    }

    /** The messages the step observed. */
    static List<MessageEvidence> of(EvidenceView evidence) {
        return MessageEvidence.observedIn(evidence.protocolEvidence());
    }

    /**
     * Whether this evidence came from a step that observed messages at all.
     * <p>
     * An observation that found none still counts: the difference between
     * "no messages arrived" and "this step was not about messages" is the
     * difference between a failed assertion and one that could not be
     * evaluated.
     */
    static boolean isObservation(EvidenceView evidence) {
        Map<String, Object> protocolEvidence = evidence.protocolEvidence();
        return protocolEvidence != null
                && protocolEvidence.containsKey(MessageEvidence.OBSERVED);
    }

    /**
     * One value out of a message, named the way the scenario named it:
     * {@code key}, {@code header:<name>}, {@code payload:<dotted.path>}, or a
     * bare dotted path, which means a payload field.
     *
     * @return the value as text, or null when the message does not carry it
     */
    static String value(MessageEvidence message, String locator) {
        if (locator == null || locator.isBlank()) {
            return null;
        }
        String trimmed = locator.trim();
        if (KEY.equals(trimmed)) {
            return message.key();
        }
        if (trimmed.startsWith(HEADER)) {
            return message.headers().get(trimmed.substring(HEADER.length()));
        }
        String path = trimmed.startsWith(PAYLOAD)
                ? trimmed.substring(PAYLOAD.length()) : trimmed;
        return field(message.payload(), path);
    }

    /**
     * Whether the value at this locator can be read at all.
     * <p>
     * A payload the evidence policy withheld is not the same as a payload
     * missing a field, and an assertion that cannot see what it is checking
     * must say so rather than fail.
     * <p>
     * Headers are the ambiguous case: a message that carried none and a policy
     * that captured none look identical in the evidence. Erring towards
     * unreadable is the safe side, because both outcomes fail the node — an
     * assertion that could not be evaluated is not a silent pass — and only one
     * of the two messages is accurate.
     */
    static boolean isReadable(MessageEvidence message, String locator) {
        if (locator == null || locator.isBlank()) {
            return false;
        }
        String trimmed = locator.trim();
        if (KEY.equals(trimmed)) {
            return true;
        }
        if (trimmed.startsWith(HEADER)) {
            return !message.headers().isEmpty();
        }
        return message.payload() != null;
    }

    /** Why a locator could not be read, for an indeterminate verdict. */
    static String unreadable(String locator) {
        return locator != null && locator.trim().startsWith(HEADER)
                ? "No headers were captured for this message, so '" + locator
                        + "' cannot be read"
                : "The message payload was not captured, so '" + locator
                        + "' cannot be read";
    }

    private static String field(JsonNode payload, String path) {
        JsonNode current = payload;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(segment);
        }
        if (current == null || current.isNull()) {
            return null;
        }
        return current.isValueNode() ? current.asText() : current.toString();
    }

    /** A required string parameter, or null when the scenario omitted it. */
    static String parameter(Map<String, Object> params, String name) {
        Object value = params == null ? null : params.get(name);
        return value == null ? null : value.toString();
    }

    /** An integer parameter, or null when the scenario omitted it. */
    static Integer number(Map<String, Object> params, String name) {
        Object value = params == null ? null : params.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number count) {
            return count.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    name + " must be a number, got: " + value);
        }
    }
}
