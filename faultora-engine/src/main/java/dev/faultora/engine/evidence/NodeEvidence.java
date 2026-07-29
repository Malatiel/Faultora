package dev.faultora.engine.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.evidence.EvidenceCapture;

import java.util.*;

/**
 * In-memory evidence store for a single node execution.
 * Captures status code, headers, body, duration, and errors.
 * What the evidence policy permits to be kept is decided by
 * {@link EvidenceCapture}, which every connector shares: one policy, one
 * implementation of it.
 */
public class NodeEvidence implements EvidenceView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EvidencePolicy evidencePolicy;
    private int statusCode = -1;
    private Map<String, List<String>> headers = Map.of();
    private byte[] body;
    private JsonNode responseJson;
    private long durationMs;
    private NormalizedError error;
    private final Map<String, Object> protocolEvidence = new LinkedHashMap<>();

    public NodeEvidence() {
        this(EvidencePolicy.MINIMAL);
    }

    public NodeEvidence(EvidencePolicy evidencePolicy) {
        this.evidencePolicy = evidencePolicy != null ? evidencePolicy : EvidencePolicy.MINIMAL;
    }

    public void statusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public void headers(Map<String, List<String>> headers) {
        this.headers = EvidenceCapture.headers(headers, evidencePolicy);
    }

    /**
     * Keep the response body, as far as the evidence policy allows.
     *
     * @param body        raw response body bytes
     * @param contentType MIME type from the response headers, may be null
     */
    public void body(byte[] body, String contentType) {
        keep(EvidenceCapture.content(body, contentType, evidencePolicy));
    }

    public void body(byte[] body) {
        keep(EvidenceCapture.content(body, evidencePolicy));
    }

    /** Hold what the policy permitted, parsed when it happens to be JSON. */
    private void keep(byte[] permitted) {
        this.body = permitted;
        if (permitted == null) {
            this.responseJson = null;
            return;
        }
        try {
            this.responseJson = MAPPER.readTree(permitted);
        } catch (Exception notJson) {
            this.responseJson = null;
        }
    }

    public void durationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public void error(NormalizedError error) {
        this.error = error;
    }

    public void error(Optional<NormalizedError> error) {
        this.error = error.orElse(null);
    }

    public void protocolEvidence(String key, Object value) {
        protocolEvidence.put(key, value);
    }

    @Override
    public Optional<Integer> statusCode() {
        return statusCode >= 0 ? Optional.of(statusCode) : Optional.empty();
    }

    @Override
    public Map<String, List<String>> responseHeaders() {
        return headers;
    }

    @Override
    public Optional<byte[]> responseBody() {
        return body != null ? Optional.of(Arrays.copyOf(body, body.length)) : Optional.empty();
    }

    @Override
    public Optional<JsonNode> responseJson() {
        return responseJson != null ? Optional.of(responseJson) : Optional.empty();
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
        return Map.copyOf(protocolEvidence);
    }

    public boolean hasError() {
        return error != null;
    }

}
