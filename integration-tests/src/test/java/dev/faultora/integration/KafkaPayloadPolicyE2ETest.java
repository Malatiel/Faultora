package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.cli.FaultoraCli;
import dev.faultora.connector.kafka.KafkaConnector;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.net.HostPolicy;
import dev.faultora.runner.DispatchVerifier;
import dev.faultora.runner.DispatchedRun;
import dev.faultora.runner.LocalLimits;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.spi.context.ConnectorContext;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Record-size policy through the connector, runner and CLI against one disposable broker. */
@EnabledIf("dockerIsAvailable")
@Timeout(30)
class KafkaPayloadPolicyE2ETest {
    @TempDir Path directory;
    private static KafkaContainer kafka;
    private static Admin admin;
    private static final AtomicInteger TOPICS = new AtomicInteger();
    private String topic;

    static boolean dockerIsAvailable() {
        return RecoveryInfrastructure.dockerIsAvailable();
    }

    @BeforeAll
    static void startBroker() throws Exception {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
        kafka.start();
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    @AfterAll
    static void stopBroker() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
        }
        if (kafka != null) {
            kafka.stop();
        }
    }

    @BeforeEach
    void newEmptyTopic() throws Exception {
        topic = "faultora-payload-" + TOPICS.incrementAndGet();
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(5, TimeUnit.SECONDS);
        assertThat(endOffset()).isZero();
    }

    @Test
    void aRecordAtTheAggregateLimitReachesTheBrokerWithItsCheckedBytes() throws Exception {
        try (KafkaConnector connector = new KafkaConnector(HostPolicy.permissive())) {
            ConnectorContext context = context(10);
            var target = connector.prepare(target(), context);
            try {
                assertThat(connector.execute(target, operation(), Map.of("key", "é", "body", new byte[]{1, 2, 3, 4},
                        "headers", Map.of("x", "€")), context).isSuccess()).isTrue();
            } finally {
                connector.release(target);
            }
        }
        assertThat(endOffset()).isEqualTo(1);
        ConsumerRecord<byte[], byte[]> record = firstRecord();
        assertThat(record.key()).containsExactly("é".getBytes(StandardCharsets.UTF_8));
        assertThat(record.value()).containsExactly((byte) 1, (byte) 2, (byte) 3, (byte) 4);
        assertThat(record.headers().lastHeader("x").value()).containsExactly("€".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aRecordAboveTheAggregateLimitNeverAppearsOnTheBroker() throws Exception {
        try (KafkaConnector connector = new KafkaConnector(HostPolicy.permissive())) {
            ConnectorContext context = context(9);
            var target = connector.prepare(target(), context);
            try {
                var result = connector.execute(target, operation(), Map.of("key", "é", "body", new byte[]{1, 2, 3, 4},
                        "headers", Map.of("x", "€")), context);
                assertThat(result.error().code()).isEqualTo("REQUEST_TOO_LARGE");
            } finally {
                connector.release(target);
            }
        }
        assertThat(endOffset()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {9, 10})
    void dispatchedPayloadPolicyCountsTheKeyAndHeadersAsWellAsTheValue(long limit) throws Exception {
        String scenario = scenario("é", "1234", true);
        List<DispatchedDocument> documents = List.of(new DispatchedDocument("asyncapi", asyncApi()));
        TargetPolicy policy = new TargetPolicy(Set.of(), Set.of(SafetyClassification.MUTATING),
                10, 1, 10_000, limit, Set.of(), Set.of());
        SignedPolicy signed = new SignedPolicy(new ObjectMapper().writeValueAsString(EffectivePolicy.of(policy)),
                "synthetic-trusted-key", "c2ln");
        long now = System.currentTimeMillis();
        Dispatch dispatch = new Dispatch("synthetic-kafka", now, "synthetic-nonce", scenario, documents,
                Map.of(), Map.of("broker", baseUrl()), Dispatch.Credentials.none(), 42, signed,
                new Lease(now, 20_000, 10_000), ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(documents));
        LocalLimits limits = new LocalLimits(Set.of(), Set.of(SafetyClassification.MUTATING), Set.of(), Set.of(),
                1, 10_000, 10, 1_048_576, null);
        DispatchedRun runner = new DispatchedRun(new DispatchVerifier(limits,
                candidate -> "synthetic-trusted-key".equals(candidate.keyId())),
                directory.resolve("runner"), key -> null, true);
        var outcome = runner.execute(dispatch, Map.of(), new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of()));
        assertThat(outcome.didRun()).as(() -> String.valueOf(outcome.refusal())).isTrue();
        assertThat(outcome.result().status()).isEqualTo(limit == 10 ? RunResult.Status.PASSED : RunResult.Status.FAILED);
        assertThat(endOffset()).isEqualTo(limit == 10 ? 1 : 0);
        if (limit == 9) {
            assertThat(Files.readString(outcome.journalPath())).contains("REQUEST_TOO_LARGE");
        }
    }

    @Test
    void cliCountsTheKeyWhenTheValueAlreadyUsesItsEntireOneMiBBudget() throws Exception {
        Path scenario = directory.resolve("scenario.yaml");
        Path asyncapi = directory.resolve("asyncapi.yaml");
        Files.writeString(scenario, scenario("x", "x".repeat(1_048_576), false));
        Files.writeString(asyncapi, asyncApi());
        StringWriter report = new StringWriter();
        int exit = new FaultoraCli(new PrintWriter(report, true), new PrintWriter(report, true))
                .run(new String[]{"test", "--scenario", scenario.toString(), "--asyncapi", asyncapi.toString(),
                        "--target", "broker=" + baseUrl(), "--allow-private", "--seed", "42",
                        "--format", "console,json", "--output", directory.resolve("results").toString()});
        assertThat(exit).as(report::toString).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        assertThat(Files.readString(directory.resolve("results/events.ndjson"))).contains("REQUEST_TOO_LARGE");
        assertThat(endOffset()).isZero();
    }

    private long endOffset() throws Exception {
        TopicPartition partition = new TopicPartition(topic, 0);
        return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get(5, TimeUnit.SECONDS)
                .get(partition).offset();
    }

    private ConsumerRecord<byte[], byte[]> firstRecord() {
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seek(partition, 0);
            long started = System.nanoTime();
            while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5)) {
                var records = consumer.poll(Duration.ofMillis(100));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
            throw new AssertionError("Acknowledged record was not readable");
        }
    }

    private static ConnectorContext context(int limit) {
        return new ConnectorContext(EvidencePolicy.MINIMAL, key -> null, 1000, 5000, 10_000,
                Map.of(KafkaConnector.MAX_RECORD_BYTES, limit));
    }

    private TargetDefinition target() {
        return new TargetDefinition(new TargetId("broker"), "Broker", baseUrl(),
                List.of(new ProtocolId("kafka")), List.of(), Map.of());
    }

    private OperationDefinition operation() {
        return new OperationDefinition(new OperationId("publish"), new ProtocolId("kafka"), new TargetId("broker"),
                SafetyClassification.MUTATING, Map.of(), null, Map.of(), Map.of("action", "publish", "topic", topic));
    }

    private static String baseUrl() {
        return "kafka://" + kafka.getBootstrapServers().replace("PLAINTEXT://", "");
    }

    private String asyncApi() {
        return """
                asyncapi: 3.0.0
                info:
                  title: Synthetic Payload Events
                  version: 1.0.0
                servers:
                  broker:
                    host: localhost:9092
                    protocol: kafka
                channels:
                  records:
                    address: %s
                operations:
                  publish:
                    action: receive
                    channel:
                      $ref: '#/channels/records'
                """.formatted(topic);
    }

    private static String scenario(String key, String body, boolean headers) {
        return """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata:
                  name: synthetic-kafka
                execute:
                  - id: publish
                    type: operation
                    operationId: publish
                    inputs:
                      key: '%s'
                      body: '%s'
                """.formatted(key, body) + (headers ? "      headers:\n        x: '€'\n" : "");
    }
}
