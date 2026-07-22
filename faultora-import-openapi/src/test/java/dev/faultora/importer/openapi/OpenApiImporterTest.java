package dev.faultora.importer.openapi;

import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.result.ImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiImporterTest {

    private OpenApiImporter importer;
    private ImportContext context;

    @BeforeEach
    void setUp() {
        importer = new OpenApiImporter();
        context = new ImportContext(
                "openapi-3.0",
                Path.of("."),
                Set.of(),
                10,
                1048576,
                Map.of()
        );
    }

    @Test
    void supportedTypesContainsOpenApi() {
        assertThat(importer.supportedTypes()).contains("openapi-3.0", "openapi-3.1");
    }

    @Test
    void importValidDocumentProducesCatalog() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.catalog()).isNotNull();
        assertThat(result.catalog().version()).isNotNull();
    }

    @Test
    void importExtractsOperations() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);
        ApiCatalog catalog = result.catalog();

        assertThat(catalog.operations()).isNotEmpty();

        // Should have GET /payments, POST /payments, GET /payments/{id}, DELETE /payments/{id}
        assertThat(catalog.operations()).hasSize(4);

        // Check operation IDs
        assertThat(catalog.operations().stream().map(op -> op.id().value()))
                .contains("list-payments", "create-payment", "get-payment", "delete-payment");
    }

    @Test
    void importClassifiesSafety() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);
        ApiCatalog catalog = result.catalog();

        // GET should be READ_ONLY
        OperationDefinition listPayments = findOperation(catalog, "list-payments");
        assertThat(listPayments.safety()).isEqualTo(SafetyClassification.READ_ONLY);

        // POST should be MUTATING
        OperationDefinition createPayment = findOperation(catalog, "create-payment");
        assertThat(createPayment.safety()).isEqualTo(SafetyClassification.MUTATING);

        // DELETE should be DESTRUCTIVE
        OperationDefinition deletePayment = findOperation(catalog, "delete-payment");
        assertThat(deletePayment.safety()).isEqualTo(SafetyClassification.DESTRUCTIVE);
    }

    @Test
    void importExtractsTargets() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);
        ApiCatalog catalog = result.catalog();

        assertThat(catalog.targets()).hasSize(2);
        assertThat(catalog.targets().stream().map(TargetDefinition::baseUrl))
                .contains("http://localhost:8080", "https://api.example.com");
    }

    @Test
    void importExtractsSchemas() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);
        ApiCatalog catalog = result.catalog();

        assertThat(catalog.schemas()).containsKeys(
                new SchemaId("Payment"),
                new SchemaId("CreatePaymentRequest"),
                new SchemaId("PaymentList"),
                new SchemaId("Error")
        );
    }

    @Test
    void importExtractsSecuritySchemes() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);
        ApiCatalog catalog = result.catalog();

        assertThat(catalog.authentication()).containsKey(
                new dev.faultora.model.identifier.AuthSchemeId("bearerAuth")
        );

        AuthSchemeDefinition bearer = catalog.authentication()
                .get(new dev.faultora.model.identifier.AuthSchemeId("bearerAuth"));
        assertThat(bearer.type()).isEqualTo("http");
    }

    @Test
    void importExtractsInputs() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);
        ApiCatalog catalog = result.catalog();

        OperationDefinition listPayments = findOperation(catalog, "list-payments");
        assertThat(listPayments.inputs()).containsKey("limit");
        assertThat(listPayments.inputs()).containsKey("offset");
        assertThat(listPayments.inputs().get("limit").location())
                .isEqualTo(InputDefinition.InputLocation.QUERY);

        OperationDefinition getPayment = findOperation(catalog, "get-payment");
        assertThat(getPayment.inputs()).containsKey("paymentId");
        assertThat(getPayment.inputs().get("paymentId").location())
                .isEqualTo(InputDefinition.InputLocation.PATH);
        assertThat(getPayment.inputs().get("paymentId").required()).isTrue();
    }

    @Test
    void importExtractsRequestBodySchema() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);
        ApiCatalog catalog = result.catalog();

        OperationDefinition createPayment = findOperation(catalog, "create-payment");
        assertThat(createPayment.inputs()).containsKey("body");
        assertThat(createPayment.requestSchemaId()).isNotNull();
        assertThat(createPayment.requestSchemaId().value()).isEqualTo("CreatePaymentRequest");
    }

    @Test
    void importExtractsResponseSchemas() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);
        ApiCatalog catalog = result.catalog();

        OperationDefinition createPayment = findOperation(catalog, "create-payment");
        assertThat(createPayment.outcomes()).containsKey("201");
        assertThat(createPayment.outcomes().get("201").value()).isEqualTo("Payment");
    }

    @Test
    void importProtocolMetadata() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);
        ApiCatalog catalog = result.catalog();

        OperationDefinition createPayment = findOperation(catalog, "create-payment");
        assertThat(createPayment.protocolMetadata()).containsEntry("method", "POST");
        assertThat(createPayment.protocolMetadata()).containsEntry("path", "/payments");
    }

    @Test
    void importIsIdempotent() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result1 = importer.importSource(content, context);
        ImportResult result2 = importer.importSource(content, context);

        assertThat(result1.catalog().version()).isEqualTo(result2.catalog().version());
        assertThat(result1.catalog().operations()).hasSameSizeAs(result2.catalog().operations());
    }

    @Test
    void importInvalidContentReturnsErrors() {
        ImportResult result = importer.importSource("not a valid document", context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void importNonOpenApiReturnsErrors() {
        ImportResult result = importer.importSource("some: yaml\nwithout: openapi", context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void importOversizedDocumentReturnsErrors() {
        ImportContext smallContext = new ImportContext(
                "openapi-3.0", Path.of("."), Set.of(), 10, 10, Map.of()
        );
        ImportResult result = importer.importSource("openapi: \"3.0.3\"", smallContext);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void importWithNoServersAddsDefault() {
        String content = """
                openapi: "3.0.3"
                info:
                  title: Test
                  version: "1.0.0"
                paths: {}
                """;
        ImportResult result = importer.importSource(content, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.catalog().targets()).hasSize(1);
        assertThat(result.catalog().targets().get(0).baseUrl()).isEqualTo("http://localhost:8080");
    }

    @Test
    void catalogVersionIsDigest() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);

        assertThat(result.catalog().version().value()).startsWith("sha256:");
    }

    @Test
    void allOperationsUseHttpProtocol() throws IOException {
        String content = loadFixture("valid-petstore.yaml");
        ImportResult result = importer.importSource(content, context);

        for (OperationDefinition op : result.catalog().operations()) {
            assertThat(op.protocol()).isEqualTo(new ProtocolId("http"));
        }
    }

    private OperationDefinition findOperation(ApiCatalog catalog, String operationId) {
        return catalog.operations().stream()
                .filter(op -> op.id().value().equals(operationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Operation not found: " + operationId));
    }

    private String loadFixture(String name) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/openapi/" + name)) {
            assertThat(is).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
