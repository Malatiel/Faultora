package dev.faultora.spi.context;

import dev.faultora.model.identifier.SchemaId;

import java.util.Map;
import java.util.Set;

/**
 * Context provided to source importers.
 *
 * @param sourceType        type of the source document (openapi, asyncapi, etc.)
 * @param workspaceRoot     root of the workspace for resolving local references
 * @param allowedRefDomains domains allowed for external references (empty = none)
 * @param maxRefDepth       maximum reference resolution depth
 * @param maxDocSizeBytes   maximum document size
 * @param config            importer-specific configuration
 */
public record ImportContext(
        String sourceType,
        java.nio.file.Path workspaceRoot,
        Set<String> allowedRefDomains,
        int maxRefDepth,
        long maxDocSizeBytes,
        Map<String, Object> config
) {}
