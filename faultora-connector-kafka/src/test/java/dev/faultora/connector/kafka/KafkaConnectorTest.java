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
    void concurrentStepsOnOneTargetNeverShareAConsumer() throws Exception {
        // A Kafka consumer belongs to the one thread that polls it, and the
        // engine prepares and releases around every invocation. Handing the
        // same handle to concurrent steps would drive one client from two
        // threads and let the first step to finish close it under the second.
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0)
                .message(TOPIC, 0, "pay-1", "{\"paymentId\":\"pay-1\"}")
                .message(TOPIC, 1, "pay-2", "{\"paymentId\":\"pay-2\"}");

        try (KafkaConnector connector = connectorOver(broker)) {
            int steps = 4;
            var started = new java.util.concurrent.CountDownLatch(steps);
            var go = new java.util.concurrent.CountDownLatch(1);
            var failures = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
            List<Thread> parallel = new java.util.ArrayList<>();

            for (int step = 0; step < steps; step++) {
                String wanted = step % 2 == 0 ? "pay-1" : "pay-2";
                Thread thread = new Thread(() -> {
                    Connector.PreparedTarget prepared = null;
                    try {
                        prepared = connector.prepare(
                                KafkaFixtures.target(), KafkaFixtures.context());
                        started.countDown();
                        go.await();
                        OperationResult result = connector.execute(
                                prepared, KafkaFixtures.consume(TOPIC),
                                Map.of("match", Map.of("payload", Map.of("paymentId", wanted)),
                                        "waitMs", 0),
                                KafkaFixtures.context());
                        assertThat(MessageEvidence.observedIn(result.protocolEvidence()))
                                .singleElement()
                                .satisfies(message ->
                                        assertThat(message.key()).isEqualTo(wanted));
                    } catch (Throwable concurrent) {
                        failures.add(concurrent);
                    } finally {
                        if (prepared != null) {
                            connector.release(prepared);
                        }
                    }
                });
                parallel.add(thread);
                thread.start();
            }

            started.await();
            go.countDown();
            for (Thread thread : parallel) {
                thread.join(30_000);
            }

            assertThat(failures).isEmpty();
            assertThat(broker.consumersOpened())
                    .as("one consumer per step, never one shared")
                    .isEqualTo(steps);
            assertThat(broker.consumersStillOpen())
                    .as("releasing a step closes its own consumer and no other")
                    .isZero();
        }
    }

    @Test
    void aPublishingRunOpensNoConsumerAtAll() {
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0);
        try (KafkaConnector connector = connectorOver(broker)) {
            Connector.PreparedTarget prepared =
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context());
            connector.execute(prepared, KafkaFixtures.publish(TOPIC),
                    Map.of("body", Map.of("paymentId", "pay-1")), KafkaFixtures.context());
            connector.release(prepared);

            assertThat(broker.consumersOpened()).isZero();
        }
    }

    @Test
    void anObservationClosesAtItsDeadlineOnAChannelThatKeepsMoving() {
        // Nothing here matches the selector, and the channel never goes quiet.
        // An observation that polled on while messages arrived would hold the
        // run open for as long as the target stayed busy.
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0);
        for (int offset = 0; offset < 500; offset++) {
            broker.message(TOPIC, offset, "other-" + offset,
                    "{\"paymentId\":\"other-" + offset + "\"}");
        }

        try (KafkaConnector connector = connectorOver(broker)) {
            long startedAt = System.nanoTime();
            OperationResult result = connector.execute(
                    connector.prepare(KafkaFixtures.target(), KafkaFixtures.context()),
                    KafkaFixtures.consume(TOPIC),
                    Map.of("match", Map.of("payload", Map.of("paymentId", "never-sent")),
                            "waitMs", 500),
                    KafkaFixtures.context());
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(MessageEvidence.observedIn(result.protocolEvidence())).isEmpty();
            assertThat(elapsedMs)
                    .as("the window closes at its deadline, not when the channel quietens")
                    .isLessThan(5_000);
        }
    }

    @Test
    void anOperatorCannotPassThroughTheBrokerListThePolicyChecked() {
        // The bootstrap list is what the destination policy verified. Replacing
        // it through the settings pass-through would reach a host the policy
        // never saw, which is a bypass rather than a configuration choice.
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0);
        try (KafkaConnector connector = connectorOver(broker)) {
            var smuggled = KafkaFixtures.context(KafkaFixtures.capturing(),
                    Map.of("kafka.bootstrap.servers", "elsewhere.example.com:9092"));

            assertThatThrownBy(() -> connector.prepare(KafkaFixtures.target(), smuggled))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bootstrap.servers")
                    .hasMessageContaining("destination policy");
        }
    }

    @Test
    void anOperatorsOwnKafkaSettingsStillReachTheClient() {
        FakeKafka broker = new FakeKafka().topic(TOPIC, 0);
        try (KafkaConnector connector = connectorOver(broker)) {
            var secured = KafkaFixtures.context(KafkaFixtures.capturing(),
                    Map.of("kafka.security.protocol", "SASL_SSL"));

            // Preparing is where a refused setting would surface; nothing here
            // is refused, so TLS and SASL settings pass through untouched.
            assertThat(connector.prepare(KafkaFixtures.target(), secured)).isNotNull();
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
