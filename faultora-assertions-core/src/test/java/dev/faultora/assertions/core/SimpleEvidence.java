package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.EvidenceView;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Simple EvidenceView implementation for tests.
 */
class SimpleEvidence implements EvidenceView {

    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final JsonNode responseJson;
    private final long durationMs;

    SimpleEvidence(int statusCode, Map<String, List<String>> headers,
                   byte[] body, JsonNode responseJson, long durationMs) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
        this.responseJson = responseJson;
        this.durationMs = durationMs;
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
        return Optional.empty();
    }

    @Override
    public Map<String, Object> protocolEvidence() {
        return Map.of();
    }
}
