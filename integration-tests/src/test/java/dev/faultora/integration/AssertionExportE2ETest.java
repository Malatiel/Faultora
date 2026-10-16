package dev.faultora.integration;

import com.sun.net.httpserver.HttpServer;
import dev.faultora.cli.FaultoraCli;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionExportE2ETest {
    private static final String MARKER = "SYNTHETIC_PRIVATE_ASSERTION_VALUE";
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"json-pass", "json-fail", "header-pass", "header-fail", "header-withheld",
            "schema-pass", "schema-fail", "poll-pass", "poll-fail"})
    void internalAssertionsDecideWithoutExportingValuesToAnyReportOrDiagnostics(String mode) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Private", MARKER);
            exchange.getResponseHeaders().add("Authorization", MARKER);
            byte[] body = ("{\"value\":\"" + MARKER + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            Path openapi = directory.resolve("api.yaml");
            Files.writeString(openapi, """
                    openapi: 3.0.3
                    info: {title: Synthetic, version: '1'}
                    servers:
                      - url: http://127.0.0.1
                    paths:
                      /evidence:
                        get:
                          operationId: read
                          responses:
                            '200':
                              description: Evidence
                              content:
                                application/json:
                                  schema:
                                    type: object
                                    required: [value]
                                    properties:
                                      value:
                                        type: %s
                    """.formatted(mode.equals("schema-fail") ? "integer" : "string"));
            boolean passing = mode.endsWith("pass");
            String type = mode.startsWith("header") ? "header" : mode.startsWith("schema") ? "schema" : "jsonpath";
            String params = switch (type) {
                case "header" -> mode.equals("header-withheld") ? "{name: Authorization, exists: false}"
                        : "{name: x-private, equals: '" + (passing ? MARKER : "public-expected") + "'}";
                case "schema" -> "{}";
                default -> "{path: value, equals: '" + (passing ? MARKER : "public-expected") + "'}";
            };
            String execute = mode.startsWith("poll") ? """
                    execute:
                      - id: poll
                        type: eventually
                        timeout: 2s
                        interval: 1s
                        steps:
                          - id: read
                            type: operation
                            operationId: read
                        until:
                          - assertionType: jsonpath
                            params: %s
                    """.formatted(params) : """
                    execute:
                      - id: read
                        type: operation
                        operationId: read
                    assertions:
                      - id: check
                        assertionType: %s
                        targetStep: read
                        params: %s
                    """.formatted(type, params);
            Path scenario = directory.resolve("scenario.yaml");
            Files.writeString(scenario, "apiVersion: faultora.dev/v1\nkind: Scenario\nmetadata: {name: synthetic-privacy}\n" + execute);
            Path output = directory.resolve("reports");
            Path diagnosticsFile = directory.resolve("diagnostics.txt");
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Process cli = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", System.getProperty("faultora.cliJar"),
                    "test", "--scenario", scenario.toString(), "--openapi", openapi.toString(), "--target",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/" + MARKER,
                    "--allow-private", "--seed", "42", "--format", "console,json,html,junit", "--output", output.toString())
                    .redirectErrorStream(true).redirectOutput(diagnosticsFile.toFile()).start();
            try {
                assertThat(cli.waitFor(30, TimeUnit.SECONDS)).as("CLI finished").isTrue();
            } finally {
                if (cli.isAlive()) {
                    cli.destroyForcibly();
                    assertThat(cli.waitFor(5, TimeUnit.SECONDS)).as("CLI teardown finished").isTrue();
                }
            }
            String diagnostics = Files.readString(diagnosticsFile);
            assertThat(cli.exitValue()).as(diagnostics).isEqualTo(passing ? FaultoraCli.EXIT_PASS : FaultoraCli.EXIT_TEST_FAILURE);
            assertThat(diagnostics).contains("Result:", "Nodes").doesNotContain(MARKER);
            for (String file : List.of("events.ndjson", "report.json", "report.html", "report.xml")) {
                assertThat(Files.readString(output.resolve(file))).as(file).doesNotContain(MARKER);
            }
            String events = Files.readString(output.resolve("events.ndjson"));
            assertThat(events).contains("ASSERTION_EVALUATED");
            if (mode.equals("header-withheld")) {
                assertThat(events).contains("INDETERMINATE");
            } else if (!passing) {
                assertThat(events).contains("\"outcome\":\"FAIL\"");
            }
        } finally {
            server.stop(0);
        }
    }
}
