package dev.faultora.model.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultora.model.identifier.AuthSchemeId;

import java.util.Map;

/**
 * Describes an authentication scheme from the source specification.
 *
 * @param id       stable scheme identifier
 * @param type     scheme type (apiKey, http, oauth2, openIdConnect)
 * @param name     name of the credential (header name, query param, etc.)
 * @param location where the credential is placed (header, query, cookie)
 * @param metadata scheme-specific metadata from the source
 */
public record AuthSchemeDefinition(
        AuthSchemeId id,
        String type,
        String name,
        String location,
        Map<String, Object> metadata
) {}
