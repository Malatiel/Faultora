package dev.faultora.model.security;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * An opaque handle to a secret value.
 * The actual secret is never exposed through toString, serialization, logs, or diagnostics.
 *
 * @param handleId     unique identifier for this handle (non-sensitive)
 * @param redacted     redacted representation safe for display (e.g. "sk-***4a2f")
 * @param sourceType   how the secret was resolved (env, file, vault, etc.)
 * @param expiresAt    epoch millis when this handle expires, -1 if no expiry
 */
public record SecretHandle(
        String handleId,
        String redacted,
        String sourceType,
        long expiresAt
) {
    public SecretHandle {
        Objects.requireNonNull(handleId, "handleId must not be null");
        Objects.requireNonNull(redacted, "redacted must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
    }

    /**
     * Returns true if this handle has expired.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    @Override
    public String toString() {
        return "SecretHandle[" + handleId + ", redacted=" + redacted + "]";
    }
}
