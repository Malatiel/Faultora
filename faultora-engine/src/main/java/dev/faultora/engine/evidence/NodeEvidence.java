package dev.faultora.engine.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.EvidenceView;

import java.util.*;

/**
 * In-memory evidence store for a single node execution.
 * Captures status code, headers, body, duration, and errors.
 * Respects evidence policy (bodies/headers may be omitted).
 */
public class NodeEvidence implements EvidenceView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private int statusCode = -1;
    private Map<String, List<String>> headers = Map.of();
    private byte[] body;
    private JsonNode responseJson;
    private long durationMs;
    private NormalizedError error;
    private final Map<String, Object> protocolEvidence = new LinkedHashMap<>();

    public void statusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public void headers(Map<String, List<String>> headers) {
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
    }

    public void body(byte[] body) {
        this.body = body;
        if (body != null) {
            try {
                this.responseJson = MAPPER.readTree(body);
            } catch (Exception ignored) {
                this.responseJson = null;
            }
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
        return body != null ? Optional.of(body) : Optional.empty();
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
        return protocolEvidence;
    }

    public boolean hasError() {
        return error != null;
    }
}
