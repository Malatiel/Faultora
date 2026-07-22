package dev.faultora.model.identifier;

import com.fasterxml.jackson.annotation.JsonCreator;

public final class SchemaId extends TypedId {
    @JsonCreator
    public SchemaId(String value) { super(value); }
}
