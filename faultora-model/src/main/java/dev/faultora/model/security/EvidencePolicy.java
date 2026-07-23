package dev.faultora.model.security;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Policy controlling evidence capture, retention, and redaction.
 *
 * @param captureBodies            whether to capture request/response bodies at all
 * @param captureHeaders           whether to capture headers
 * @param headerDenylist           headers never captured (always includes Authorization, Cookie)
 * @param maxBodyBytes             maximum body size captured
 * @param maxRows                  maximum rows for database observations
 * @param redactPaths              JSONPath expressions to redact from captured data
 * @param contentTypeAllowlist     MIME types allowed for body capture (empty = all)
 * @param retentionClass           retention duration class (session, short, standard, long)
 */
public record EvidencePolicy(
        boolean captureBodies,
        boolean captureHeaders,
        Set<String> headerDenylist,
        long maxBodyBytes,
        int maxRows,
        List<String> redactPaths,
        Set<String> contentTypeAllowlist,
        String retentionClass
) {
    private static final Set<String> REQUIRED_HEADER_DENYLIST = Set.of(
            "authorization", "cookie", "set-cookie", "proxy-authorization");

    public EvidencePolicy {
        if (maxBodyBytes < 0) throw new IllegalArgumentException("maxBodyBytes must not be negative");
        if (maxRows < 0) throw new IllegalArgumentException("maxRows must not be negative");
        if (retentionClass == null || retentionClass.isBlank()) {
            throw new IllegalArgumentException("retentionClass must not be blank");
        }

        LinkedHashSet<String> denied = new LinkedHashSet<>(REQUIRED_HEADER_DENYLIST);
        if (headerDenylist != null) {
            headerDenylist.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .forEach(denied::add);
        }
        headerDenylist = Set.copyOf(denied);
        redactPaths = redactPaths == null ? List.of() : List.copyOf(redactPaths);
        contentTypeAllowlist = contentTypeAllowlist == null
                ? Set.of()
                : contentTypeAllowlist.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Default evidence policy: minimal capture, no bodies, no headers. */
    public static final EvidencePolicy MINIMAL = new EvidencePolicy(
            false, false,
            Set.of("authorization", "cookie", "set-cookie", "proxy-authorization"),
            0, 0, List.of(), Set.of(), "session"
    );
}
