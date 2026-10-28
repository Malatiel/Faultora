package dev.faultora.spi.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.evidence.EvidenceExport;
import dev.faultora.spi.result.AssertionResult;
import dev.faultora.spi.result.ImportResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static dev.faultora.spi.extension.ExtensionWireContractProbe.Direction.CHILD;
import static dev.faultora.spi.extension.ExtensionWireContractProbe.Direction.PARENT;
import static dev.faultora.spi.extension.ExtensionWireContractProbe.JSON;
import static dev.faultora.spi.extension.ExtensionWireContractProbe.MAX_FRAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Conformance vectors for the specification, not a production transport/isolation test. */
class ExtensionWireContractTest {
    private static ObjectNode fixture(String name) throws IOException {
        try (var input = ExtensionWireContractTest.class.getResourceAsStream("/extension-wire-v1/" + name + ".json")) {
            assertThat(input).as("wire fixture %s", name).isNotNull();
            return (ObjectNode) JSON.readTree(input);
        }
    }

    private static void send(ExtensionWireContractProbe probe, ExtensionWireContractProbe.Direction direction,
                             JsonNode message, long now) throws IOException {
        probe.receive(direction, new ByteArrayInputStream(ExtensionWireContractProbe.frame(message, MAX_FRAME)), now);
    }

    private static ExtensionWireContractProbe idle() throws IOException {
        var probe = new ExtensionWireContractProbe(10000);
        send(probe, PARENT, fixture("hello"), 0);
        send(probe, CHILD, fixture("ready"), 1);
        return probe;
    }

    @ParameterizedTest
    @ValueSource(strings = {"assertion-pass", "assertion-fail", "assertion-indeterminate", "import-result", "import-failure", "render-result"})
    void allThreePureContractsCompleteAMatchedConversation(String response) throws Exception {
        var probe = idle();
        String request = response.startsWith("assertion") ? "assertion-request"
                : response.startsWith("import") ? "import-request" : "render-request";
        send(probe, PARENT, fixture(request), 2);
        send(probe, CHILD, fixture(response), 3);
        send(probe, PARENT, fixture("shutdown"), 4);
        send(probe, CHILD, fixture("stopped"), 5);
        probe.eof();
        assertThat(probe.failed()).isFalse();
    }

