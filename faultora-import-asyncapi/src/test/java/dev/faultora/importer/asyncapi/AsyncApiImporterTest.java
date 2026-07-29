package dev.faultora.importer.asyncapi;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.result.ImportResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** What an AsyncAPI description becomes, and in which direction. */
class AsyncApiImporterTest {

    private static final ImportContext CONTEXT = new ImportContext(
            "asyncapi", Path.of("."), Set.of(), 10, 1_000_000, Map.of());

    private final AsyncApiImporter importer = new AsyncApiImporter();

    private static final String PAYMENTS = """
            asyncapi: 3.0.0
            info:
              title: Payments
              version: "1.0.0"
            servers:
              broker:
                host: kafka.example.com:9092
                protocol: kafka
            channels:
              paymentCommands:
                address: payment-commands
                messages:
                  settlePayment:
                    $ref: '#/components/messages/SettlePayment'
              paymentEvents:
                address: payment-events
                messages:
                  paymentSettled:
                    $ref: '#/components/messages/PaymentSettled'
            operations:
              settlePayment:
                action: receive
                channel:
                  $ref: '#/channels/paymentCommands'
              paymentSettled:
                action: send
                channel:
                  $ref: '#/channels/paymentEvents'
            components:
              messages:
                SettlePayment:
                  correlationId:
                    location: $message.header#/correlation-id
                  payload:
                    $ref: '#/components/schemas/SettleCommand'
                PaymentSettled:
                  payload:
                    $ref: '#/components/schemas/SettledEvent'
              schemas:
                SettleCommand:
                  type: object
                  required: [paymentId, amount]
                  properties:
                    paymentId: { type: string }
                    amount: { type: integer, minimum: 1 }
                SettledEvent:
                  type: object
                  properties:
                    paymentId: { type: string }
                    status: { type: string }
            """;

    private ApiCatalog imported() {
        ImportResult result = importer.importSource(PAYMENTS, CONTEXT);
        assertThat(result.errors()).isEmpty();
        assertThat(result.catalog()).isNotNull();
        return result.catalog();
    }

    private OperationDefinition operation(ApiCatalog catalog, String id) {
        return catalog.operations().stream()
                .filter(candidate -> candidate.id().equals(new OperationId(id)))
                .findFirst().orElseThrow();
    }

    @Test
    void theApplicationsReceiveIsSomethingTheRunPublishesTo() {
        // AsyncAPI 3.0 states the action from the application's side. Reading
        // it as the run's own would reverse every operation in the document.
        OperationDefinition settle = operation(imported(), "settlePayment");

        assertThat(settle.protocolMetadata()).containsEntry("action", "publish");
        assertThat(settle.protocolMetadata()).containsEntry("topic", "payment-commands");
    }

    @Test
    void theApplicationsSendIsSomethingTheRunConsumes() {
        OperationDefinition settled = operation(imported(), "paymentSettled");

        assertThat(settled.protocolMetadata()).containsEntry("action", "consume");
        assertThat(settled.protocolMetadata()).containsEntry("topic", "payment-events");
    }

    @Test
    void whatTheRunWritesIsMutatingAndWhatItWatchesIsNot() {
        // This is why the direction has to be right: a write classified as a
        // read passes the execution policy without being asked for.
        ApiCatalog catalog = imported();

        assertThat(operation(catalog, "settlePayment").safety())
                .isEqualTo(SafetyClassification.MUTATING);
        assertThat(operation(catalog, "paymentSettled").safety())
                .isEqualTo(SafetyClassification.READ_ONLY);
    }

    @Test
    void aPublishedPayloadBecomesARequestSchemaSoItCanBeGenerated() {
        ApiCatalog catalog = imported();

        assertThat(operation(catalog, "settlePayment").requestSchemaId())
                .isEqualTo(new SchemaId("SettleCommand"));
        assertThat(operation(catalog, "paymentSettled").requestSchemaId()).isNull();
        assertThat(operation(catalog, "paymentSettled").outcomes())
                .containsEntry("message", new SchemaId("SettledEvent"));
        assertThat(catalog.schemas()).containsKey(new SchemaId("SettleCommand"));
    }

    @Test
    void aCorrelationLocationSurvivesIntoTheCatalog() {
        assertThat(operation(imported(), "settlePayment").protocolMetadata())
                .containsEntry("correlationId", "$message.header#/correlation-id");
    }

