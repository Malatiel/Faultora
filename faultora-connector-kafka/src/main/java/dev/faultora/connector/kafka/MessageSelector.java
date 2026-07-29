package dev.faultora.connector.kafka;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which of the messages in the observation window this step is about.
 * <p>
 * The floor bounds how far back an observation reaches, but it does not make
 * the observation deterministic: a repeat block that publishes and
 * observes on every iteration would see its earlier iterations again, and
 * "exactly one event" would be unwritable. Determinism comes from
 * <em>selection</em> — a step says which messages are its own, usually by the
 * correlation value it published, and the assertions run over those.
 * <p>
 * A selector is written in the step's inputs:
 * <pre>
 * with:
 *   match:
 *     key: "{{ inputs.paymentId }}"
 *     headers:
 *       correlation-id: "{{ inputs.paymentId }}"
 *     payload:
 *       paymentId: "{{ inputs.paymentId }}"
 *       status: settled
 * </pre>
 * Every clause present must hold. A selector with no clauses selects every
 * message in the window, which is the right default for a topic the run
 * created for itself and the wrong one for a shared topic.
 */
record MessageSelector(String key, Map<String, String> headers, Map<String, String> payload) {

    /** Input name carrying the selector. */
    static final String INPUT = "match";

    static final MessageSelector EVERYTHING =
            new MessageSelector(null, Map.of(), Map.of());

    /**
     * Read the selector from a step's inputs.
     *
     * @throws IllegalArgumentException when {@code match} is not a mapping
     */
    static MessageSelector from(Map<String, Object> inputs) {
        Object declared = inputs == null ? null : inputs.get(INPUT);
        if (declared == null) {
            return EVERYTHING;
        }
        if (!(declared instanceof Map<?, ?> match)) {
            throw new IllegalArgumentException(
                    "match must be a mapping of key, headers and payload clauses");
        }
        return new MessageSelector(
                text(match.get("key")),
                strings(match.get("headers")),
                strings(match.get("payload")));
    }

    /** Whether this message is one the step asked for. */
    boolean matches(MessageSelector.Candidate candidate) {
        if (key != null && !key.equals(candidate.key())) {
            return false;
        }
        for (Map.Entry<String, String> clause : headers.entrySet()) {
            if (!clause.getValue().equals(candidate.header(clause.getKey()))) {
                return false;
            }
        }
        for (Map.Entry<String, String> clause : payload.entrySet()) {
            if (!clause.getValue().equals(candidate.payloadField(clause.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Whether this selector narrows anything at all. */
    boolean selectsEverything() {
        return EVERYTHING.equals(this);
    }

    /**
     * What a selector is evaluated against.
     * <p>
     * The candidate is the message as it arrived, not as the evidence policy
     * left it: a run must be able to select on a field it is not allowed to
     * store, or a policy that withholds payloads would silently change which
     * messages a scenario sees.
     */
    interface Candidate {
        String key();

        String header(String name);

        String payloadField(String path);
    }

    /** Resolve a dotted path against a parsed payload, as text. */
    static String field(JsonNode payload, String path) {
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

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, String> strings(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> clauses = new LinkedHashMap<>();
        map.forEach((name, expected) -> {
            if (name != null && expected != null) {
                clauses.put(name.toString(), expected.toString());
            }
        });
        return Map.copyOf(clauses);
    }
}
