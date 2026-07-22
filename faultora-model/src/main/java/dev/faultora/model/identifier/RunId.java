package dev.faultora.model.identifier;

import com.fasterxml.jackson.annotation.JsonCreator;

public final class RunId extends TypedId {
    @JsonCreator
    public RunId(String value) { super(value); }
}
