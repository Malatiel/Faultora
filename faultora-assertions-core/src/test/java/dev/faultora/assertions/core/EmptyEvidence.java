package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.EvidenceView;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Empty EvidenceView with no data, for testing indeterminate cases.
 */
class EmptyEvidence implements EvidenceView {

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

    @Override
    public Map<String, Object> protocolEvidence() {
        return Map.of();
    }
}
