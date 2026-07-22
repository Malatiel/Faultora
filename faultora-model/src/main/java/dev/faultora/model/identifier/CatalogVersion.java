package dev.faultora.model.identifier;

import com.fasterxml.jackson.annotation.JsonCreator;

public final class CatalogVersion extends TypedId {
    @JsonCreator
    public CatalogVersion(String value) { super(value); }
}
