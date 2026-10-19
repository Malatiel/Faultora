package dev.faultora.importer.observations;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.result.ImportResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** What an operator's observation document becomes. */
class ObservationImporterTest {

    private static final ImportContext CONTEXT = new ImportContext(
            "observations", Path.of("."), Set.of(), 10, 1_000_000, Map.of());

    private final ObservationImporter importer = new ObservationImporter();

    private static final String LEDGER = """
            apiVersion: faultora.dev/v1alpha1
            kind: Observations

            servers:
              ledger:
                url: jdbc:postgresql://db.example.com:5432/payments
                description: The ledger database

            observations:
              ledger-entries-for:
                server: ledger
                description: The entries recorded against one payment
                sql: >-
                  SELECT account, amount FROM ledger_entries
                  WHERE payment_id = :paymentId ORDER BY id
                parameters:
                  paymentId:
                    type: string
                    required: true

              payment-count:
                sql: SELECT count(*) AS total FROM payments
            """;

    private ApiCatalog imported() {
        ImportResult result = importer.importSource(LEDGER, CONTEXT);
        assertThat(result.errors()).isEmpty();
        return result.catalog();
    }

    @Test
    void aServerBecomesATargetTheOperatorCanRedirect() {
        assertThat(imported().targets()).singleElement().satisfies(target -> {
            assertThat(target.id()).isEqualTo(new TargetId("ledger"));
            assertThat(target.baseUrl())
                    .isEqualTo("jdbc:postgresql://db.example.com:5432/payments");
            assertThat(target.protocols()).extracting(protocol -> protocol.value())
                    .containsExactly("jdbc");
        });
    }

    @Test
    void anObservationCarriesItsStatementAndItsParameters() {
        var observation = imported().operations().stream()
                .filter(candidate -> candidate.id().equals(
                        new OperationId("ledger-entries-for")))
                .findFirst().orElseThrow();

        assertThat(observation.protocolMetadata().get(ObservationImporter.SQL).toString())
                .contains("SELECT account, amount", ":paymentId");
        assertThat(observation.protocolMetadata().get(ObservationImporter.PARAMETERS))
                .isEqualTo(List.of("paymentId"));
        assertThat(observation.inputs()).containsOnlyKeys("paymentId");
        assertThat(observation.target()).isEqualTo(new TargetId("ledger"));
    }

    @Test
    void everyObservationIsReadOnly() {
        // True only because the connector refuses anything that is not a single
        // reading statement: a document cannot be trusted to classify its own
        // SQL honestly when the SQL is right there in it.
        assertThat(imported().operations())
                .allSatisfy(observation -> assertThat(observation.safety())
                        .isEqualTo(SafetyClassification.READ_ONLY));
    }

    @Test
    void anObservationWithoutAServerUsesTheOnlyOneDeclared() {
        var observation = imported().operations().stream()
                .filter(candidate -> candidate.id().equals(new OperationId("payment-count")))
                .findFirst().orElseThrow();

        assertThat(observation.target()).isEqualTo(new TargetId("ledger"));
        assertThat(observation.inputs()).isEmpty();
    }

    @Test
    void anObservationNamingAnUndeclaredServerIsReportedAndSkipped() {
        String document = """
                apiVersion: faultora.dev/v1alpha1
                kind: Observations
                servers:
                  ledger: { url: "jdbc:h2:mem:test" }
                observations:
                  elsewhere:
                    server: warehouse
                    sql: SELECT 1
                """;

        ImportResult result = importer.importSource(document, CONTEXT);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.catalog().operations()).isEmpty();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("warehouse"));
    }

    @Test
    void aDocumentWithoutAServerIsRefused() {
        ImportResult result = importer.importSource("""
                apiVersion: faultora.dev/v1alpha1
                kind: Observations
                observations:
                  anything: { sql: SELECT 1 }
                """, CONTEXT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors().get(0).code()).isEqualTo("NO_SERVER");
    }

    @Test
    void aDocumentOfAnotherKindIsRefusedByName() {
        ImportResult result = importer.importSource("""
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata: { name: not-observations }
                """, CONTEXT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors().get(0).message()).contains("Scenario", "Observations");
    }

    private static final String SERVER_AND_ONE_OBSERVATION = """
            kind: Observations
            servers:
              ledger: { url: "jdbc:h2:mem:test" }
            observations:
              anything: { sql: SELECT 1 }
            """;

    private ImportResult declaring(String apiVersionLine) {
        return importer.importSource(apiVersionLine + "\n" + SERVER_AND_ONE_OBSERVATION, CONTEXT);
    }

    @Test
    void theCurrentVersionImportsWithoutAVersionWarning() {
        ImportResult result = declaring("apiVersion: faultora.dev/v1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.catalog().operations()).hasSize(1);
        assertThat(result.warnings()).noneMatch(warning -> warning.contains("apiVersion")
                || warning.contains("deprecated"));
    }

    @Test
    void thePreviewVersionStillImportsAndSaysWhenItStops() {
        ImportResult result = declaring("apiVersion: faultora.dev/v1alpha1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.catalog().operations()).hasSize(1);
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("faultora.dev/v1alpha1", "deprecated", "2.0", "faultora.dev/v1",
                        "faultora migrate"));
    }

    @Test
    void anUnknownVersionIsRefusedBeforeACatalogExists() {
        ImportResult result = declaring("apiVersion: faultora.dev/v99");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.catalog()).isNull();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("UNSUPPORTED_API_VERSION");
            assertThat(error.message())
                    .contains("faultora.dev/v99", "faultora.dev/v1", "faultora.dev/v1alpha1");
        });
    }

    @Test
    void aVersionThatIsNotAStringIsRefusedAsUnsupported() {
        ImportResult result = declaring("apiVersion: 1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors().get(0).code()).isEqualTo("UNSUPPORTED_API_VERSION");
    }

    @Test
    void aDocumentThatDeclaresNoVersionIsRefused() {
        // Silence is not a version: a catalog that says nothing cannot be held
        // to any contract, and guessing one would be the very drift a version
        // exists to prevent.
        ImportResult result = importer.importSource(SERVER_AND_ONE_OBSERVATION, CONTEXT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.catalog()).isNull();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("MISSING_API_VERSION");
            assertThat(error.message()).contains("apiVersion: faultora.dev/v1");
        });
    }

    @Test
    void theImporterAnswersToTheObservationsFamily() {
        assertThat(importer.supportedTypes()).containsExactly("observations-v1");
    }
}
