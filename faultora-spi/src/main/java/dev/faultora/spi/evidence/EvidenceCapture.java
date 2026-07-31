package dev.faultora.spi.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.model.security.EvidencePolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies an {@link EvidencePolicy} to content before it is kept.
 * <p>
 * The policy is one document, so it needs one implementation. The engine
 * applies it to an HTTP response; a connector whose evidence is not a response
 * — a set of consumed messages, a result set — has to apply it itself, because
 * only the connector knows what its evidence is made of. Both call this.
 * <p>
 * Two rules are worth stating because they are decisions rather than
 * mechanics:
 * <ul>
 *   <li><b>Redaction fails closed.</b> Content that has to be redacted but
 *       cannot be parsed is dropped, not kept. Keeping it would mean keeping a
 *       secret the policy said to remove.</li>
 *   <li><b>Redaction happens before the size limit.</b> Truncating first could
 *       cut a document into something unparseable and so unredactable, which
 *       would persist the raw fields the policy named.</li>
 * </ul>
 */
public final class EvidenceCapture {

    /** What a redacted value is replaced with. */
    public static final String REDACTED_MARKER = "***";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvidenceCapture() {
    }

    /**
     * The headers the policy permits keeping.
     *
     * @return the permitted headers, empty when the policy captures none
     */
    public static Map<String, List<String>> headers(
            Map<String, List<String>> headers, EvidencePolicy policy) {
        EvidencePolicy effective = effective(policy);
        if (!effective.captureHeaders() || headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> kept = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name != null
                    && !effective.headerDenylist().contains(name.toLowerCase(Locale.ROOT))) {
                kept.put(name, values == null ? List.of() : List.copyOf(values));
            }
        });
        return Map.copyOf(kept);
    }

    /**
     * The content the policy permits keeping, with its declared type checked
     * against the allowlist.
     *
     * @param contentType the declared media type, or null when none was given
     * @return the content to keep, or null when the policy keeps none of it
     */
    public static byte[] content(byte[] content, String contentType, EvidencePolicy policy) {
        EvidencePolicy effective = effective(policy);
        if (!effective.captureBodies()) {
            return null;
        }
        return typeAllowed(contentType, effective) ? content(content, effective) : null;
    }

    /**
     * The content the policy permits keeping, for evidence that carries no
     * declared media type of its own.
     *
     * @return the content to keep, or null when the policy keeps none of it
     */
    public static byte[] content(byte[] content, EvidencePolicy policy) {
        EvidencePolicy effective = effective(policy);
        if (!effective.captureBodies() || content == null) {
            return null;
        }

        List<String> redactPaths = effective.redactPaths();
        if (redactPaths != null && !redactPaths.isEmpty()) {
            try {
                JsonNode parsed = MAPPER.readTree(content);
                redact(parsed, redactPaths);
                byte[] redacted = MAPPER.writeValueAsBytes(parsed);
                return exceedsLimit(redacted, effective) ? null : redacted;
            } catch (Exception unprovable) {
                return null;
            }
        }

        return exceedsLimit(content, effective)
                ? Arrays.copyOf(content, (int) effective.maxBodyBytes())
                : Arrays.copyOf(content, content.length);
    }

    /**
     * The rows the policy permits keeping, as tabular evidence.
     * <p>
     * A result set is not a document, so the same policy lands on it slightly
     * differently, and both ways it lands conservatively:
     * <ul>
     *   <li><b>No bodies means no values, not no rows.</b> How many rows an
     *       observation returned is a count rather than content, and the
     *       counting assertions still answer from it. The values go, and
     *       {@link TableEvidence#valuesWithheld()} says they went, so an
     *       assertion that reads one is indeterminate rather than comparing
     *       against a blank.</li>
     *   <li><b>A redact path names a column.</b> Its leading segment is matched
     *       against the column name and the whole cell is replaced. Matching
     *       further into a cell would mean parsing it as a document, and a cell
     *       that cannot be parsed could not then be redacted — so the cell goes
     *       whole, which is the side of that choice a policy asked for.</li>
     * </ul>
     *
     * @param rows each row keyed by column name, in the order they were read
     */
    public static TableEvidence table(
            List<String> columns,
            List<Map<String, Object>> rows,
            boolean truncated,
            EvidencePolicy policy
    ) {
        EvidencePolicy effective = effective(policy);
        List<Map<String, Object>> kept = rows == null ? List.of() : rows;
        if (!effective.captureBodies()) {
            return new TableEvidence(
                    columns, kept.stream().map(row -> Map.<String, Object>of()).toList(),
                    truncated, true);
        }

        Set<String> redactedColumns = leadingSegments(effective.redactPaths());
        if (redactedColumns.isEmpty()) {
            return new TableEvidence(columns, kept, truncated, false);
        }
        List<Map<String, Object>> redacted = new ArrayList<>(kept.size());
        for (Map<String, Object> row : kept) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.replaceAll((column, value) ->
                    redactedColumns.contains(column.toLowerCase(Locale.ROOT))
                            ? REDACTED_MARKER : value);
            redacted.add(copy);
        }
        return new TableEvidence(columns, redacted, truncated, false);
    }

    /** The first segment of each redact path, lowercased. */
    private static Set<String> leadingSegments(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Set.of();
        }
        Set<String> segments = new LinkedHashSet<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String normalized = path.startsWith("$.") ? path.substring(2) : path;
            if (normalized.startsWith("$")) normalized = normalized.substring(1);
            if (normalized.startsWith(".")) normalized = normalized.substring(1);
            int dot = normalized.indexOf('.');
            String leading = dot < 0 ? normalized : normalized.substring(0, dot);
            if (!leading.isBlank()) {
                segments.add(leading.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(segments);
    }

    /** Whether the policy's allowlist admits this media type. */
    private static boolean typeAllowed(String contentType, EvidencePolicy policy) {
        Set<String> allowlist = policy.contentTypeAllowlist();
        if (allowlist.isEmpty()) {
            return true;
        }
        String declared = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String mimeType = declared.contains(";")
                ? declared.substring(0, declared.indexOf(';')).trim()
                : declared;
        for (String allowed : allowlist) {
            String candidate = allowed.toLowerCase(Locale.ROOT);
            if (mimeType.equals(candidate) || mimeType.startsWith(candidate + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean exceedsLimit(byte[] content, EvidencePolicy policy) {
        return policy.maxBodyBytes() > 0 && content.length > policy.maxBodyBytes();
    }

    /**
     * Replace the values at these paths with {@link #REDACTED_MARKER}, in
     * place.
     * <p>
     * A path is dot-separated and may start with a JSONPath root
     * ({@code $.card.number}). Arrays are traversed, so {@code items.price}
     * redacts the price of every item.
     */
    public static void redact(JsonNode tree, List<String> paths) {
        if (tree == null || paths == null) {
            return;
        }
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String normalized = path.startsWith("$.") ? path.substring(2) : path;
            if (normalized.startsWith("$")) normalized = normalized.substring(1);
            if (normalized.startsWith(".")) normalized = normalized.substring(1);
            if (normalized.isEmpty()) {
                continue;
            }
            redactPath(tree, normalized.split("\\."), 0);
        }
    }

    private static void redactPath(JsonNode current, String[] segments, int index) {
        if (current == null || index >= segments.length) {
            return;
        }
        if (current.isArray()) {
            for (JsonNode item : current) {
                redactPath(item, segments, index);
            }
            return;
        }
        if (!(current instanceof ObjectNode object)) {
            return;
        }
        String segment = segments[index];
        if (!object.has(segment)) {
            return;
        }
        if (index == segments.length - 1) {
            object.put(segment, REDACTED_MARKER);
        } else {
            redactPath(object.get(segment), segments, index + 1);
        }
    }

    private static EvidencePolicy effective(EvidencePolicy policy) {
        return policy != null ? policy : EvidencePolicy.MINIMAL;
    }
}
