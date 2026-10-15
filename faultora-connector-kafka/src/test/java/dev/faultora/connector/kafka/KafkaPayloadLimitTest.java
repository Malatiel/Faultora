package dev.faultora.connector.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.net.HostPolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class KafkaPayloadLimitTest {
    private final FakeKafka broker = new FakeKafka().topic(KafkaFixtures.TOPIC, 0);
    private KafkaConnector connector;

    @BeforeEach void setUp() { connector = new KafkaConnector(HostPolicy.permissive(), broker); }
    @AfterEach void tearDown() { connector.close(); }

    @Test
    void keyValueAndHeaderBytesShareOneLimitIncludingUtf8Names() {
        Map<String, Object> inputs = Map.of("key", "é", "body", new byte[]{1, 2, 3, 4},
                "headers", Map.of("x", "€"));
        assertThat(publish(inputs, 10).isSuccess()).isTrue();
        var record = broker.published().history().getFirst();
        assertThat(record.key()).containsExactly("é".getBytes(StandardCharsets.UTF_8));
        assertThat(record.value()).containsExactly((byte) 1, (byte) 2, (byte) 3, (byte) 4);
        assertThat(record.headers().lastHeader("x").value()).containsExactly("€".getBytes(StandardCharsets.UTF_8));
        assertThat(broker.producersOpened()).isEqualTo(1);
        assertThat(broker.consumersOpened()).isZero();
    }

    @Test
    void individuallySmallFieldsCannotExceedTheAggregateLimit() {
        assertTooLarge(publish(Map.of("key", "é", "body", new byte[]{1, 2, 3, 4},
                "headers", Map.of("x", "€")), 9));
        assertUnsent();
    }

    @ParameterizedTest
    @MethodSource("oversizedFields")
    void anyOversizedComponentStopsBeforeOpeningTheProducer(Map<String, Object> inputs, int limit) {
        assertTooLarge(publish(inputs, limit));
        assertUnsent();
    }

    static Stream<Arguments> oversizedFields() {
        return Stream.of(
                Arguments.of(Map.of("key", "é"), 1),
                Arguments.of(Map.of("body", "é"), 1),
                Arguments.of(Map.of("body", new byte[]{1, 2}), 1),
                Arguments.of(Map.of("headers", Map.of("é", "")), 1),
                Arguments.of(Map.of("headers", Map.of("x", "é")), 2),
                Arguments.of(Map.of("key", "a", "body", "b", "headers", Map.of("c", "d")), 3));
    }

    @Test
    void structuredJsonRetainsItsPreviousBytesAndUsesTheirActualSize() throws Exception {
        Map<String, Object> value = Map.of("text", "é😺");
        byte[] expected = new ObjectMapper().writeValueAsBytes(value);
        assertTooLarge(publish(Map.of("body", value), expected.length - 1));
        assertUnsent();
        assertThat(publish(Map.of("body", value), expected.length).isSuccess()).isTrue();
        assertThat(broker.published().history().getFirst().value()).containsExactly(expected);
    }

    @Test
    void nestedBinaryJsonCountsBase64ExpansionWhileARawValueStaysBinary() {
        byte[] value = {1, 2, 3};
        assertThat(publish(Map.of("body", value), 3).isSuccess()).isTrue();
        byte[] expected = "{\"b\":\"AQID\"}".getBytes(StandardCharsets.UTF_8);
        assertTooLarge(publish(Map.of("body", Map.of("b", value)), expected.length - 1));
        assertThat(publish(Map.of("body", Map.of("b", value)), expected.length).isSuccess()).isTrue();
        assertThat(broker.published().history()).hasSize(2);
        assertThat(broker.published().history().getLast().value()).containsExactly(expected);
    }

    @Test
    void nullTombstonesAndEmptyValuesRemainDistinct() {
        Map<String, Object> tombstone = new LinkedHashMap<>();
        tombstone.put("body", null);
        assertThat(publish(tombstone, 1).isSuccess()).isTrue();
        assertThat(publish(Map.of("body", new byte[0], "key", ""), 1).isSuccess()).isTrue();
        assertThat(broker.published().history().getFirst().value()).isNull();
        assertThat(broker.published().history().getFirst().key()).isNull();
        assertThat(broker.published().history().getLast().value()).isEmpty();
        assertThat(broker.published().history().getLast().key()).isEmpty();
    }

    @Test
    void binaryValuesAreSnapshottedRatherThanOwnedByTheCaller() {
        byte[] value = {1, 2};
        assertThat(publish(Map.of("body", value), 2).isSuccess()).isTrue();
        value[0] = 99;
        assertThat(broker.published().history().getFirst().value()).containsExactly((byte) 1, (byte) 2);
    }

    @Test
    void nullHeadersAreOmittedAndNormalizedNamesKeepTheirLastValue() {
        Map<Object, Object> headers = new LinkedHashMap<>();
        headers.put(null, "ignored");
        headers.put("ignored", null);
        headers.put(1, "longer");
        headers.put("1", "x");
        assertThat(publish(Map.of("headers", headers), 7).isSuccess()).isTrue();
        var actual = broker.published().history().getFirst().headers();
        assertThat(actual.toArray()).hasSize(1);
        assertThat(actual.lastHeader("1").value()).containsExactly((byte) 'x');
    }

    @Test
    void replacingANormalizedHeaderDoesNotChargeBothValues() {
        Map<Object, Object> headers = new LinkedHashMap<>();
        headers.put(1, "aa");
        headers.put("1", "bbb");
        assertThat(publish(Map.of("body", "x", "headers", headers), 5).isSuccess()).isTrue();
        assertThat(broker.published().history().getFirst().headers().lastHeader("1").value())
                .containsExactly("bbb".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unusedNonMapHeadersKeepTheirExistingBehavior() {
        assertThat(publish(Map.of("body", "x", "headers", "unused"), 1).isSuccess()).isTrue();
        assertThat(broker.published().history().getFirst().headers().toArray()).isEmpty();
    }

    @Test
    void aRejectedRecordDoesNotPoisonTheNextPublishOrOpenAConsumer() {
        assertTooLarge(publish(Map.of("body", "oversized"), 1));
        assertUnsent();
        assertThat(publish(Map.of("body", "ok"), 2).isSuccess()).isTrue();
        assertThat(broker.published().history()).hasSize(1);
        assertThat(broker.producersOpened()).isEqualTo(1);
        assertThat(broker.consumersOpened()).isZero();
    }

    @Test
    void jsonTraversalStopsBeforeBuildingAnEntireOversizedCollection() {
        AtomicInteger visited = new AtomicInteger();
        var body = new AbstractList<String>() {
            public int size() { return Integer.MAX_VALUE; }
            public String get(int index) { visited.incrementAndGet(); return "x".repeat(200); }
        };
        assertTooLarge(publish(Map.of("body", body), 16));
        assertThat(visited.get()).isBetween(1, 100);
        assertUnsent();
    }

    @Test
    void headersAreCheckedDuringTraversalRatherThanAfterCopyingTheWholeMap() {
        AtomicInteger visited = new AtomicInteger();
        var headers = new AbstractMap<String, String>() {
            public Set<Entry<String, String>> entrySet() {
                return new AbstractSet<>() {
                    public int size() { return Integer.MAX_VALUE; }
                    public Iterator<Entry<String, String>> iterator() {
                        return new Iterator<>() {
                            public boolean hasNext() { return true; }
                            public Entry<String, String> next() {
                                return new SimpleEntry<>("h" + visited.incrementAndGet(), "x".repeat(200));
                            }
                        };
                    }
                };
            }
        };
        assertTooLarge(publish(Map.of("headers", headers), 16));
        assertThat(visited.get()).isEqualTo(1);
        assertUnsent();
    }

    @Test
    void producerBatchSettingsCannotWidenTheRecordDataLimit() {
        ConnectorContext context = KafkaFixtures.context(KafkaFixtures.capturing(),
                Map.of(KafkaConnector.MAX_RECORD_BYTES, 1, "kafka.max.request.size", 100_000_000));
        assertTooLarge(publish(Map.of("body", "xx"), context));
        assertUnsent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"body", "key", "header-name", "header-value"})
    void oversizedCharacterSequencesAreNotFirstMaterializedAsStrings(String field) {
        AtomicInteger visited = new AtomicInteger();
        CharSequence text = new CharSequence() {
            public int length() { return Integer.MAX_VALUE; }
            public char charAt(int index) { visited.incrementAndGet(); return 'x'; }
            public CharSequence subSequence(int start, int end) { throw new AssertionError("No full intermediate text"); }
            public String toString() { throw new AssertionError("No full intermediate text"); }
        };
        Map<String, Object> input = switch (field) {
            case "header-name" -> Map.of("headers", Map.of(text, ""));
            case "header-value" -> Map.of("headers", Map.of("x", text));
            default -> Map.of(field, text);
        };
        assertTooLarge(publish(input, 16));
        assertThat(visited.get()).isBetween(1, 20_000);
        assertUnsent();
    }

    @Test
    void surrogatePairsCrossingAnEncodingChunkKeepTheirUtf8Bytes() {
        String value = "x".repeat(4095) + "😺";
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        assertThat(publish(Map.of("body", new StringBuilder(value)), expected.length).isSuccess()).isTrue();
        assertThat(broker.published().history().getFirst().value()).containsExactly(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidLimits")
    void invalidLimitsFailClosedBeforeTheProducerIsOpened(Object limit) {
        OperationResult result = publish(Map.of("body", "x"), context(limit));
        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.VALIDATION);
        assertThat(result.error().code()).isEqualTo("INVALID_PAYLOAD_LIMIT");
        assertThat(result.error().retryable()).isFalse();
        assertUnsent();
    }

    static Stream<Object> invalidLimits() {
        return Stream.of(0, -1L, 10 * 1024 * 1024 + 1, Long.MAX_VALUE, "16", true,
                1.5, Double.NaN, Double.POSITIVE_INFINITY, new BigDecimal("1.001"),
                new BigInteger("18446744073709551617"));
    }

    @ParameterizedTest
    @MethodSource("validLimits")
    void exactIntegralNumericLimitsAreAccepted(Number limit) {
        assertThat(publish(Map.of("body", new byte[16]), context(limit)).isSuccess()).isTrue();
        assertThat(broker.published().history()).hasSize(1);
    }

    static Stream<Number> validLimits() {
        return Stream.of(16, 16L, new BigInteger("16"), new BigDecimal("16.000"), 16.0);
    }

    @Test
    void missingConfigurationStillAppliesAFiniteCeilingWithoutCopyingOversizedBinaryInput() {
        assertTooLarge(publish(Map.of("body", new byte[10 * 1024 * 1024 + 1]), KafkaFixtures.context()));
        assertUnsent();
    }

    @Test
    void aSerializationFailureIsAnExplicitValidationFailureWithoutPublishing() {
        OperationResult result = publish(Map.of("body", new Object()), 16);
        assertThat(result.error().code()).isEqualTo("KAFKA_INVALID_OPERATION");
        assertThat(result.error().message()).isEqualTo("Message payload cannot be serialized");
        assertUnsent();
    }

    @Test
    void operatorInterceptorsCannotReplaceAnAlreadyValidatedRecord() {
        ConnectorContext context = KafkaFixtures.context(KafkaFixtures.capturing(),
                Map.of("kafka.interceptor.classes", "synthetic.PayloadReplacingInterceptor"));
        assertThatThrownBy(() -> connector.prepare(KafkaFixtures.target(), context))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("interceptor.classes");
        assertUnsent();
    }

    @Test
    void publicationLimitsDoNotChangeObservationBehavior() {
        var prepared = connector.prepare(KafkaFixtures.target(), context(0));
        try {
            assertThat(connector.execute(prepared, KafkaFixtures.consume(KafkaFixtures.TOPIC),
                    Map.of("waitMs", 0), context(0)).isSuccess()).isTrue();
        } finally { connector.release(prepared); }
        assertThat(broker.producersOpened()).isZero();
    }

    private OperationResult publish(Map<String, Object> inputs, int limit) {
        return publish(inputs, context(limit));
    }

    private OperationResult publish(Map<String, Object> inputs, ConnectorContext context) {
        var prepared = connector.prepare(KafkaFixtures.target(), context);
        try { return connector.execute(prepared, KafkaFixtures.publish(KafkaFixtures.TOPIC), inputs, context); }
        finally { connector.release(prepared); }
    }

    private static ConnectorContext context(Object limit) {
        return KafkaFixtures.context(KafkaFixtures.capturing(), Map.of(KafkaConnector.MAX_RECORD_BYTES, limit));
    }

    private static void assertTooLarge(OperationResult result) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.POLICY_VIOLATION);
        assertThat(result.error().code()).isEqualTo("REQUEST_TOO_LARGE");
        assertThat(result.error().retryable()).isFalse();
        assertThat(result.error().metadata()).containsKey("maxBytes");
        assertThat(result.protocolEvidence()).isEmpty();
    }

    private void assertUnsent() {
        assertThat(broker.producersOpened()).isZero();
        assertThat(broker.published().history()).isEmpty();
    }
}
