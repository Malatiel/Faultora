package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpPreparationE2ETest {
    @TempDir Path directory;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MARKER = "SYNTHETIC_PRIVATE_MCP_VALUE";

    private String call(int id, String name, JsonNode arguments) throws Exception {
        var request = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", "tools/call");
        var params = request.putObject("params").put("name", name);
        params.set("arguments", arguments);
        return MAPPER.writeValueAsString(request);
    }

    @Test
    void packagedStdioClientCanInspectAndPrepareWithoutExecutingOrLeakingSources() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        target.start();
        try {
            Files.writeString(directory.resolve("api.yaml"), """
                    openapi: 3.0.3
                    info: {title: synthetic, version: '1'}
                    servers:
                      - url: http://127.0.0.1:%d/%s
                    paths:
                      /read:
                        get:
                          operationId: read
                          responses: {'200': {description: synthetic}}
                    """.formatted(target.getAddress().getPort(), MARKER));
            Path config = directory.resolve("operator.json");
            Files.writeString(config, """
                    {"catalogs":[{"id":"demo","family":"openapi","path":"api.yaml"}],
                    "allowedFaultTypes":["http-latency"]}
                    """);
            String scenario = """
                    apiVersion: faultora.dev/v1
                    kind: Scenario
                    metadata: {name: synthetic}
                    faults:
                      - id: latency
                        faultType: http-latency
                        duration: 1s
                        params: {delayMs: 1}
                    execute:
                      - id: read
                        type: operation
                        operationId: read
                    """;
            var prepare = MAPPER.createObjectNode().put("scenario", scenario).put("seed", 42);
            prepare.putArray("catalogIds").add("demo");
            List<String> frames = List.of(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"fixture-client\",\"version\":\"1\"}}}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                    call(3, "list_capabilities", MAPPER.createObjectNode()).replace("\"id\":3", "\"id\":\"запрос-3\""),
                    call(4, "describe_catalog", MAPPER.createObjectNode().put("catalogId", "demo")),
                    call(5, "prepare_run", prepare),
                    call(6, "describe_catalog", MAPPER.createObjectNode().put("catalogId", "../" + MARKER)),
                    call(7, "start_run", MAPPER.createObjectNode().put("confirmed", true)));
            Path stdin = directory.resolve("requests.ndjson");
            Path stdout = directory.resolve("stdout.ndjson");
            Path stderr = directory.resolve("stderr.txt");
            Files.writeString(stdin, String.join("\n", frames) + "\n", StandardCharsets.UTF_8);
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Process cli = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar",
                    System.getProperty("faultora.cliJar"), "mcp", "--config", config.toString())
                    .directory(directory.toFile()).redirectInput(stdin.toFile())
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            try {
                assertThat(cli.waitFor(30, TimeUnit.SECONDS)).as("MCP EOF shutdown").isTrue();
                assertThat(cli.exitValue()).isZero();
            } finally {
                if (cli.isAlive()) {
                    cli.destroyForcibly();
                    assertThat(cli.waitFor(5, TimeUnit.SECONDS)).as("MCP teardown").isTrue();
                }
            }
            List<JsonNode> replies = new ArrayList<>();
            for (String line : Files.readAllLines(stdout)) {
                replies.add(MAPPER.readTree(line));
            }
            assertThat(replies).hasSize(7);
            assertThat(replies.get(0).path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
            assertThat(replies.get(1).path("result").path("tools")).hasSize(3);
            assertThat(replies.get(2).path("id").asText()).isEqualTo("запрос-3");
            assertThat(replies.get(4).path("result").path("isError").asBoolean()).isFalse();
            assertThat(replies.get(4).path("result").path("structuredContent")
                    .path("preview").path("compiled").asBoolean()).isTrue();
            assertThat(replies.get(5).path("result").path("structuredContent").path("code").asText())
                    .isEqualTo("UNKNOWN_CATALOG");
            assertThat(replies.get(6).path("error").path("code").asInt()).isEqualTo(-32602);
            assertThat(Files.readString(stdout)).doesNotContain(MARKER, directory.toString(), "127.0.0.1");
            assertThat(Files.readString(stderr)).doesNotContain(MARKER, directory.toString(), "127.0.0.1");
            assertThat(requests).hasValue(0);
            try (var files = Files.list(directory)) {
                assertThat(files.map(path -> path.getFileName().toString()))
                        .containsExactlyInAnyOrder("api.yaml", "operator.json", "requests.ndjson", "stdout.ndjson", "stderr.txt");
            }
        } finally {
            target.stop(0);
        }
    }
}
