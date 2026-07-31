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

    @Test
    void theImporterAnswersToTheObservationsFamily() {
        assertThat(importer.supportedTypes()).containsExactly("observations-v1");
    }
}
