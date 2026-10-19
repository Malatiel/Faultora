package dev.faultora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpStdioServerTest {
    private List<JsonNode> exchange(String... messages) throws IOException {
        return exchangeBytes((String.join("\n", messages) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private List<JsonNode> exchangeBytes(byte[] messages) throws IOException {
        StringWriter output = new StringWriter();
        new McpStdioServer(McpFixtures.configuration()).serve(
                new ByteArrayInputStream(messages), new PrintWriter(output));
        List<JsonNode> replies = new ArrayList<>();
        for (String line : output.toString().lines().toList()) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(262_144);
            replies.add(McpJson.MAPPER.readTree(line));
        }
        return replies;
    }

    @Test
    void fixtureClientHandshakeAndToolDiscoveryUseOnlyMcpMessages() throws Exception {
        String initialize;
        JsonNode expected;
        try (var request = getClass().getResourceAsStream("/mcp/initialize-request.json");
             var response = getClass().getResourceAsStream("/mcp/initialize-result.json")) {
            initialize = new String(request.readAllBytes(), StandardCharsets.UTF_8).strip();
            expected = McpJson.MAPPER.readTree(response);
        }
        // The server reports the build's own version, not one written into a fixture.
        assertThat(dev.faultora.runtime.FaultoraVersion.current()).isNotEqualTo("0");
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("result").path("serverInfo"))
                .put("version", dev.faultora.runtime.FaultoraVersion.current());
        var replies = exchange(initialize, McpFixtures.READY,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertThat(replies).hasSize(2);
        assertThat(replies.getFirst()).isEqualTo(expected);
        var tools = replies.get(1).path("result").path("tools");
        assertThat(tools).hasSize(3);
        assertThat(tools.findValuesAsText("name"))
                .containsExactly("list_capabilities", "describe_catalog", "prepare_run");
        for (JsonNode tool : tools) {
            assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean(true)).isFalse();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true)).isFalse();
        }
    }

    @Test
    void unsupportedOfferReceivesTheSupportedRevisionForClientNegotiation() throws Exception {
        var reply = exchange(McpFixtures.INITIALIZE.replace("2025-11-25", "2026-07-28")).getFirst();
        assertThat(reply.path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
    }

    @Test
    void toolsRequireInitializationAndTheInitializedNotification() throws Exception {
        String list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        var replies = exchange(list, McpFixtures.INITIALIZE, list,
                McpFixtures.READY.substring(0, McpFixtures.READY.length() - 1) + ",\"params\":[]}", list,
                McpFixtures.READY, list, McpFixtures.INITIALIZE);
        assertThat(replies).hasSize(6);
        assertThat(replies.get(0).path("error").path("code").asInt()).isEqualTo(-32000);
        assertThat(replies.get(2).path("error").path("code").asInt()).isEqualTo(-32000);
        assertThat(replies.get(3).path("error").path("code").asInt()).isEqualTo(-32000);
        assertThat(replies.get(4).path("result").path("tools")).hasSize(3);
        assertThat(replies.get(5).path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void requestIdsArePreservedButNullFloatingAndOversizedIdsAreRefused() throws Exception {
        for (String id : List.of("-1", "9223372036854775807", "\"request-1\"")) {
            var response = exchange("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}").getFirst();
            assertThat(response.path("id")).isEqualTo(McpJson.MAPPER.readTree(id));
            assertThat(response.has("result")).isTrue();
        }
        for (String id : List.of("null", "1.5", "9223372036854775808", "\"" + "x".repeat(129) + "\"")) {
            var response = exchange("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}").getFirst();
            assertThat(response.path("id").isNull()).isTrue();
            assertThat(response.path("error").path("code").asInt()).isEqualTo(-32600);
        }
    }

    @Test
    void malformedJsonEnvelopeDuplicatePropertiesAndBatchesCannotReachTools() throws Exception {
        for (String message : List.of("{", "null", "[]", "{} {}",
                "{\"jsonrpc\":2.0,\"id\":1,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":\"SYNTHETIC_PRIVATE_VALUE\"}")) {
            var response = exchange(message).getFirst();
            assertThat(response.has("error")).isTrue();
            assertThat(response.toString()).doesNotContain(McpFixtures.MARKER);
        }
    }

    @Test
    void invalidUtf8IsRefusedAndTheFollowingCompleteFrameStillWorks() throws Exception {
        byte[] ping = "\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[ping.length + 2];
        input[0] = (byte) 0xc3;
        input[1] = (byte) 0x28;
        System.arraycopy(ping, 0, input, 2, ping.length);
        var replies = exchangeBytes(input);
        assertThat(replies).hasSize(2);
        assertThat(replies.getFirst().path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(replies.get(1).path("id").asInt()).isEqualTo(2);
    }

    @Test
    void oversizedOrTruncatedFramesStopTheConnection() throws Exception {
        var oversized = exchange("x".repeat(McpJson.MAX_REQUEST_BYTES + 1),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
        assertThat(oversized).hasSize(1);
        assertThat(oversized.getFirst().path("error").path("code").asInt()).isEqualTo(-32700);
        var truncated = exchangeBytes(McpFixtures.INITIALIZE.getBytes(StandardCharsets.UTF_8));
        assertThat(truncated).hasSize(1);
        assertThat(truncated.getFirst().has("error")).isTrue();
    }

    @Test
    void deepJsonIsRefusedWithinTheParserLimit() throws Exception {
        var response = exchange("[".repeat(65) + "0" + "]".repeat(65)).getFirst();
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void frameBoundsCountUtf8BytesAndEmptyFramesAreParseErrors() throws Exception {
        assertThat(exchange("").getFirst().path("error").path("code").asInt()).isEqualTo(-32700);
        String multibyte = "я".repeat(McpJson.MAX_REQUEST_BYTES / 2 + 1);
        assertThat(multibyte.length()).isLessThan(McpJson.MAX_REQUEST_BYTES);
        assertThat(exchange(multibyte).getFirst().path("error").path("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void unknownMethodsExecutionToolsAndInvalidCursorsHaveNamedProtocolErrors() throws Exception {
        var replies = exchange(McpFixtures.INITIALIZE, McpFixtures.READY,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"start_run\",\"arguments\":{\"confirmed\":true}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\",\"params\":{\"cursor\":\"unknown\"}}");
        assertThat(replies.get(1).path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(replies.get(2).path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(replies.get(3).path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void toolCallNotificationsNeitherReplyNorAllocatePreparations() throws Exception {
        var call = McpJson.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", "tools/call");
        var params = call.putObject("params").put("name", "prepare_run");
        var arguments = params.putObject("arguments").put("scenario", McpFixtures.SCENARIO);
        arguments.putArray("catalogIds").add("demo");
        String notifications = (call + "\n").repeat(40);
        call.put("id", 2);
        var replies = exchange(McpFixtures.INITIALIZE, McpFixtures.READY, notifications + call);
        assertThat(replies).hasSize(2);
        assertThat(replies.get(1).path("result").path("isError").asBoolean()).isFalse();
        assertThat(replies.get(1).path("result").path("structuredContent").path("preparedId").asText()).isNotBlank();
    }

    @Test
    void outputFailureIsObservedWithoutWritingAnExceptionToStdout() {
        Writer broken = new Writer() {
            public void write(char[] characters, int offset, int length) throws IOException {
                throw new IOException(McpFixtures.MARKER);
            }

            public void flush() {
            }

            public void close() {
            }
        };
        assertThatThrownBy(() -> new McpStdioServer(McpFixtures.configuration()).serve(
                new ByteArrayInputStream((McpFixtures.INITIALIZE + "\n").getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(broken))).isInstanceOf(IOException.class).hasMessageNotContaining(McpFixtures.MARKER);
    }
}
