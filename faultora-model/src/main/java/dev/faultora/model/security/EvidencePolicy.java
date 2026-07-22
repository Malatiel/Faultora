package dev.faultora.model.security;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
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
    /** Default evidence policy: minimal capture, no bodies, no headers. */
    public static final EvidencePolicy MINIMAL = new EvidencePolicy(
            false, false,
            Set.of("authorization", "cookie", "set-cookie", "proxy-authorization"),
            0, 0, List.of(), Set.of(), "session"
    );
}
