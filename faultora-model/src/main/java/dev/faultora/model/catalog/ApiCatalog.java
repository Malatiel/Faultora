package dev.faultora.model.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultora.model.identifier.AuthSchemeId;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.SchemaId;

import java.util.List;
import java.util.Map;

/**
 * Top-level canonical API catalog produced by importers.
 * Independent of the source specification format (OpenAPI, AsyncAPI, etc.).
 *
 * @param version        catalog version (deterministic from content digest)
 * @param targets        imported target definitions
 * @param operations     imported operation definitions
 * @param schemas        imported schema definitions, keyed by schema ID
 * @param authentication imported authentication schemes
 * @param workflows      imported workflow definitions
 */
public record ApiCatalog(
        CatalogVersion version,
        List<TargetDefinition> targets,
        List<OperationDefinition> operations,
        Map<SchemaId, DataSchema> schemas,
        Map<AuthSchemeId, AuthSchemeDefinition> authentication,
        List<WorkflowDefinition> workflows
) {}
