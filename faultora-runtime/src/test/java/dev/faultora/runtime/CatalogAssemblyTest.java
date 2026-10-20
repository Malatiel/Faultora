package dev.faultora.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the importers say about a catalog they accepted.
 * <p>
 * The warnings used to stop at the importer: a skipped server or a deprecated
 * observation version imported cleanly and nobody was told. Every caller now
 * gets them beside the catalog.
 */
class CatalogAssemblyTest {
    private static final ExtensionPolicy EXTENSIONS =
            new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());
    private static final TargetPolicy POLICY = new TargetPolicy(
            Set.of(), Set.of(SafetyClassification.READ_ONLY), 50, 2, 10_000, 1024, Set.of(), Set.of());

    private static final String PREVIEW_OBSERVATIONS = """
            apiVersion: faultora.dev/v1alpha1
            kind: Observations
            servers:
              ledger: { url: "jdbc:h2:mem:catalog-warnings" }
            observations:
              entries: { server: ledger, sql: SELECT 1 }
            """;

    private static final String HOSTLESS_SERVER = """
            asyncapi: 3.0.0
            info: { title: Payments, version: 1.0.0 }
            servers:
              legacy: { protocol: kafka }
            """;

    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata: {name: catalog-warnings}
            execute:
              - id: wait
                type: wait
                timeout: 1ms
            """;

    @Test
    void aDeprecatedObservationVersionImportsAndSaysSo() {
        var assembled = CatalogAssembly.assemble(
                List.of(new CatalogAssembly.Document("observations", PREVIEW_OBSERVATIONS)), EXTENSIONS);

        assertThat(assembled.catalog().operations()).hasSize(1);
        assertThat(assembled.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.family()).isEqualTo("observations");
            assertThat(warning.message()).contains("faultora.dev/v1alpha1", "deprecated");
        });
    }

    @Test
    void aSkippedServerIsReportedAgainstTheFamilyThatSkippedIt() {
        var assembled = CatalogAssembly.assemble(
                List.of(new CatalogAssembly.Document("asyncapi", HOSTLESS_SERVER)), EXTENSIONS);

        assertThat(assembled.warnings())
                .allSatisfy(warning -> assertThat(warning.family()).isEqualTo("asyncapi"))
                .anySatisfy(warning -> assertThat(warning.message())
                        .isEqualTo("Server 'legacy' names no host and was skipped"));
    }

    @Test
    void mergedDocumentsKeepEveryWarningInTheOrderTheDocumentsWereNamed() {
        var assembled = CatalogAssembly.assemble(List.of(
                new CatalogAssembly.Document("observations", PREVIEW_OBSERVATIONS),
                new CatalogAssembly.Document("asyncapi", HOSTLESS_SERVER)), EXTENSIONS);

        assertThat(assembled.warnings()).extracting(CatalogAssembly.Warning::family)
                .startsWith("observations", "asyncapi").containsOnly("observations", "asyncapi");
    }

    @Test
    void preparationCarriesTheWarningsAndThePreviewOnlyNamesTheFamily() throws Exception {
        var prepared = RunPreparation.prepare(new RunPreparation.Request(SCENARIO,
                List.of(new CatalogAssembly.Document("asyncapi", HOSTLESS_SERVER)), "",
                EXTENSIONS, POLICY, new RunId("run-catalog-warnings"), 1, null));

        assertThat(prepared.isSuccess()).as(() -> prepared.diagnostics().toString()).isTrue();
        assertThat(prepared.catalogWarnings()).anySatisfy(warning ->
                assertThat(warning.message()).contains("legacy"));

        var preview = PlanPreview.describe(prepared, RuntimeCapabilities.describe(EXTENSIONS, POLICY, Set.of()), POLICY);
        assertThat(preview.compiled()).isTrue();
        assertThat(preview.diagnostics()).contains(new PlanPreview.Diagnostic(
                "WARNING", "catalog", null, "asyncapi", "Catalog import warning"));
        // The preview leaves the document's own names out, as it does for
        // scenario diagnostics: an assistant reading it was not given the document.
        assertThat(new ObjectMapper().writeValueAsString(preview)).doesNotContain("legacy");
    }

    @Test
    void aRunWithoutDocumentsHasNoCatalogWarnings() {
        var prepared = RunPreparation.prepare(new RunPreparation.Request(SCENARIO, List.of(),
                "http://api.example.com", EXTENSIONS, POLICY, new RunId("run-no-documents"), 1, null));

        assertThat(prepared.catalogWarnings()).isEmpty();
    }
}
