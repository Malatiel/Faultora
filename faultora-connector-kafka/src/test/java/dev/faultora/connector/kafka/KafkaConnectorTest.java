package dev.faultora.connector.kafka;

import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.net.DestinationPolicyViolation;
import dev.faultora.net.HostPolicy;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.evidence.MessageEvidence;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the connector decides before a byte reaches a broker. */
class KafkaConnectorTest {

    private static final String TOPIC = KafkaFixtures.TOPIC;

    /** The fixture target is local, so these tests run under a policy that allows it. */
    private static KafkaConnector connectorOver(FakeKafka broker) {
        return new KafkaConnector(HostPolicy.permissive(), broker);
    }

    @Test
    void aPublishedMessageReportsWhereItLanded() {
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0);
        try (KafkaConnector connector = connectorOver(broker)) {
            Connector.PreparedTarget target =
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context());

            OperationResult result = connector.execute(
                    target, KafkaFixtures.publish(TOPIC),
                    Map.of("key", "pay-1", "body", Map.of("paymentId", "pay-1")),
                    KafkaFixtures.context());

            assertThat(result.isSuccess()).isTrue();
            MessageEvidence published =
                    MessageEvidence.publishedIn(result.protocolEvidence());
            assertThat(published).isNotNull();
            assertThat(published.topic()).isEqualTo(TOPIC);
            assertThat(published.key()).isEqualTo("pay-1");
            assertThat(published.digest()).startsWith("sha256:");
            assertThat(published.payload().get("paymentId").asText()).isEqualTo("pay-1");
            assertThat(broker.published().history()).hasSize(1);
        }
    }

    @Test
    void anObservationSelectsOnlyTheMessagesTheStepIsAbout() {
        // Determinism comes from selection, not position: two runs, or two
        // iterations of one repeat block, each pick out their own messages.
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0)
                .message(TOPIC, 0, "pay-1", "{\"paymentId\":\"pay-1\",\"status\":\"settled\"}")
                .message(TOPIC, 1, "pay-2", "{\"paymentId\":\"pay-2\",\"status\":\"settled\"}")
                .message(TOPIC, 2, "pay-3", "{\"paymentId\":\"pay-3\",\"status\":\"settled\"}");

        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult result = connector.execute(
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context()),
                    KafkaFixtures.consume(TOPIC),
                    Map.of("match", Map.of("payload", Map.of("paymentId", "pay-2")),
                            "waitMs", 0),
                    KafkaFixtures.context());

            List<MessageEvidence> messages =
                    MessageEvidence.observedIn(result.protocolEvidence());
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).key()).isEqualTo("pay-2");
            assertThat(result.protocolEvidence().get("observed")).isEqualTo(3L);
            assertThat(result.protocolEvidence().get("selective")).isEqualTo(true);
        }
    }

    @Test
    void aSelectorMatchesOnKeysAndHeadersToo() {
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0)
                .message(TOPIC, 0, "pay-1", "{}", Map.of("correlation-id", "abc"))
                .message(TOPIC, 1, "pay-2", "{}", Map.of("correlation-id", "xyz"));

        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult byHeader = connector.execute(
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context()),
                    KafkaFixtures.consume(TOPIC),
                    Map.of("match", Map.of("headers", Map.of("correlation-id", "xyz")),
                            "waitMs", 0),
                    KafkaFixtures.context());

            assertThat(MessageEvidence.observedIn(byHeader.protocolEvidence()))
                    .singleElement()
                    .satisfies(message -> assertThat(message.key()).isEqualTo("pay-2"));
        }
    }

    @Test
    void anObservationDoesNotReachBelowTheFloorTheRunArrivedAt() {
        // Two messages predate the run. An observation must not report them:
        // a scenario asserting "one event" would otherwise fail on history it
        // had nothing to do with.
        FakeKafka broker = new FakeKafka().topic(TOPIC, 2)
                .message(TOPIC, 0, "old-1", "{\"paymentId\":\"old-1\"}")
                .message(TOPIC, 1, "old-2", "{\"paymentId\":\"old-2\"}")
                .message(TOPIC, 2, "new-1", "{\"paymentId\":\"new-1\"}");

        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult result = connector.execute(
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context()),
                    KafkaFixtures.consume(TOPIC),
                    Map.of("waitMs", 0), KafkaFixtures.context());

            assertThat(MessageEvidence.observedIn(result.protocolEvidence()))
                    .singleElement()
                    .satisfies(message -> assertThat(message.key()).isEqualTo("new-1"));
        }
    }

    @Test
    void anObservationCanBeAskedToReachBackToTheBeginning() {
        FakeKafka broker = new FakeKafka().topic(TOPIC, 2)
                .message(TOPIC, 0, "old-1", "{}")
                .message(TOPIC, 1, "old-2", "{}");

        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult result = connector.execute(
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context()),
                    KafkaFixtures.consume(TOPIC),
                    Map.of("from", "beginning", "waitMs", 0), KafkaFixtures.context());

            assertThat(MessageEvidence.observedIn(result.protocolEvidence())).hasSize(2);
        }
    }

    @Test
    void anObservationStopsAtTheCountItWasGiven() {
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0)
                .message(TOPIC, 0, "a", "{}")
                .message(TOPIC, 1, "b", "{}")
                .message(TOPIC, 2, "c", "{}");

        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult result = connector.execute(
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context()),
                    KafkaFixtures.consume(TOPIC),
                    Map.of("maxMessages", 2, "waitMs", 0), KafkaFixtures.context());

            assertThat(MessageEvidence.observedIn(result.protocolEvidence())).hasSize(2);
        }
    }

    @Test
    void theEvidencePolicyGovernsPayloadsAndHeadersAlike() {
        // A message header carries tokens as readily as an HTTP header does.
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0)
                .message(TOPIC, 0, "pay-1", "{\"card\":\"4111111111111111\"}",
                        Map.of("authorization", "Bearer secret", "trace-id", "t-1"));

        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult withheld = connector.execute(
                    connector.prepare(KafkaFixtures.target(),
                            KafkaFixtures.context(EvidencePolicy.MINIMAL)),
                    KafkaFixtures.consume(TOPIC),
                    Map.of("waitMs", 0),
                    KafkaFixtures.context(EvidencePolicy.MINIMAL));

            MessageEvidence message =
                    MessageEvidence.observedIn(withheld.protocolEvidence()).get(0);
            assertThat(message.payload()).isNull();
            assertThat(message.headers()).isEmpty();
            // The digest identifies the message without being the message.
            assertThat(message.digest()).startsWith("sha256:");
            assertThat(message.offset()).isZero();
        }
    }

    @Test
    void aDeniedHeaderIsDroppedWhileTheRestAreKept() {
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0)
                .message(TOPIC, 0, "pay-1", "{}",
                        Map.of("authorization", "Bearer secret", "trace-id", "t-1"));

        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult result = connector.execute(
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context()),
                    KafkaFixtures.consume(TOPIC),
                    Map.of("waitMs", 0), KafkaFixtures.context());

            Map<String, String> headers =
                    MessageEvidence.observedIn(result.protocolEvidence()).get(0).headers();
            assertThat(headers).containsEntry("trace-id", "t-1");
            assertThat(headers).doesNotContainKey("authorization");
        }
    }

    @Test
    void aSelectorStillWorksOnAPayloadThePolicyWillNotStore() {
        // Otherwise a policy that withholds payloads would quietly change which
        // messages a scenario observes, which is not the policy's business.
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0)
                .message(TOPIC, 0, "pay-1", "{\"paymentId\":\"pay-1\"}")
                .message(TOPIC, 1, "pay-2", "{\"paymentId\":\"pay-2\"}");

        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult result = connector.execute(
                    connector.prepare(KafkaFixtures.target(),
                            KafkaFixtures.context(EvidencePolicy.MINIMAL)),
                    KafkaFixtures.consume(TOPIC),
                    Map.of("match", Map.of("payload", Map.of("paymentId", "pay-2")),
                            "waitMs", 0),
                    KafkaFixtures.context(EvidencePolicy.MINIMAL));

            List<MessageEvidence> messages =
                    MessageEvidence.observedIn(result.protocolEvidence());
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).key()).isEqualTo("pay-2");
            assertThat(messages.get(0).payload()).isNull();
        }
    }

    @Test
    void aBrokerOnAPrivateNetworkIsRefusedBeforeAnythingIsSent() {
        try (KafkaConnector connector = new KafkaConnector(
                HostPolicy.defaultPolicy(), new FakeKafka())) {
            var localBroker = new dev.faultora.model.catalog.TargetDefinition(
                    new dev.faultora.model.identifier.TargetId("events"), "Events",
                    "kafka://localhost:9092",
                    List.of(new dev.faultora.model.identifier.ProtocolId("kafka")),
                    List.of(), Map.of());

            assertThatThrownBy(() ->
                    connector.prepare(localBroker, KafkaFixtures.context()))
                    .isInstanceOf(DestinationPolicyViolation.class)
                    .hasMessageContaining("localhost");
        }
    }

    @Test
    void anOperationThatNamesNoKafkaActionFailsAsAConfigurationError() {
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0);
        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult result = connector.execute(
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context()),
                    KafkaFixtures.operation("mystery", "broadcast", TOPIC),
                    Map.of(), KafkaFixtures.context());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error().code()).isEqualTo("KAFKA_INVALID_OPERATION");
            assertThat(result.error().retryable()).isFalse();
        }
    }

    @Test
    void anUnknownTopicIsReportedRatherThanWaitedOut() {
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0);
        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult result = connector.execute(
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context()),
                    KafkaFixtures.consume("no-such-topic"),
                    Map.of("waitMs", 0), KafkaFixtures.context());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error().message()).contains("no-such-topic");
        }
    }

    @Test
    void anObservationThatFindsNothingIsStillAnObservation() {
        // Absence is what several event assertions are about; deciding it here
        // would put the verdict in the connector instead of the assertion.
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0);
        try (KafkaConnector connector = connectorOver(broker)) {
            OperationResult result = connector.execute(
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context()),
                    KafkaFixtures.consume(TOPIC),
                    Map.of("waitMs", 0), KafkaFixtures.context());

            assertThat(result.isSuccess()).isTrue();
            assertThat(MessageEvidence.observedIn(result.protocolEvidence())).isEmpty();
        }
    }
}
