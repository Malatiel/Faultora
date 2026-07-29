package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.evidence.MessageEvidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Evidence from a step that observed messages, for the event assertions. */
class ObservedEvidence implements EvidenceView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<MessageEvidence> messages = new ArrayList<>();
    private final boolean observation;

    private ObservedEvidence(boolean observation) {
        this.observation = observation;
    }

    /** Evidence from a step that observed, however little it found. */
    static ObservedEvidence observing() {
        return new ObservedEvidence(true);
    }

    /** Evidence from a step that was about something other than messages. */
    static ObservedEvidence ofSomethingElse() {
        return new ObservedEvidence(false);
    }

    ObservedEvidence message(String key, String payloadJson) {
        return message(key, payloadJson, Map.of());
    }

    ObservedEvidence message(String key, String payloadJson, Map<String, String> headers) {
        messages.add(new MessageEvidence(
                "payment-events", 0, messages.size(), System.currentTimeMillis(),
                key, headers, parse(payloadJson), "sha256:" + messages.size()));
        return this;
    }

    /** A message whose payload the evidence policy withheld. */
    ObservedEvidence withheldMessage(String key) {
        messages.add(new MessageEvidence(
                "payment-events", 0, messages.size(), System.currentTimeMillis(),
                key, Map.of(), null, "sha256:withheld"));
        return this;
    }

    private static JsonNode parse(String json) {
        try {
            return json == null ? null : MAPPER.readTree(json);
        } catch (Exception notJson) {
            throw new IllegalArgumentException(notJson);
        }
    }

    @Override
    public Map<String, Object> protocolEvidence() {
        return observation
                ? Map.of(MessageEvidence.OBSERVED, List.copyOf(messages))
                : Map.of("status", "not an observation");
    }

    @Override
    public Optional<Integer> statusCode() {
        return Optional.empty();
    }

    @Override
    public Map<String, List<String>> responseHeaders() {
        return Map.of();
    }

    @Override
    public Optional<byte[]> responseBody() {
        return Optional.empty();
    }

    @Override
    public Optional<JsonNode> responseJson() {
        return Optional.empty();
    }

    @Override
    public long durationMs() {
        return 0;
    }

    @Override
    public Optional<NormalizedError> error() {
        return Optional.empty();
    }
}
