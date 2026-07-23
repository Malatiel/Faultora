package dev.faultora.model.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * An opaque handle to a secret value.
 * The actual secret is never exposed through toString, serialization, logs, or diagnostics.
 * <p>
 * The raw secret value is accessible only via {@link #secretValue()} which returns a
 * defensive copy of the char array. The caller must zero-fill the returned array after use.
 */
public final class SecretHandle {

    private final String handleId;
    private final String redacted;
    private final String sourceType;
    private final long expiresAt;

    /**
     * Supplier that provides the actual secret value on demand.
     * Transient — never serialized or included in toString/equals/hashCode.
     */
    private final Supplier<char[]> valueSupplier;

    /**
     * Create a SecretHandle with no value supplier (value-less handle for availability checks).
     */
    public SecretHandle(
            @JsonProperty("handleId") String handleId,
            @JsonProperty("redacted") String redacted,
            @JsonProperty("sourceType") String sourceType,
            @JsonProperty("expiresAt") long expiresAt
    ) {
        this(handleId, redacted, sourceType, expiresAt, null);
    }

    /**
     * Create a SecretHandle with a value supplier.
     *
     * @param handleId      unique identifier for this handle (non-sensitive)
     * @param redacted      redacted representation safe for display (e.g. "sk-***4a2f")
     * @param sourceType    how the secret was resolved (env, file, vault, etc.)
     * @param expiresAt     epoch millis when this handle expires, -1 if no expiry
     * @param valueSupplier supplier that returns the raw secret value (may be null)
     */
    public SecretHandle(
            String handleId,
            String redacted,
            String sourceType,
            long expiresAt,
            Supplier<char[]> valueSupplier
    ) {
        this.handleId = Objects.requireNonNull(handleId, "handleId must not be null");
        this.redacted = Objects.requireNonNull(redacted, "redacted must not be null");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        this.expiresAt = expiresAt;
        this.valueSupplier = valueSupplier;
    }

    @JsonProperty
    public String handleId() {
        return handleId;
    }

    @JsonProperty
    public String redacted() {
        return redacted;
    }

    @JsonProperty
    public String sourceType() {
        return sourceType;
    }

    @JsonProperty
    public long expiresAt() {
        return expiresAt;
    }

    /**
     * Returns true if this handle has expired.
     */
    @JsonIgnore
    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    /**
     * Returns the raw secret value as a defensive copy.
     * The caller must zero-fill the returned array after use.
     * Returns null if no value supplier was provided.
     */
    @JsonIgnore
    public char[] secretValue() {
        if (valueSupplier == null) return null;
        char[] value = valueSupplier.get();
        if (value == null) return null;
        try {
            // Return a defensive copy — caller can't corrupt the supplier's value.
            return Arrays.copyOf(value, value.length);
        } finally {
            // The supplier contract is scoped: its temporary value is destroyed here.
            Arrays.fill(value, '\0');
        }
    }

    @Override
    public String toString() {
        return "SecretHandle[" + handleId + ", redacted=" + redacted + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SecretHandle other)) return false;
        return Objects.equals(handleId, other.handleId)
                && Objects.equals(redacted, other.redacted)
                && Objects.equals(sourceType, other.sourceType)
                && expiresAt == other.expiresAt;
        // valueSupplier is intentionally excluded from equals
    }

    @Override
    public int hashCode() {
        return Objects.hash(handleId, redacted, sourceType, expiresAt);
        // valueSupplier is intentionally excluded from hashCode
    }
}
