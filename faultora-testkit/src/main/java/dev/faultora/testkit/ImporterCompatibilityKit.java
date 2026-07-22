package dev.faultora.testkit;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.contract.SourceImporter;
import dev.faultora.spi.result.ImportResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Technology compatibility kit for SourceImporter implementations.
 */
public abstract class ImporterCompatibilityKit {

    /**
     * Provide the importer under test.
     */
    protected abstract SourceImporter createImporter();

    /**
     * Provide valid source content for testing.
     */
    protected abstract String validSourceContent();

    /**
     * The expected source type (e.g. "openapi-3.0").
     */
    protected abstract String expectedSourceType();

    @Test
    void importerDeclaresSupportedTypes() {
        SourceImporter importer = createImporter();
        assertThat(importer.supportedTypes()).isNotEmpty();
        assertThat(importer.supportedTypes()).contains(expectedSourceType());
    }

    @Test
    void importValidSourceProducesCatalog() {
        SourceImporter importer = createImporter();
        ImportContext context = createMinimalContext();
        ImportResult result = importer.importSource(validSourceContent(), context);

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.catalog()).isNotNull();
        assertThat(result.catalog().version()).isNotNull();
    }

    @Test
    void importInvalidSourceReturnsErrors() {
        SourceImporter importer = createImporter();
        ImportContext context = createMinimalContext();
        ImportResult result = importer.importSource("not valid content", context);

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void importIsIdempotent() {
        SourceImporter importer = createImporter();
        ImportContext context = createMinimalContext();
        ImportResult result1 = importer.importSource(validSourceContent(), context);
        ImportResult result2 = importer.importSource(validSourceContent(), context);

        assertThat(result1.catalog()).isEqualTo(result2.catalog());
    }

    protected ImportContext createMinimalContext() {
        return new ImportContext(
                expectedSourceType(),
                Path.of("."),
                Set.of(),
                10,
                1048576,
                Map.of()
        );
    }
}
