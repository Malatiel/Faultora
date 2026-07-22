package dev.faultora.spi.contract;

import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.result.ImportResult;

import java.util.Set;

/**
 * Imports API descriptions from various source formats into the canonical catalog.
 * Implementations are discovered via Java SPI ServiceLoader.
 */
public interface SourceImporter {

    /**
     * Source types this importer supports (e.g. "openapi-3.0", "openapi-3.1", "asyncapi").
     */
    Set<String> supportedTypes();

    /**
     * Import a source document into the canonical catalog.
     *
     * @param sourceContent the raw source content (YAML or JSON)
     * @param context       import context with workspace, policy, and configuration
     * @return import result containing the catalog or errors
     */
    ImportResult importSource(String sourceContent, ImportContext context);
}
