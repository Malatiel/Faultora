package dev.faultora.model.identifier;

import com.fasterxml.jackson.annotation.JsonCreator;

public final class AuthSchemeId extends TypedId {
    @JsonCreator
    public AuthSchemeId(String value) { super(value); }
}