    @Test
    void dtoComponentsRemainThePublishedDataWithoutConvenienceGetters() throws Exception {
        JsonNode assertion = fixture("assertion-request").path("data").path("input");
        JsonNode evidence = assertion.path("evidence");
        var snapshot = JSON.treeToValue(evidence, EvidenceSnapshot.class);
        assertThat(snapshot.asView().responseBody().orElseThrow()).isEqualTo("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        assertThat(JSON.readTree(JSON.writeValueAsBytes(snapshot))).isEqualTo(evidence);
        assertThat(JSON.readTree(JSON.writeValueAsBytes(JSON.treeToValue(assertion.get("context"), AssertionContext.class))))
                .isEqualTo(assertion.get("context"));
        for (String name : List.of("assertion-pass", "assertion-fail", "assertion-indeterminate")) {
            JsonNode value = fixture(name).path("data").path("value");
            var result = JSON.treeToValue(value, AssertionResult.class);
            assertThat(JSON.readTree(JSON.writeValueAsBytes(result))).isEqualTo(value);
            assertThat(EvidenceExport.assertion(result).details()).isEmpty();
        }
        for (String name : List.of("import-result", "import-failure")) {
            JsonNode value = fixture(name).path("data").path("value");
            var result = JSON.treeToValue(value, ImportResult.class);
            assertThat(result.isSuccess()).isEqualTo(name.equals("import-result"));
            assertThat(JSON.readTree(JSON.writeValueAsBytes(result))).isEqualTo(value);
        }
        JsonNode event = fixture("render-request").path("data").path("input").path("events").get(0);
        assertThat(JSON.readTree(JSON.writeValueAsBytes(JSON.treeToValue(event, RunEvent.class)))).isEqualTo(event);
    }

    static Stream<JsonNode> framingVectors() throws IOException {
        try (var input = ExtensionWireContractTest.class.getResourceAsStream("/extension-wire-v1/framing-vectors.json")) {
            return java.util.stream.StreamSupport.stream(JSON.readTree(input).spliterator(), false).toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("framingVectors")
    void literalByteVectorsFixTheFramingAndRefusalCodes(JsonNode vector) throws Exception {
        byte[] bytes = HexFormat.of().parseHex(vector.path("hex").asText());
        int maximum = vector.path("limit").asInt(MAX_FRAME);
        if (vector.has("error")) {
            assertThatThrownBy(() -> ExtensionWireContractProbe.readFrame(new ByteArrayInputStream(bytes), maximum))
                    .as(vector.path("name").asText()).isInstanceOf(ExtensionWireContractProbe.Refusal.class)
                    .hasMessage(vector.path("error").asText());
        } else {
            assertThat(ExtensionWireContractProbe.readFrame(new ByteArrayInputStream(bytes), maximum))
                    .isEqualTo(vector.get("value"));
            assertThat(ExtensionWireContractProbe.frame(vector.get("value"), maximum)).isEqualTo(bytes);
        }
    }

    @Test
    void fragmentedAndConcatenatedFramesPreserveUtf8AndBoundaries() throws Exception {
        byte[] one = ExtensionWireContractProbe.frame(fixture("hello"), MAX_FRAME);
        byte[] two = ExtensionWireContractProbe.frame(fixture("ready"), MAX_FRAME);
        byte[] combined = ByteBuffer.allocate(one.length + two.length).put(one).put(two).array();
        try (var fragments = new FilterInputStream(new ByteArrayInputStream(combined)) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        }) {
            assertThat(ExtensionWireContractProbe.readFrame(fragments, MAX_FRAME)).isEqualTo(fixture("hello"));
            assertThat(ExtensionWireContractProbe.readFrame(fragments, MAX_FRAME)).isEqualTo(fixture("ready"));
            assertThat(ExtensionWireContractProbe.readFrame(fragments, MAX_FRAME)).isNull();
        }
    }

    @Test
    void exactByteCeilingIsAcceptedAndOverflowReadsOnlyThePrefix() throws Exception {
        byte[] body = new byte[MAX_FRAME];
        Arrays.fill(body, (byte) ' ');
        body[0] = '{'; body[1] = '}';
        byte[] exact = ByteBuffer.allocate(MAX_FRAME + 4).putInt(MAX_FRAME).put(body).array();
        assertThat(ExtensionWireContractProbe.readFrame(new ByteArrayInputStream(exact), MAX_FRAME)).isEqualTo(JSON.readTree("{}"));
        try (var prefixOnly = new FilterInputStream(new ByteArrayInputStream(HexFormat.of().parseHex("00100001"))) {
            private int read;
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                if (read >= 4) throw new AssertionError("oversized payload was read or drained");
                int count = super.read(bytes, offset, length);
                if (count > 0) read += count;
                return count;
            }
        }) {
            assertThatThrownBy(() -> ExtensionWireContractProbe.readFrame(prefixOnly, MAX_FRAME))
                    .isInstanceOf(ExtensionWireContractProbe.Refusal.class).hasMessage("MESSAGE_TOO_LARGE");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"depth", "number", "nested-duplicate", "non-finite"})
    void boundedJsonRefusesAmbiguousOrExpensiveValues(String kind) {
        String payload = switch (kind) {
            case "depth" -> "{\"x\":".repeat(65) + "0" + "}".repeat(65);
            case "number" -> "{\"x\":" + "9".repeat(65) + "}";
            case "nested-duplicate" -> "{\"x\":{\"same\":1,\"same\":2}}";
            default -> "{\"x\":NaN}";
        };
        byte[] utf8 = payload.getBytes(StandardCharsets.UTF_8);
        byte[] frame = ByteBuffer.allocate(utf8.length + 4).putInt(utf8.length).put(utf8).array();
        assertThatThrownBy(() -> ExtensionWireContractProbe.readFrame(new ByteArrayInputStream(frame), MAX_FRAME))
                .isInstanceOf(ExtensionWireContractProbe.Refusal.class).hasMessage("MALFORMED_MESSAGE");
    }

    private static void refuses(ExtensionWireContractProbe probe, ExtensionWireContractProbe.Direction direction,
                                ObjectNode message, long now, String code) {
        assertThatThrownBy(() -> send(probe, direction, message, now))
                .isInstanceOf(ExtensionWireContractProbe.Refusal.class).hasMessage(code);
        assertThat(probe.failed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.0", "1.1"})
    void anUnofferedVersionNeverFallsBackSilently(String version) throws Exception {
        var hello = fixture("hello");
        ((ObjectNode) hello.get("data")).putArray("versions").add(version);
        refuses(new ExtensionWireContractProbe(10000), PARENT, hello, 0, "INCOMPATIBLE_VERSION");
        var probe = new ExtensionWireContractProbe(10000);
        send(probe, PARENT, fixture("hello"), 0);
        refuses(probe, CHILD, fixture("ready").put("version", version), 1, "INCOMPATIBLE_VERSION");
    }

    @Test
    void providerOrderIsNotAnIdentityOrCapabilityDifference() throws Exception {
        var hello = fixture("hello");
        ((ObjectNode) hello.get("data")).putArray("versions").add("1.1").add("1.0");
        var ready = fixture("ready");
        var list = (com.fasterxml.jackson.databind.node.ArrayNode) ready.path("data").path("providers");
        JsonNode first = list.remove(0); list.add(first);
        var probe = new ExtensionWireContractProbe(10000);
        send(probe, PARENT, hello, 0);
        send(probe, CHILD, ready, 1);
        assertThat(probe.failed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"connector", "fault", "secret-resolver"})
    void aHandshakeCannotSmuggleAnImpureSpi(String kind) throws Exception {
        var hello = fixture("hello");
        ((ObjectNode) hello.path("data").path("providers").get(0)).put("kind", kind);
        refuses(new ExtensionWireContractProbe(10000), PARENT, hello, 0, "UNSUPPORTED_PROVIDER");
    }

    @Test
    void identityAndNegotiatedLimitCannotBeWidened() throws Exception {
        var probe = new ExtensionWireContractProbe(10000);
        send(probe, PARENT, fixture("hello"), 0);
        var ready = fixture("ready");
        ((ObjectNode) ready.path("data").path("plugin")).put("id", "example.other");
        refuses(probe, CHILD, ready, 1, "IDENTITY_MISMATCH");
        probe = new ExtensionWireContractProbe(10000);
        var hello = fixture("hello");
        ((ObjectNode) hello.get("data")).put("maxFrameBytes", 1024);
        send(probe, PARENT, hello, 0);
        refuses(probe, CHILD, fixture("ready"), 1, "LIMIT_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "numeric-id", "leading-zero", "session", "provider", "version", "outcome"})
    void aBadReplyCannotCompleteThePendingAssertion(String mutation) throws Exception {
        var probe = idle();
        send(probe, PARENT, fixture("assertion-request"), 2);
        var reply = fixture("assertion-pass");
        String code = switch (mutation) {
            case "id" -> { reply.put("id", "2"); yield "ID_MISMATCH"; }
            case "numeric-id" -> { reply.put("id", 1); yield "MALFORMED_MESSAGE"; }
            case "leading-zero" -> { reply.put("id", "01"); yield "MALFORMED_MESSAGE"; }
            case "session" -> { reply.put("sessionId", "f".repeat(32)); yield "SESSION_MISMATCH"; }
            case "provider" -> {
                ((ObjectNode) reply.path("data").path("provider")).put("name", "other.status"); yield "ID_MISMATCH";
            }
            case "version" -> { reply.put("version", "2.0"); yield "INCOMPATIBLE_VERSION"; }
            default -> {
                ((ObjectNode) reply.path("data").path("value")).put("outcome", "PASSED"); yield "MALFORMED_MESSAGE";
            }
        };
        refuses(probe, CHILD, reply, 3, code);
    }

    @Test
    void noCallCanRunBeforeReadyOrOverlapAnotherCall() throws Exception {
        refuses(new ExtensionWireContractProbe(10000), PARENT, fixture("assertion-request"), 0, "INVALID_STATE");
        var probe = idle();
        send(probe, PARENT, fixture("assertion-request"), 2);
        refuses(probe, PARENT, fixture("import-request").put("id", "2"), 3, "INVALID_STATE");
    }

    @Test
    void completedIdsAreNotReusableAndLateResponsesPoisonTheSession() throws Exception {
        var probe = idle();
        send(probe, PARENT, fixture("assertion-request"), 2);
        send(probe, CHILD, fixture("assertion-pass"), 3);
        refuses(probe, PARENT, fixture("assertion-request"), 4, "ID_MISMATCH");
        probe = idle();
        send(probe, PARENT, fixture("assertion-request"), 2);
        refuses(probe, CHILD, fixture("assertion-pass"), 1002, "DEADLINE_EXCEEDED");
        refuses(probe, CHILD, fixture("assertion-pass"), 1003, "INVALID_STATE");
    }

    @Test
    void handshakeAndParentPhaseBudgetsAreAlsoAuthoritative() throws Exception {
        var probe = new ExtensionWireContractProbe(10000);
        send(probe, PARENT, fixture("hello"), 0);
        refuses(probe, CHILD, fixture("ready"), 5000, "DEADLINE_EXCEEDED");
        probe = new ExtensionWireContractProbe(5);
        send(probe, PARENT, fixture("hello"), 0);
        refuses(probe, CHILD, fixture("ready"), 5, "DEADLINE_EXCEEDED");
        probe = idle();
        refuses(probe, PARENT, fixture("assertion-request"), 9500, "DEADLINE_EXCEEDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-bodyComplete", "live-context", "bad-base64", "renderer-path", "unknown-envelope", "timeout-zero"})
    void payloadsArePlainExplicitDataAndNeverLiveHandles(String mutation) throws Exception {
        var probe = idle();
        var request = fixture(mutation.equals("renderer-path") ? "render-request" : "assertion-request");
        switch (mutation) {
            case "missing-bodyComplete" -> ((ObjectNode) request.path("data").path("input").path("evidence")).remove("bodyComplete");
            case "bad-base64" -> ((ObjectNode) request.path("data").path("input").path("evidence")).put("responseBody", "!");
            case "live-context" -> ((ObjectNode) request.path("data").path("input").path("context")).put("workspaceRoot", "/private");
            case "renderer-path" -> ((ObjectNode) request.path("data").path("input")).put("writer", "/output/report");
            case "unknown-envelope" -> request.put("@class", "example.NotADataObject");
            default -> ((ObjectNode) request.get("data")).put("timeoutMs", 0);
        }
        refuses(probe, PARENT, request, 2, "MALFORMED_MESSAGE");
    }

    @Test
    void ordinaryProviderFailureIsNotAPassingResultOrReusableSession() throws Exception {
        var probe = idle();
        send(probe, PARENT, fixture("assertion-request"), 2);
        send(probe, CHILD, fixture("error"), 3);
        assertThat(probe.failed()).isTrue();
        refuses(probe, PARENT, fixture("assertion-request").put("id", "2"), 4, "INVALID_STATE");
        var waiting = idle();
        assertThatThrownBy(waiting::eof).isInstanceOf(ExtensionWireContractProbe.Refusal.class).hasMessage("PEER_CLOSED");
    }

    @Test
    void malformedFramingPoisonsTheSessionToo() throws Exception {
        var probe = idle();
        assertThatThrownBy(() -> probe.receive(CHILD, new ByteArrayInputStream(new byte[4]), 2))
                .isInstanceOf(ExtensionWireContractProbe.Refusal.class).hasMessage("MALFORMED_FRAME");
        refuses(probe, PARENT, fixture("assertion-request"), 3, "INVALID_STATE");
    }

    @Test
    void utf8SizeIsBytesAndNotJavaCharacterCount() {
        var value = JSON.createObjectNode().put("text", "é".repeat(MAX_FRAME / 2));
        assertThat(value.path("text").asText().length()).isLessThan(MAX_FRAME);
        assertThatThrownBy(() -> ExtensionWireContractProbe.frame(value, MAX_FRAME))
                .isInstanceOf(ExtensionWireContractProbe.Refusal.class).hasMessage("MESSAGE_TOO_LARGE");
    }

    @Test
    void theNegotiatedCeilingAppliesToFollowingFrames() throws Exception {
        var probe = new ExtensionWireContractProbe(10000);
        var hello = fixture("hello");
        ((ObjectNode) hello.get("data")).put("maxFrameBytes", 2048);
        send(probe, PARENT, hello, 0);
        var ready = fixture("ready");
        ((ObjectNode) ready.get("data")).put("maxFrameBytes", 1024);
        send(probe, CHILD, ready, 1);
        assertThat(probe.frameLimit()).isEqualTo(1024);
        assertThatThrownBy(() -> probe.receive(CHILD,
                new ByteArrayInputStream(HexFormat.of().parseHex("00000401")), 2))
                .isInstanceOf(ExtensionWireContractProbe.Refusal.class).hasMessage("MESSAGE_TOO_LARGE");
        assertThat(probe.failed()).isTrue();
    }

    @Test
    void refusalDuringHandshakeIsTerminalWithoutSelectingAVersion() throws Exception {
        var probe = new ExtensionWireContractProbe(10000);
        send(probe, PARENT, fixture("hello"), 0);
        send(probe, CHILD, fixture("handshake-refusal"), 1);
        assertThat(probe.failed()).isTrue();
        refuses(probe, CHILD, fixture("ready"), 2, "INVALID_STATE");
    }

    @Test
    void readyCannotAdvertiseExtraApprovedFamilySelectors() throws Exception {
        var probe = new ExtensionWireContractProbe(10000);
        send(probe, PARENT, fixture("hello"), 0);
        var ready = fixture("ready");
        ((com.fasterxml.jackson.databind.node.ArrayNode) ready.path("data").path("providers"))
                .addObject().put("kind", "renderer").put("name", "other.json");
        refuses(probe, CHILD, ready, 1, "UNSUPPORTED_PROVIDER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspaceRoot", "allowedRefDomains"})
    void importContextDoesNotCarryTheParentFilesystemOrNetworkAuthority(String field) throws Exception {
        var probe = idle();
        var request = fixture("import-request");
        ((ObjectNode) request.path("data").path("input").path("context")).put(field, "synthetic");
        refuses(probe, PARENT, request, 2, "MALFORMED_MESSAGE");
    }

    @Test
    void importerDocumentLimitIsCheckedOnDecodedUtf8Bytes() throws Exception {
        var probe = idle();
        var request = fixture("import-request");
        ((ObjectNode) request.path("data").path("input").path("context")).put("maxDocSizeBytes", 1);
        refuses(probe, PARENT, request, 2, "MESSAGE_TOO_LARGE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"numeric-version", "fractional-status", "string-budget", "null-outcome", "render-path"})
    void scalarCoercionAndArbitraryOutputLocationsAreNotPartOfTheContract(String mutation) throws Exception {
        var probe = idle();
        if (mutation.equals("fractional-status") || mutation.equals("string-budget")) {
            var request = fixture("assertion-request");
            if (mutation.equals("fractional-status")) {
                ((ObjectNode) request.path("data").path("input").path("evidence")).put("statusCode", 200.5);
            } else {
                ((ObjectNode) request.get("data")).put("timeoutMs", "1000");
            }
            refuses(probe, PARENT, request, 2, "MALFORMED_MESSAGE");
        } else {
            boolean renderer = mutation.equals("render-path");
            send(probe, PARENT, fixture(renderer ? "render-request" : "assertion-request"), 2);
            var result = fixture(renderer ? "render-result" : "assertion-pass");
            if (mutation.equals("numeric-version")) result.put("version", 1.0);
            else if (renderer) ((ObjectNode) result.path("data").path("value")).put("path", "/output/report");
            else ((ObjectNode) result.path("data").path("value")).putNull("outcome");
            refuses(probe, CHILD, result, 3, "MALFORMED_MESSAGE");
        }
    }

    @Test
    void streamFailureAlsoPoisonsTheSessionWithoutExportingItsMessage() throws Exception {
        var probe = idle();
        try (var broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("SYNTHETIC_PRIVATE_IO"); }
        }) {
            assertThatThrownBy(() -> probe.receive(CHILD, broken, 2))
                    .isInstanceOf(ExtensionWireContractProbe.Refusal.class).hasMessage("PEER_CLOSED");
        }
        assertThat(probe.failed()).isTrue();
        refuses(probe, PARENT, fixture("assertion-request"), 3, "INVALID_STATE");
    }

    @Test
    void evenAnUncorrelatedErrorMustUseARepresentableId() throws Exception {
        var error = fixture("handshake-refusal").put("id", "9223372036854775808");
        refuses(new ExtensionWireContractProbe(10000), CHILD, error, 0, "ID_MISMATCH");
    }
}
