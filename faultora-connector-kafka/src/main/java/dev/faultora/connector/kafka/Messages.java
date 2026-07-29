package dev.faultora.connector.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.evidence.EvidenceCapture;
import dev.faultora.spi.evidence.MessageEvidence;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning Kafka records into inputs and evidence.
 * <p>
 * The evidence policy is applied here rather than by the engine, because the
 * engine's idea of evidence is a response body and headers, and a message is
 * neither. What the policy says about bodies governs payloads, and what it says
 * about headers governs message headers: a token in a message header is as much
 * a secret as one in an HTTP header.
 */
final class Messages {

    /** Media type assumed for a payload, for the policy's content-type allowlist. */
    private static final String JSON = "application/json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Messages() {
    }

    /**
     * Serialize a value a scenario wants published.
     * <p>
     * Text is published as written — a scenario that hand-wrote a payload means
     * those bytes. Anything else is JSON, which is what a generated payload and
     * a scenario mapping both are.
     */
    static byte[] payloadOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof CharSequence text) {
            return text.toString().getBytes(StandardCharsets.UTF_8);
        }
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception notSerializable) {
            throw new IllegalArgumentException(
                    "Message payload cannot be serialized: " + notSerializable.getMessage(),
                    notSerializable);
        }
    }

    /** Header values as text, with a repeated name keeping its last value. */
    static Map<String, String> headersOf(Headers headers) {
        Map<String, String> asText = new LinkedHashMap<>();
        if (headers != null) {
            for (Header header : headers) {
                asText.put(header.key(), header.value() == null
                        ? null : new String(header.value(), StandardCharsets.UTF_8));
            }
        }
        return asText;
    }

    /** A scenario's {@code headers} input as text pairs. */
    static Map<String, String> declaredHeaders(Object declared) {
        if (!(declared instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        map.forEach((name, value) -> {
            if (name != null && value != null) {
                headers.put(name.toString(), value.toString());
            }
        });
        return headers;
    }

    /**
     * Evidence for one message, holding only what the policy permits.
     *
     * @param withinBudget whether this observation still has room to store
     *                     payloads; past the budget the digest stands alone
     */
    static MessageEvidence evidence(
            String topic,
            int partition,
            long offset,
            long timestampMs,
            String key,
            Map<String, String> headers,
            byte[] payload,
            EvidencePolicy policy,
            boolean withinBudget
    ) {
        byte[] kept = withinBudget
                ? EvidenceCapture.content(payload, JSON, policy)
                : null;
        return new MessageEvidence(
                topic, partition, offset, timestampMs, key,
                permittedHeaders(headers, policy),
                parsed(kept),
                payload == null ? null : ContentDigest.sha256Uri(payload));
    }

    /**
     * Headers the policy permits, filtered by the same denylist that protects
     * HTTP headers. Message headers are where correlation ids live, and also
     * where a token ends up when someone puts one there.
     */
    private static Map<String, String> permittedHeaders(
            Map<String, String> headers, EvidencePolicy policy) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> asLists = new LinkedHashMap<>();
        headers.forEach((name, value) ->
                asLists.put(name, value == null ? List.of() : List.of(value)));
        Map<String, List<String>> permitted = EvidenceCapture.headers(asLists, policy);

        Map<String, String> flattened = new LinkedHashMap<>();
        permitted.forEach((name, values) ->
                flattened.put(name, values.isEmpty() ? null : values.get(0)));
        return flattened;
    }

    /** A payload as a JSON tree, or as text when it is not JSON. */
    private static JsonNode parsed(byte[] payload) {
        if (payload == null) {
            return null;
        }
        try {
            return MAPPER.readTree(payload);
        } catch (Exception notJson) {
            return TextNode.valueOf(new String(payload, StandardCharsets.UTF_8));
        }
    }

    /** The payload parsed for selection, whatever the policy stores. */
    static JsonNode forSelection(byte[] payload) {
        return parsed(payload);
    }
}
