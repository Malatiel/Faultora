package dev.faultora.model.identifier;

import com.fasterxml.jackson.annotation.JsonCreator;

public final class NodeId extends TypedId {
    @JsonCreator
    public NodeId(String value) { super(value); }
}
