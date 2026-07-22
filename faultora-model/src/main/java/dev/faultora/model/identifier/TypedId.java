package dev.faultora.model.identifier;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Opaque typed identifier with a stable string value.
 * Subclasses provide type-safety without runtime overhead.
 */
public abstract sealed class TypedId implements Comparable<TypedId> permits
        ProtocolId, OperationId, TargetId, SchemaId,
        AuthSchemeId, WorkflowId, RunId, NodeId, CatalogVersion {

    private static final Pattern SAFE_ID = Pattern.compile("^[a-zA-Z0-9._:/@-]{1,256}$");

    private final String value;

    protected TypedId(String value) {
        Objects.requireNonNull(value, "identifier value must not be null");
        if (!SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Identifier contains disallowed characters or exceeds 256 chars: " + value);
        }
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TypedId typedId = (TypedId) o;
        return value.equals(typedId.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public int compareTo(TypedId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + value + "]";
    }
}
