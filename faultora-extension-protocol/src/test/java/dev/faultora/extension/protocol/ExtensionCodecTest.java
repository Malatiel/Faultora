package dev.faultora.extension.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.stream.Stream;

import static dev.faultora.extension.protocol.JsonFrameCodec.MAX_FRAME_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionCodecTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "0123456789abcdef0123456789abcdef";

    private static ObjectNode fixture(String name) throws IOException {
        try (var input = ExtensionCodecTest.class.getResourceAsStream("/extension-wire-v1/" + name + ".json")) {
            assertThat(input).isNotNull();
            return (ObjectNode) JSON.readTree(input);
        }
    }

    private static byte[] encoded(JsonNode message) throws IOException {
        var output = new ByteArrayOutputStream();
        new ExtensionCodec(MAX_FRAME_BYTES).write(output, message);
        return output.toByteArray();
    }

    static Stream<JsonNode> vectors() throws IOException {
        try (var input = ExtensionCodecTest.class.getResourceAsStream("/extension-wire-v1/framing-vectors.json")) {
            return java.util.stream.StreamSupport.stream(JSON.readTree(input).spliterator(), false).toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("vectors")
    void reusesTheIndependentLiteralC01FramingVectors(JsonNode vector) throws Exception {
        var codec = new JsonFrameCodec(vector.path("limit").asInt(MAX_FRAME_BYTES));
        byte[] bytes = HexFormat.of().parseHex(vector.path("hex").asText());
        if (vector.has("error")) {
            assertThatThrownBy(() -> codec.read(new ByteArrayInputStream(bytes)))
                    .isInstanceOf(ProtocolException.class).hasMessage(vector.get("error").asText());
        } else {
            assertThat(codec.read(new ByteArrayInputStream(bytes))).isEqualTo(vector.get("value"));
            var output = new ByteArrayOutputStream();
            codec.write(output, vector.get("value"));
            assertThat(output.toByteArray()).isEqualTo(bytes);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello", "ready", "assertion-request", "assertion-pass", "assertion-fail",
            "assertion-indeterminate", "import-request", "import-result", "import-failure", "render-request",
            "render-result", "error", "handshake-refusal", "shutdown", "stopped"})
    void allC01EnvelopesRoundTripWithoutSpiOrJavaObjectTypes(String name) throws Exception {
        JsonNode message = fixture(name);
        assertThat(new ExtensionCodec(MAX_FRAME_BYTES).read(new ByteArrayInputStream(encoded(message))))
                .isEqualTo(message);
    }

    @Test
    void fragmentedReadsKeepFrameBoundariesAndDoNotOwnTheStream() throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write(encoded(fixture("hello")));
        bytes.write(encoded(fixture("ready")));
        int[] closes = {0};
        var input = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override public int read(byte[] target, int offset, int count) throws IOException {
                return super.read(target, offset, Math.min(1, count));
            }
            @Override public void close() { closes[0]++; }
        };
        var codec = new ExtensionCodec(MAX_FRAME_BYTES);
        assertThat(codec.read(input)).isEqualTo(fixture("hello"));
        assertThat(codec.read(input)).isEqualTo(fixture("ready"));
        assertThat(codec.read(input)).isNull();
        assertThat(closes[0]).isZero();
        input.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"00100001", "ffffffff", "00000401"})
    void oversizedPrefixIsRefusedBeforeAnyPayloadRead(String hex) {
        byte[] prefix = HexFormat.of().parseHex(hex);
        var input = new ByteArrayInputStream(prefix) {
            @Override public synchronized int read() {
                if (pos >= 4) throw new AssertionError("payload read or drained");
                return super.read();
            }
            @Override public synchronized int read(byte[] target, int offset, int count) {
                if (pos >= 4) throw new AssertionError("payload read or drained");
                return super.read(target, offset, count);
            }
        };
        assertThatThrownBy(() -> new JsonFrameCodec(hex.equals("00000401") ? 1024 : MAX_FRAME_BYTES).read(input))
                .isInstanceOf(ProtocolException.class).hasMessage("MESSAGE_TOO_LARGE");
    }

    @Test
    void writerHonoursTheByteCeilingWithoutSendingEvenThePrefixOnOverflow() throws Exception {
        var codec = new JsonFrameCodec(1024);
        var exact = JSON.createObjectNode().put("t", "a".repeat(1016));
        var output = new ByteArrayOutputStream();
        codec.write(output, exact);
        assertThat(output.size()).isEqualTo(1028);
        assertThat(codec.read(new ByteArrayInputStream(output.toByteArray()))).isEqualTo(exact);
        output.reset();
        var excessive = JSON.createObjectNode().put("t", "é".repeat(600));
        assertThatThrownBy(() -> codec.write(output, excessive)).hasMessage("MESSAGE_TOO_LARGE");
        assertThat(output.size()).isZero();
        var escaped = JSON.createObjectNode().put("t", "\n".repeat(600));
        assertThatThrownBy(() -> codec.write(output, escaped)).hasMessage("MESSAGE_TOO_LARGE");
        assertThat(output.size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "-1", "1.0", "9223372036854775808", "18446744073709551615", "", "+1"})
    void rejectsNonCanonicalOrUnrepresentableIdsOnReadAndWrite(String id) throws Exception {
        var message = fixture("assertion-pass").put("id", id);
        var codec = new ExtensionCodec(MAX_FRAME_BYTES);
        var output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> codec.write(output, message)).hasMessage("ID_MISMATCH");
        assertThat(output.size()).isZero();
        assertThatThrownBy(() -> codec.read(raw(message.toString()))).hasMessage("ID_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"numeric-id", "extra", "version", "session", "data", "zero-request", "ready-id"})
    void rejectsEnvelopeShapeVersionAndControlIdErrors(String mutation) throws Exception {
        var message = fixture(mutation.equals("ready-id") ? "ready" : "assertion-request");
        String code = switch (mutation) {
            case "numeric-id" -> { message.put("id", 1); yield "ID_MISMATCH"; }
            case "extra" -> { message.put("@class", "example.NotData"); yield "MALFORMED_MESSAGE"; }
            case "version" -> { message.put("version", "1.1"); yield "INCOMPATIBLE_VERSION"; }
            case "session" -> { message.put("sessionId", "bad"); yield "MALFORMED_MESSAGE"; }
            case "data" -> { message.putNull("data"); yield "MALFORMED_MESSAGE"; }
            case "zero-request" -> { message.put("id", "0"); yield "ID_MISMATCH"; }
            default -> { message.put("id", "1"); yield "ID_MISMATCH"; }
        };
        assertThatThrownBy(() -> new ExtensionCodec(MAX_FRAME_BYTES).read(raw(message.toString()))).hasMessage(code);
    }

    @Test
    void replyMustMatchThePendingSessionAndIdAndCannotBeAnEof() throws Exception {
        var codec = new ExtensionCodec(MAX_FRAME_BYTES);
        byte[] reply = encoded(fixture("assertion-pass"));
        assertThat(codec.readReply(new ByteArrayInputStream(reply), SESSION, "1")).isEqualTo(fixture("assertion-pass"));
        assertThatThrownBy(() -> codec.readReply(new ByteArrayInputStream(reply), SESSION, "2")).hasMessage("ID_MISMATCH");
        assertThatThrownBy(() -> codec.readReply(new ByteArrayInputStream(reply), "f".repeat(32), "1")).hasMessage("SESSION_MISMATCH");
        assertThatThrownBy(() -> codec.readReply(new ByteArrayInputStream(new byte[0]), SESSION, "1")).hasMessage("PEER_CLOSED");
        assertThatThrownBy(() -> codec.readReply(raw(fixture("handshake-refusal").toString()), SESSION, "1")).hasMessage("ID_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"depth", "number", "non-finite", "java-object"})
    void writerRefusesDeepOrNonJsonTreesWithoutTouchingOutput(String kind) {
        var node = JSON.createObjectNode();
        switch (kind) {
            case "depth" -> { ObjectNode nested = node; for (int i = 0; i < 64; i++) nested = nested.putObject("x"); }
            case "number" -> node.put("n", new BigInteger("9".repeat(65)));
            case "non-finite" -> node.put("n", Double.NaN);
            default -> node.putPOJO("object", new Object());
        }
        var output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> new JsonFrameCodec(MAX_FRAME_BYTES).write(output, node)).hasMessage("MALFORMED_MESSAGE");
        assertThat(output.size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"depth", "number", "non-finite", "nested-duplicate"})
    void readerEnforcesJsonComplexityAndLexicalConstraints(String kind) {
        String value = switch (kind) {
            case "depth" -> "{\"x\":".repeat(65) + "0" + "}".repeat(65);
            case "number" -> "{\"x\":" + "9".repeat(65) + "}";
            case "non-finite" -> "{\"x\":NaN}";
            default -> "{\"x\":{\"same\":1,\"same\":2}}";
        };
        assertThatThrownBy(() -> new JsonFrameCodec(MAX_FRAME_BYTES).read(raw(value))).hasMessage("MALFORMED_MESSAGE");
    }

    @Test
    void ioFailuresNeverRetainTheUnderlyingMessageOrCause() {
        var codec = new JsonFrameCodec(1024);
        var input = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("SYNTHETIC_PRIVATE_MARKER"); }
        };
        var output = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("SYNTHETIC_PRIVATE_MARKER"); }
        };
        assertThatThrownBy(() -> codec.read(input)).hasMessage("PEER_CLOSED").hasNoCause();
        assertThatThrownBy(() -> codec.write(output, JSON.createObjectNode())).hasMessage("PEER_CLOSED").hasNoCause();
    }

    @Test
    void finiteDecimalExponentsRemainPlainJsonWithoutDoubleOverflow() throws Exception {
        var codec = new JsonFrameCodec(1024);
        JsonNode value = codec.read(raw("{\"n\":1e400}"));
        var output = new ByteArrayOutputStream();
        codec.write(output, value);
        assertThat(codec.read(new ByteArrayInputStream(output.toByteArray()))).isEqualTo(value);
        assertThat(value.get("n").isBigDecimal()).isTrue();
    }

    @Test
    void theAbsoluteCeilingIsInclusiveAndStreamsAreNotFlushedOrClosed() throws Exception {
        var node = JSON.createObjectNode().put("t", "a".repeat(MAX_FRAME_BYTES - 8));
        var output = new ByteArrayOutputStream() {
            @Override public void close() { throw new AssertionError("unexpected close"); }
            @Override public void flush() { throw new AssertionError("unexpected flush"); }
        };
        var codec = new JsonFrameCodec(MAX_FRAME_BYTES);
        codec.write(output, node);
        assertThat(output.size()).isEqualTo(MAX_FRAME_BYTES + 4);
        assertThat(codec.read(new ByteArrayInputStream(output.toByteArray()))).isEqualTo(node);
    }

    private static ByteArrayInputStream raw(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new ByteArrayInputStream(ByteBuffer.allocate(bytes.length + 4).putInt(bytes.length).put(bytes).array());
    }
}
