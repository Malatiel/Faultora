package dev.faultora.spi.evidence;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One message a run published or observed.
 * <p>
 * This is the protocol-neutral shape of event evidence: a connector for any
 * broker produces it, assertions about events consume it, and the engine
 * journals it without knowing which broker it came from. Without a shared
 * shape, every event assertion would have to know the connector that produced
 * the evidence it reads, which is the coupling the SPI exists to prevent.
 * <p>
 * The payload is what the evidence policy permitted to be kept, so it is null
 * whenever the policy captures no bodies, the message exceeded the size the
 * policy allows, or a payload that had to be redacted could not be parsed. The
 * {@link #digest} is present either way: it identifies content without being
 * content, so a report can point at a message the run was not allowed to store.
 *
 * @param topic       channel the message belongs to
 * @param partition   partition it was written to or read from, -1 when unknown
 * @param offset      position within the partition, -1 when unknown
 * @param timestampMs broker timestamp in epoch millis, -1 when unknown
 * @param key         message key as text, null when the message has none
 * @param headers     headers the evidence policy permitted, never null
 * @param payload     payload the evidence policy permitted, parsed when it is
 *                    JSON and held as text when it is not, null when withheld
 * @param digest      {@code sha256:} digest of the raw payload bytes
 */
public record MessageEvidence(
        String topic,
        int partition,
        long offset,
        long timestampMs,
        String key,
        Map<String, String> headers,
        JsonNode payload,
        String digest
) {
    /** Protocol-evidence key under which observed messages are published. */
    public static final String OBSERVED = "messages";

    /** Protocol-evidence key under which a published message is recorded. */
    public static final String PUBLISHED = "published";

    public MessageEvidence {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /**
     * The messages an operation observed.
     *
     * @return the messages, or an empty list when the evidence has none —
     *         which is what evidence from a non-event protocol looks like
     */
    public static List<MessageEvidence> observedIn(Map<String, Object> protocolEvidence) {
        if (protocolEvidence == null) {
            return List.of();
        }
        if (!(protocolEvidence.get(OBSERVED) instanceof List<?> values)) {
            return List.of();
        }
        List<MessageEvidence> messages = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof MessageEvidence message) {
                messages.add(message);
            }
        }
        return List.copyOf(messages);
    }

    /** The message an operation published, or null when it published none. */
    public static MessageEvidence publishedIn(Map<String, Object> protocolEvidence) {
        return protocolEvidence != null
                && protocolEvidence.get(PUBLISHED) instanceof MessageEvidence published
                ? published : null;
    }
}
