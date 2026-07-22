package dev.faultora.model.identifier;

import com.fasterxml.jackson.annotation.JsonCreator;

public final class OperationId extends TypedId {
    @JsonCreator
    public OperationId(String value) { super(value); }
}
