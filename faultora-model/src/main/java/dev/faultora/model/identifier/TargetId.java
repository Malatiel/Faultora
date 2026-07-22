package dev.faultora.model.identifier;

import com.fasterxml.jackson.annotation.JsonCreator;

public final class TargetId extends TypedId {
    @JsonCreator
    public TargetId(String value) { super(value); }
}
