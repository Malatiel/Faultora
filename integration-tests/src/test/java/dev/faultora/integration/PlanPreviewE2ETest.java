package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the packaged executable and its actual stdout, not an injected CLI writer. */
class PlanPreviewE2ETest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"operation", "network-fault", "fault-refused", "invalid-structure", "invalid-duration"})
    void previewDoesNotReachTargetsActivateFaultsOrChangeArtifacts(String mode) throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchanges.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String marker = "SYNTHETIC_PRIVATE_PREVIEW_VALUE";
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + marker;
            Path scenario = directory.resolve("scenario.yaml");
            String content = """
                    apiVersion: faultora.dev/v1
                    kind: Scenario
                    metadata: {name: preview}
                    execute:
                      - id: read
                        operationId: read
                        inputs: {body: '%s'}
                    """.formatted(marker);
            if (mode.contains("fault")) {
                content += """
                        faults:
                          - id: inject
                            faultType: network-reset
                            targetScope: '%s'
                            duration: 1s
                            params: {privateValue: '%s'}
                        """.formatted(marker, marker);
            }
            if (mode.equals("invalid-structure")) {
                content += "  - id: read\n    type: wait\n    timeout: 1ms\n";
            }
            if (mode.equals("invalid-duration")) {
                content += "timeout: " + marker + "\n";
            }
            Files.writeString(scenario, content);
            Path output = Files.createDirectory(directory.resolve("reports"));
            Path sentinel = output.resolve("events.ndjson");
            Files.writeString(sentinel, "previous run must survive");
            Path stdout = directory.resolve("stdout.txt");
            Path stderr = directory.resolve("stderr.txt");
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", System.getProperty("faultora.cliJar"),
                    "test", "--preview", "--scenario", scenario.toString(), "--target", endpoint,
                    "--allow-private", "--auth-secret-id", "unresolved-preview-credential",
                    "--seed", "42", "--format", "console,json,html,junit", "--output", output.toString()));
            if (mode.equals("network-fault")) {
                command.addAll(List.of("--toxiproxy-url", endpoint));
            }
            Process cli = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            try {
                assertThat(cli.waitFor(30, TimeUnit.SECONDS)).as("preview finished").isTrue();
            } finally {
                if (cli.isAlive()) {
                    cli.destroyForcibly();
                    assertThat(cli.waitFor(5, TimeUnit.SECONDS)).as("CLI teardown finished").isTrue();
                }
            }
            boolean compiled = mode.equals("operation") || mode.equals("network-fault");
            String text = Files.readString(stdout);
            assertThat(cli.exitValue()).as(Files.readString(stderr)).isEqualTo(compiled ? 0 : 2);
            var preview = new ObjectMapper().readTree(text);
            assertThat(preview.path("compiled").asBoolean()).isEqualTo(compiled);
            assertThat(preview.path("budgets").path("maxRequests").asInt()).isEqualTo(1000);
            assertThat(text).doesNotContain(marker, endpoint, "unresolved-preview-credential");
            assertThat(exchanges).hasValue(0);
            assertThat(Files.readString(sentinel)).isEqualTo("previous run must survive");
            try (var files = Files.list(output)) {
                assertThat(files).containsExactly(sentinel);
            }
            if (compiled) {
                assertThat(preview.path("actions").get(0).path("operationId").asText()).isEqualTo("read");
            } else {
                assertThat(preview.path("diagnostics").isEmpty()).isFalse();
            }
            if (mode.equals("network-fault")) {
                assertThat(preview.path("faults").get(0).path("type").asText()).isEqualTo("network-reset");
                assertThat(preview.path("capabilities").path("faultTypes").toString()).contains("network-reset");
            } else {
                assertThat(preview.path("capabilities").path("faultTypes").toString()).doesNotContain("network-reset");
            }
        } finally {
            server.stop(0);
        }
    }
}
