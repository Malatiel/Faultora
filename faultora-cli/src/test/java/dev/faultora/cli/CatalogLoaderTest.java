package dev.faultora.cli;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.spec.model.ScenarioDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a run compiles against when it was given more than one description.
 * <p>
 * One document was the only case any suite covered, and it was the case that
 * worked: the union of two carried a version nothing could hold, so a scenario
 * spanning an API and a broker failed before it started. These tests are about
 * the union.
 */
class CatalogLoaderTest {

    private static final String OPENAPI = """
            openapi: "3.0.3"
            info:
              title: Payments
              version: "1.0.0"
            servers:
              - url: http://localhost:8080
            paths:
              /payments:
                post:
                  operationId: create-payment
                  responses:
                    "201":
                      description: created
            """;

    private static final String ASYNCAPI = """
            asyncapi: 3.0.0
            info:
              title: Payment Events
              version: "1.0.0"
            servers:
              broker:
                host: localhost:9092
                protocol: kafka
            channels:
              paymentEvents:
                address: payment-events
            operations:
              paymentSettled:
                action: send
                channel:
                  $ref: '#/channels/paymentEvents'
            """;

    @TempDir
    Path documents;

    private ApiCatalog load(String openApi, String asyncApi) throws IOException {
        Path openApiPath = null;
        Path asyncApiPath = null;
        if (openApi != null) {
            openApiPath = documents.resolve("openapi.yaml");
            Files.writeString(openApiPath, openApi);
        }
        if (asyncApi != null) {
            asyncApiPath = documents.resolve("asyncapi.yaml");
            Files.writeString(asyncApiPath, asyncApi);
        }
        TestOptions options = TestOptions.parse(
                List.of(arguments(openApiPath, asyncApiPath)));
        return CatalogLoader.load(options, scenario(), RunPolicies.extensionPolicy(options));
    }

    private static String[] arguments(Path openApi, Path asyncApi) {
        if (openApi != null && asyncApi != null) {
            return new String[]{"--scenario", "unused.yaml",
                    "--openapi", openApi.toString(), "--asyncapi", asyncApi.toString()};
        }
        return new String[]{"--scenario", "unused.yaml", "--openapi",
                (openApi == null ? asyncApi : openApi).toString()};
    }

    private static ScenarioDocument scenario() {
        return null;
    }

    @Test
    void twoDescriptionsBecomeOneCatalogWithAVersionThatIsAnIdentifier() throws IOException {
        // A digest joined to a digest is neither bounded nor allowed, and a run
        // that imported an API beside a broker used to fail before it started.
        ApiCatalog merged = load(OPENAPI, ASYNCAPI);

        assertThat(merged.version().value()).startsWith("sha256:");
        assertThat(merged.operations()).extracting(operation -> operation.id().value())
                .contains("create-payment", "paymentSettled");
    }

    @Test
    void theVersionChangesWhenEitherDocumentDoes() throws IOException {
        String version = load(OPENAPI, ASYNCAPI).version().value();
        String withAnotherPath = load(
                OPENAPI.replace("/payments", "/transfers"), ASYNCAPI).version().value();
        String withAnotherChannel = load(
                OPENAPI, ASYNCAPI.replace("payment-events", "transfer-events")).version().value();

        assertThat(withAnotherPath).isNotEqualTo(version);
        assertThat(withAnotherChannel).isNotEqualTo(version);
    }

    @Test
    void oneDescriptionKeepsItsOwnDigest() throws IOException {
        // A reader of a single-source run journal expects the document's digest,
        // not a digest over a list of one.
        ApiCatalog single = load(OPENAPI, null);

        assertThat(single.version().value()).startsWith("sha256:");
        assertThat(load(OPENAPI, ASYNCAPI).version().value())
                .isNotEqualTo(single.version().value());
    }
}
