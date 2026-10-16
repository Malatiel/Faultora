package dev.faultora.connector.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(5)
class RequestBodyTest {
    @ParameterizedTest
    @CsvSource({"abc, 3", "é, 2", "€, 3", "😺, 4", "я😺, 6"})
    void rawTextUsesEncodedBytesAndAcceptsExactlyTheLimit(String text, int bytes) throws Exception {
        assertThat(RequestBody.encode(text, bytes)).containsExactly(text.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RequestBody.encode(text, bytes - 1))
                .isInstanceOf(RequestBody.TooLarge.class);
    }

    @Test
    void structuredJsonCountsNamesQuotesAndUtf8Values() throws Exception {
        String json = "{\"text\":\"é😺\"}";
        byte[] expected = json.getBytes(StandardCharsets.UTF_8);
        assertThat(RequestBody.encode(Map.of("text", "é😺"), expected.length)).containsExactly(expected);
        assertThatThrownBy(() -> RequestBody.encode(Map.of("text", "é😺"), expected.length - 1))
                .isInstanceOf(RequestBody.TooLarge.class);
    }

    @Test
    void escapingIsChargedByTheEmittedJsonBytes() throws Exception {
        byte[] expected = "{\"x\":\"\\n\\\\\\\"\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(RequestBody.encode(Map.of("x", "\n\\\""), expected.length)).containsExactly(expected);
        assertThatThrownBy(() -> RequestBody.encode(Map.of("x", "\n\\\""), expected.length - 1))
                .isInstanceOf(RequestBody.TooLarge.class);
    }

    @Test
    void binaryJsonUsesItsBase64WireSize() throws Exception {
        byte[] value = {1, 2, 3};
        assertThat(RequestBody.encode(value, 6)).containsExactly("\"AQID\"".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RequestBody.encode(value, 5)).isInstanceOf(RequestBody.TooLarge.class);
    }

    @Test
    void utf8SurrogatePairsCanCrossAnEncodingChunkBoundary() throws Exception {
        String text = "x".repeat(4095) + "😺é";
        byte[] expected = text.getBytes(StandardCharsets.UTF_8);
        assertThat(RequestBody.encode(text, expected.length)).containsExactly(expected);
        assertThatThrownBy(() -> RequestBody.encode(text, expected.length - 1))
                .isInstanceOf(RequestBody.TooLarge.class);
    }

    @Test
    void rawTextKeepsThePreviousUtf8ReplacementForAnUnpairedSurrogate() throws Exception {
        assertThat(RequestBody.encode("\uD800", 1)).containsExactly((byte) '?');
    }

    @Test
    void absentAndEmptyBodiesEncodeNoPayload() throws Exception {
        assertThat(RequestBody.encode(null, 1)).isEmpty();
        assertThat(RequestBody.encode("", 1)).isEmpty();
    }

    @Test
    void overflowingJsonStopsTraversalBeforeMaterializingTheCollection() {
        AtomicInteger visited = new AtomicInteger();
        var body = new AbstractList<String>() {
            public int size() {
                return Integer.MAX_VALUE;
            }
            public String get(int index) {
                visited.incrementAndGet();
                return "x".repeat(200);
            }
        };
        assertThatThrownBy(() -> RequestBody.encode(body, 64)).isInstanceOf(RequestBody.TooLarge.class);
        assertThat(visited.get()).isBetween(1, 100);
    }

    @Test
    void anOversizedRawStringIsRejectedDuringEncoding() {
        assertThatThrownBy(() -> RequestBody.encode("x".repeat(100_000), 16))
                .isInstanceOf(RequestBody.TooLarge.class);
    }

    @Test
    void missingConfigurationUsesTheFiniteConnectorCeiling() {
        assertThat(RequestBody.limit(null)).isEqualTo(10 * 1024 * 1024);
    }

    @ParameterizedTest
    @MethodSource("invalidLimits")
    void invalidLimitsCannotBeTruncatedOrWidened(Object value) {
        assertThatThrownBy(() -> RequestBody.limit(value)).isInstanceOf(RequestBody.InvalidLimit.class);
    }

    static Stream<Object> invalidLimits() {
        return Stream.of(0, -1L, 10 * 1024 * 1024 + 1, Long.MAX_VALUE, "16", true,
                1.5, Double.NaN, Double.POSITIVE_INFINITY, new BigDecimal("1.001"),
                new BigInteger("18446744073709551617"));
    }

    @ParameterizedTest
    @MethodSource("validLimits")
    void exactIntegralNumericLimitsAreAccepted(Number value) {
        assertThat(RequestBody.limit(value)).isEqualTo(16);
    }

    static Stream<Number> validLimits() {
        return Stream.of(16, 16L, new BigInteger("16"), new BigDecimal("16.000"), 16.0);
    }
}
