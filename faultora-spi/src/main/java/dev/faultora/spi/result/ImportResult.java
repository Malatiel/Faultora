package dev.faultora.spi.result;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.NormalizedError;

import java.util.List;
import java.util.Map;

/**
 * Result of importing a source document into the canonical catalog.
 *
 * @param catalog    the imported catalog (null if import failed)
 * @param errors     import errors (empty if successful)
 * @param warnings   non-fatal warnings
 * @param metadata   importer-specific metadata
 */
public record ImportResult(
        ApiCatalog catalog,
        List<NormalizedError> errors,
        List<String> warnings,
        Map<String, Object> metadata
) {
    public boolean isSuccess() {
        return catalog != null && errors.isEmpty();
    }

    public static ImportResult success(ApiCatalog catalog, List<String> warnings, Map<String, Object> metadata) {
        return new ImportResult(catalog, List.of(), warnings, metadata);
    }

    public static ImportResult failure(List<NormalizedError> errors) {
        return new ImportResult(null, errors, List.of(), Map.of());
    }
}
