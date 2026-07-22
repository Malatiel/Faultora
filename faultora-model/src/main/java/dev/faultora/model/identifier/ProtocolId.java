package dev.faultora.model.identifier;

import com.fasterxml.jackson.annotation.JsonCreator;

public final class ProtocolId extends TypedId {
    @JsonCreator
    public ProtocolId(String value) { super(value); }
}
