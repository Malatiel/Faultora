package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.evidence.TableEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Evidence from a step that observed rows, for the tabular assertions. */
class ObservedTableEvidence implements EvidenceView {

    private final List<String> columns;
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final boolean observation;
    private boolean truncated;

    private ObservedTableEvidence(boolean observation, String... columns) {
        this.observation = observation;
        this.columns = List.of(columns);
    }

    static ObservedTableEvidence observing(String... columns) {
        return new ObservedTableEvidence(true, columns);
    }

    /** Evidence from a step that was about something other than rows. */
    static ObservedTableEvidence ofSomethingElse() {
        return new ObservedTableEvidence(false);
    }

    ObservedTableEvidence row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < columns.size() && index < values.length; index++) {
            row.put(columns.get(index), values[index]);
        }
        rows.add(row);
        return this;
    }

    /** The same rows, but cut short by the row limit. */
    ObservedTableEvidence truncated() {
        this.truncated = true;
        return this;
    }

    @Override
    public Map<String, Object> protocolEvidence() {
        return observation
                ? Map.of(TableEvidence.OBSERVED,
                        new TableEvidence(columns, rows, truncated))
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
