package dev.faultora.engine.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.EvidenceView;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/**
 * In-memory evidence store for a single node execution.
 * Captures status code, headers, body, duration, and errors.
 * Respects evidence policy — bodies and headers are omitted when
 * the policy specifies captureBodies=false / captureHeaders=false,
 * content type is not in the allowlist, or body exceeds maxBodyBytes.
 * Sensitive JSON paths specified in redactPaths are replaced with "***"
 * before the body is stored.
 */
public class NodeEvidence implements EvidenceView {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REDACTED_MARKER = "***";

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
        if (!evidencePolicy.captureHeaders()) {
            this.headers = Map.of();
            return;
        }
        if (headers == null || headers.isEmpty()) {
            this.headers = Map.of();
            return;
        }
        // Filter out headers in the denylist (e.g., authorization, cookie)
        Map<String, List<String>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key != null && !evidencePolicy.headerDenylist().contains(key.toLowerCase())) {
                filtered.put(key, List.copyOf(entry.getValue()));
            }
        }
        this.headers = Map.copyOf(filtered);
    }

    /**
     * Set the response body with content type check.
     * Enforces captureBodies, contentTypeAllowlist, maxBodyBytes, and redactPaths.
     *
     * @param body        raw response body bytes
     * @param contentType MIME type from response headers (may be null)
     */
    public void body(byte[] body, String contentType) {
        if (!evidencePolicy.captureBodies()) {
            this.body = null;
            this.responseJson = null;
            return;
        }
        // contentTypeAllowlist: if non-empty, only capture body for allowed MIME types
        Set<String> allowlist = evidencePolicy.contentTypeAllowlist();
        if (!allowlist.isEmpty()) {
            String ct = contentType != null ? contentType.toLowerCase() : "";
            // Strip parameters: "application/json; charset=utf-8" → "application/json"
            String mimeType = ct.contains(";") ? ct.substring(0, ct.indexOf(';')).trim() : ct;
            boolean allowed = false;
            for (String allowedType : allowlist) {
                if (mimeType.equals(allowedType.toLowerCase()) || mimeType.startsWith(allowedType.toLowerCase() + "/")) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                this.body = null;
                this.responseJson = null;
                return;
            }
        }
        body(body);
    }

    public void body(byte[] body) {
        if (!evidencePolicy.captureBodies()) {
            this.body = null;
            this.responseJson = null;
            return;
        }
        if (body == null) {
            this.body = null;
            this.responseJson = null;
            return;
        }

        List<String> redactPaths = evidencePolicy.redactPaths();
        boolean redactionRequired = redactPaths != null && !redactPaths.isEmpty();
        if (redactionRequired) {
            try {
                // Parse and redact the complete JSON before applying the storage
                // limit. Truncating first could make JSON invalid and persist raw
                // secret fields without applying redactPaths.
                JsonNode json = MAPPER.readTree(body);
                applyRedaction(json, redactPaths);
                byte[] redacted = MAPPER.writeValueAsBytes(json);
                if (exceedsCaptureLimit(redacted)) {
                    omitBody();
                    return;
                }
                this.body = redacted;
                this.responseJson = json;
            } catch (Exception unsafeToCapture) {
                // Fail closed: content that cannot be parsed cannot be proven redacted.
                omitBody();
            }
            return;
        }

        byte[] effective = body;
        if (exceedsCaptureLimit(body)) {
            effective = Arrays.copyOf(body, (int) evidencePolicy.maxBodyBytes());
        }
        this.body = effective;
        try {
            this.responseJson = MAPPER.readTree(effective);
        } catch (Exception ignored) {
            this.responseJson = null;
        }
    }

    private boolean exceedsCaptureLimit(byte[] value) {
        return evidencePolicy.maxBodyBytes() > 0
                && value.length > evidencePolicy.maxBodyBytes();
    }

    private void omitBody() {
        this.body = null;
        this.responseJson = null;
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

    /**
     * Apply JSON path redaction to a parsed JSON tree.
     * Each path is a dot-separated field path (e.g. "creditCard.number",
     * "user.ssn"). Paths may optionally start with "$." (JSONPath root).
     * Matching leaf values are replaced with "***".
     * Array elements are traversed: a path "items.price" redacts the
     * "price" field in every element of the "items" array.
     *
     * @param node  the JSON tree to redact in-place
     * @param paths dot-separated paths to redact
     */
    static void applyRedaction(JsonNode node, List<String> paths) {
        if (node == null || paths == null) return;
        for (String path : paths) {
            if (path == null || path.isBlank()) continue;
            // Strip JSONPath root prefix
            String normalized = path.startsWith("$.") ? path.substring(2) : path;
            if (normalized.startsWith("$")) normalized = normalized.substring(1);
            if (normalized.startsWith(".")) normalized = normalized.substring(1);
            if (normalized.isEmpty()) continue;
            redactPath(node, normalized.split("\\."), 0);
        }
    }

    private static void redactPath(JsonNode current, String[] segments, int index) {
        if (current == null || index >= segments.length) return;

        String segment = segments[index];
        boolean lastSegment = (index == segments.length - 1);

        if (current.isArray()) {
            // Traverse all array elements
            for (JsonNode item : current) {
                redactPath(item, segments, index);
            }
            return;
        }

        if (!current.isObject()) return;
        ObjectNode obj = (ObjectNode) current;

        if (!obj.has(segment)) return;

        if (lastSegment) {
            // Replace value with redaction marker
            obj.put(segment, REDACTED_MARKER);
        } else {
            redactPath(obj.get(segment), segments, index + 1);
        }
    }
}
