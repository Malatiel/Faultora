package dev.faultora.model.identifier;

import com.fasterxml.jackson.annotation.JsonCreator;

public final class WorkflowId extends TypedId {
    @JsonCreator
    public WorkflowId(String value) { super(value); }
}
