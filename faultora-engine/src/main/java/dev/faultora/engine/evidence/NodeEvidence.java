package dev.faultora.engine.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.evidence.EvidenceCapture;
import dev.faultora.spi.evidence.MessageEvidence;
import dev.faultora.spi.evidence.TableEvidence;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    private final EvidenceBudget budget;
    private int statusCode = -1;
    private Map<String, List<String>> headers = Map.of();
    private boolean headersWithheldByBudget;
    private byte[] body;
    private boolean bodyComplete;
    private JsonNode responseJson;
    private boolean budgetExceeded;
    private String tableDigest;
    private boolean tableRowsWithheldByBudget;
    private long durationMs;
    private NormalizedError error;
    private final Map<String, Object> protocolEvidence = new LinkedHashMap<>();

    public NodeEvidence() {
        this(EvidencePolicy.MINIMAL);
    }

    public NodeEvidence(EvidencePolicy evidencePolicy) {
        this(evidencePolicy, new EvidenceBudget(Long.MAX_VALUE));
    }

    public NodeEvidence(EvidencePolicy evidencePolicy, EvidenceBudget budget) {
        this.evidencePolicy = evidencePolicy != null ? evidencePolicy : EvidencePolicy.MINIMAL;
        this.budget = Objects.requireNonNull(budget);
    }

    public void statusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public void headers(Map<String, List<String>> headers) {
        Map<String, List<String>> permitted = EvidenceCapture.headers(headers, evidencePolicy);
        long bytes = 0;
        for (var entry : permitted.entrySet()) {
            bytes += bytesOf(entry.getKey());
            for (String value : entry.getValue()) bytes += bytesOf(value);
        }
        this.headersWithheldByBudget = !admit(bytes);
        this.headers = headersWithheldByBudget ? Map.of() : permitted;
    }

    /**
     * Keep the response body, as far as the evidence policy allows.
     *
     * @param body        raw response body bytes
     * @param contentType MIME type from the response headers, may be null
     */
    public void body(byte[] body, String contentType) {
        keep(body, EvidenceCapture.content(body, contentType, evidencePolicy));
    }

    public void body(byte[] body) {
        keep(body, EvidenceCapture.content(body, evidencePolicy));
    }

    /** Hold what the policy permitted, parsed when it happens to be JSON. */
    private void keep(byte[] original, byte[] permitted) {
        this.body = permitted != null && admit(permitted.length) ? permitted : null;
        this.bodyComplete = EvidenceCapture.complete(original, body, evidencePolicy);
        if (body == null) {
            this.responseJson = null;
            return;
        }
        try {
            this.responseJson = MAPPER.readTree(body);
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
        if (TableEvidence.OBSERVED.equals(key) && value instanceof TableEvidence table) {
            byte[] permitted = table.canonicalForm().getBytes(StandardCharsets.UTF_8);
            tableDigest = ContentDigest.sha256Uri(permitted);
            // Previously withheld rows may still be bound as an array of empty objects.
            long bytes = table.valuesWithheld() ? 2 + Math.max(0L, 3L * table.rowCount() - 1) : permitted.length;
            tableRowsWithheldByBudget = !admit(bytes);
            if (tableRowsWithheldByBudget && !table.valuesWithheld()) {
                value = new TableEvidence(table.columns(), Collections.nCopies(table.rowCount(), Map.of()),
                        table.truncated(), true, table.redactedColumns());
            }
        } else if (value instanceof MessageEvidence message) {
            value = retainedMessage(message);
        } else if (MessageEvidence.OBSERVED.equals(key) && value instanceof List<?> messages) {
            var retained = new ArrayList<>();
            for (Object item : messages) {
                if (item instanceof MessageEvidence message) {
                    retained.add(retainedMessage(message));
                } else if (admitJson("", item)) {
                    retained.add(item);
                }
            }
            value = Collections.unmodifiableList(retained);
        } else if (!isObservationMetadata(key, value) && !admitJson(key, value)) {
            return;
        }
        protocolEvidence.put(key, value);
    }

    private MessageEvidence retainedMessage(MessageEvidence message) {
        boolean keepKey = message.key() == null || admit(bytesOf(message.key()));
        long headerBytes = 0;
        for (var entry : message.headers().entrySet()) {
            headerBytes += bytesOf(entry.getKey()) + bytesOf(entry.getValue());
        }
        boolean keepHeaders = admit(headerBytes);
        JsonNode payload = message.payload();
        boolean keepPayload = payload == null || admitJson("", payload);
        if (keepKey && keepHeaders && keepPayload) return message;
        return new MessageEvidence(message.topic(), message.partition(), message.offset(), message.timestampMs(),
                keepKey ? message.key() : null, keepHeaders ? message.headers() : Map.of(), keepPayload ? payload : null,
                message.digest(), keepPayload && message.payloadComplete(),
                keepHeaders && message.headersCaptured(), message.withheldHeaders(), message.keyWithheld() || !keepKey);
    }

    private static boolean isObservationMetadata(String key, Object value) {
        return switch (key) {
            case "topic" -> value instanceof String topic && topic.length() <= 249;
            case "partition", "offset", "observed", "matched", "waitedMs", "requestedWaitMs",
                    "rowCount", "fetchedRows" -> value instanceof Integer || value instanceof Long;
            case "selective", "truncated" -> value instanceof Boolean;
            default -> false;
        };
    }

    private boolean admitJson(String key, Object value) {
        var counter = new JsonSizeCounter(budget.remaining());
        try {
            counter.add(bytesOf(key));
            MAPPER.writeValue(counter, value);
            return admit(counter.bytes);
        } catch (IOException unboundedOrUnsupported) {
            budgetExceeded = true;
            return false;
        }
    }

    /** Count encoded bytes without building a second in-memory copy of an extension value. */
    private static final class JsonSizeCounter extends OutputStream {
        private final long limit;
        private long bytes;

        JsonSizeCounter(long limit) { this.limit = limit; }

        @Override public void write(int value) throws IOException { add(1); }

        @Override public void write(byte[] data, int offset, int length) throws IOException { add(length); }

        void add(long count) throws IOException {
            if (count > limit - bytes) throw new IOException("Evidence size exceeds remaining budget");
            bytes += count;
        }
    }

    private boolean admit(long bytes) {
        if (budget.admit(bytes)) return true;
        budgetExceeded = true;
        return false;
    }

    private static long bytesOf(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    public boolean budgetExceeded() { return budgetExceeded; }

    /** Digest of the policy-permitted table, before applying the shared retention limit. */
    public String tableDigest() { return tableDigest; }

    public boolean tableRowsWithheldByBudget() { return tableRowsWithheldByBudget; }

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
        return responseJson != null ? Optional.of(responseJson.deepCopy()) : Optional.empty();
    }

    @Override
    public boolean bodyComplete() {
        return bodyComplete;
    }

    @Override
    public boolean headerReadable(String name) {
        return evidencePolicy.captureHeaders() && !headersWithheldByBudget
                && !evidencePolicy.headerDenylist().contains(name.toLowerCase(Locale.ROOT));
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
