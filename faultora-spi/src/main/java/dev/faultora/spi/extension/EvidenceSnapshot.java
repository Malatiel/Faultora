package dev.faultora.spi.extension;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.EvidenceView;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything an assertion can see, as data rather than as an object.
 * <p>
 * ADR-023 says three of the SPI contracts are pure functions over what they are
 * handed, and can therefore run somewhere their heap and sockets are not the
 * run's. That is a claim about {@link EvidenceView}, which is an interface —
 * and an interface whose every method happens to return data is exactly the
 * kind of thing that stops being true when somebody adds a method returning a
 * live connection.
 * <p>
 * This is that claim made checkable. An evidence view converts to a snapshot
 * and back, and a test compares the two; a method added to the interface with
 * no field here fails to compile or fails that comparison. What crosses a
 * process boundary is this record, not the view.
 * <p>
 * The body travels base64-encoded because it is bytes and the transport is
 * text. That is the only transformation: everything else is the value the view
 * returned.
 *
 * @param statusCode      HTTP status, or null for a non-HTTP operation
 * @param responseHeaders headers the evidence policy permitted keeping
 * @param responseBody    the body, base64, or null when none was kept
 * @param durationMs      how long the operation took
 * @param error           what went wrong, or null
 * @param protocolEvidence protocol-specific values, already plain data
 */
public record EvidenceSnapshot(
        @JsonProperty("statusCode") Integer statusCode,
        @JsonProperty("responseHeaders") Map<String, List<String>> responseHeaders,
        @JsonProperty("responseBody") String responseBody,
        @JsonProperty("durationMs") long durationMs,
        @JsonProperty("error") NormalizedError error,
        @JsonProperty("protocolEvidence") Map<String, Object> protocolEvidence
) {
    public EvidenceSnapshot {
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        protocolEvidence = protocolEvidence == null ? Map.of() : Map.copyOf(protocolEvidence);
    }

    /** Take everything a view can be asked for. */
    public static EvidenceSnapshot of(EvidenceView view) {
        return new EvidenceSnapshot(
                view.statusCode().orElse(null),
                view.responseHeaders(),
                view.responseBody().map(Base64.getEncoder()::encodeToString).orElse(null),
                view.durationMs(),
                view.error().orElse(null),
                view.protocolEvidence());
    }

    /**
     * The same evidence, as the interface an assertion expects.
     * <p>
     * The JSON view is parsed here rather than carried, because a tree and the
     * bytes it was parsed from are one fact and carrying both invites them to
     * disagree.
     */
    public EvidenceView asView() {
        byte[] body = responseBody == null ? null : Base64.getDecoder().decode(responseBody);
        return new EvidenceView() {
            @Override
            public Optional<Integer> statusCode() {
                return Optional.ofNullable(statusCode);
            }

            @Override
            public Map<String, List<String>> responseHeaders() {
                return responseHeaders;
            }

            @Override
            public Optional<byte[]> responseBody() {
                return Optional.ofNullable(body);
            }

            @Override
            public Optional<JsonNode> responseJson() {
                if (body == null) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(body));
                } catch (Exception notJson) {
                    return Optional.empty();
                }
            }

            @Override
            public long durationMs() {
                return durationMs;
            }

            @Override
            public Optional<NormalizedError> error() {
                return Optional.ofNullable(error);
            }

            @Override
            public Map<String, Object> protocolEvidence() {
                return protocolEvidence;
            }
        };
    }
}