    @Test
    void aKafkaServerBecomesATargetTheConnectorCanReach() {
        assertThat(imported().targets()).singleElement().satisfies(target -> {
            assertThat(target.baseUrl()).isEqualTo("kafka://kafka.example.com:9092");
            assertThat(target.protocols()).extracting(protocol -> protocol.value())
                    .containsExactly("kafka");
        });
    }

    @Test
    void aChannelsKafkaBindingNamesTheTopicWhenTheAddressDoesNot() {
        String document = """
                asyncapi: 3.0.0
                info: { title: T, version: "1" }
                servers:
                  broker: { host: kafka:9092, protocol: kafka }
                channels:
                  readable:
                    bindings:
                      kafka:
                        topic: internal.payments.v2
                operations:
                  watch:
                    action: send
                    channel:
                      $ref: '#/channels/readable'
                """;

        ImportResult result = importer.importSource(document, CONTEXT);

        assertThat(result.catalog().operations()).singleElement()
                .satisfies(operation -> assertThat(operation.protocolMetadata())
                        .containsEntry("topic", "internal.payments.v2"));
    }

    @Test
    void anInlinePayloadIsGivenANameSoTheCatalogStandsAlone() {
        String document = """
                asyncapi: 3.0.0
                info: { title: T, version: "1" }
                servers:
                  broker: { host: kafka:9092, protocol: kafka }
                channels:
                  commands:
                    address: commands
                    messages:
                      command:
                        payload:
                          type: object
                          properties:
                            id: { type: string }
                operations:
                  send:
                    action: receive
                    channel:
                      $ref: '#/channels/commands'
                """;

        ImportResult result = importer.importSource(document, CONTEXT);

        SchemaId inlined = new SchemaId("send-payload");
        assertThat(result.catalog().schemas()).containsKey(inlined);
        assertThat(result.catalog().operations().get(0).requestSchemaId()).isEqualTo(inlined);
    }

    @Test
    void aServerOfAnotherProtocolIsReportedRatherThanDiscarding() {
        // A description usually covers more than the part a test needs. Losing
        // the whole catalog over one MQTT server would be the wrong trade.
        String document = """
                asyncapi: 3.0.0
                info: { title: T, version: "1" }
                servers:
                  broker: { host: kafka:9092, protocol: kafka }
                  devices: { host: mqtt.example.com:1883, protocol: mqtt }
                channels:
                  events:
                    address: events
                operations:
                  watch:
                    action: send
                    channel:
                      $ref: '#/channels/events'
                """;

        ImportResult result = importer.importSource(document, CONTEXT);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("mqtt"));
        assertThat(result.catalog().targets()).hasSize(1);
        assertThat(result.catalog().operations()).hasSize(1);
    }

    @Test
    void anAsyncApi2DocumentIsRefusedByNameRatherThanReversed() {
        String legacy = """
                asyncapi: 2.6.0
                info: { title: T, version: "1" }
                channels:
                  payments:
                    publish:
                      operationId: sendPayment
                """;

        ImportResult result = importer.importSource(legacy, CONTEXT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("UNSUPPORTED_VERSION");
            assertThat(error.message()).contains("2.6.0", "reverse every operation");
        });
    }

    @Test
    void aDocumentThatIsNotAsyncApiIsRefused() {
        ImportResult result = importer.importSource(
                "openapi: 3.0.3\ninfo: { title: T, version: \"1\" }\n", CONTEXT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors().get(0).code()).isEqualTo("NOT_ASYNCAPI");
    }

    @Test
    void aReferenceLeavingTheDocumentIsRefusedRatherThanFetched() {
        String document = """
                asyncapi: 3.0.0
                info: { title: T, version: "1" }
                servers:
                  broker: { host: kafka:9092, protocol: kafka }
                channels:
                  events:
                    address: events
                operations:
                  watch:
                    action: send
                    channel:
                      $ref: 'https://example.com/channels.yaml#/events'
                """;

        ImportResult result = importer.importSource(document, CONTEXT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors().get(0).message()).contains("outside this document");
    }

    @Test
    void theImporterAnswersToTheAsyncApiFamily() {
        assertThat(importer.supportedTypes()).containsExactly("asyncapi-3.0");
    }
}
