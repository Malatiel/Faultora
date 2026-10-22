package dev.faultora.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real stdout/stderr of the release JAR, including console report rendering. */
class MachineDiagnosticsE2ETest {
    private static final String MARKER = "SYNTHETIC_PRIVATE_MACHINE_VALUE";
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"pass", "fail", "indeterminate", "node-failed", "destination-blocked", "preview", "preview-refused",
            "report-failure", "artifact-failure", "owned-artifact", "invalid-yaml", "invalid-structure", "input-unknown",
            "input-required", "catalog-refused", "missing-scenario", "missing-catalog", "invalid-option", "invalid-format"})
    void machineModeProducesOnlyOneSafeJsonResultAndPreservesExitCodes(String mode) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Path output = directory.resolve("reports");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            if (mode.equals("report-failure")) {
                // Ownership has already been acquired; fail the later report write.
                Files.createDirectories(output.resolve("report.json"));
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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
            Path scenario = directory.resolve("scenario.yaml");
            String source = """
                    apiVersion: faultora.dev/v1
                    kind: Scenario
                    metadata: {name: synthetic-machine}
                    execute:
                      - id: read
                        operationId: read
                    assertions:
                      - id: check
                        assertionType: %s
                        targetStep: read
                        params: %s
                    """.formatted(mode.equals("indeterminate") ? "header" : "jsonpath",
                    mode.equals("indeterminate") ? "{name: Authorization, exists: false}"
                            : "{path: value, equals: '" + (mode.equals("fail") ? "public-expected" : MARKER) + "'}");
            if (mode.equals("preview-refused")) {
                source += "faults:\n  - id: inject\n    faultType: network-reset\n    duration: 1s\n";
            }
            if (mode.equals("invalid-yaml")) {
                source = "apiVersion: [" + MARKER;
            }
            if (mode.equals("invalid-structure")) {
                source += "timeout: " + MARKER + "\n";
            }
            if (mode.equals("input-required")) {
                source += "inputs:\n  account:\n    type: string\n    required: true\n";
            }
            if (mode.equals("node-failed")) {
                source = source.replace("    operationId: read\n", "    operationId: read\n    inputs: {body: '{{inputs.missing}}'}\n");
            }
            Files.writeString(scenario, source);
            if (mode.equals("owned-artifact")) {
                Files.createDirectories(output);
                Files.writeString(output.resolve("events.ndjson"), "synthetic old journal");
            }
            if (mode.equals("artifact-failure")) {
                Files.writeString(output, "synthetic artifact blocker");
            }
            if (mode.equals("missing-scenario")) {
                scenario = directory.resolve(MARKER + ".yaml");
            }
            List<String> args = new ArrayList<>(List.of("--diagnostics=json", "test", "--scenario", scenario.toString(),
                    "--target", "http://127.0.0.1:" + server.getAddress().getPort() + "/" + MARKER,
                    "--allow-private", "--seed", "42", "--format", "console,json,html,junit", "--output", output.toString()));
            if (mode.equals("destination-blocked")) {
                args.remove("--allow-private");
            }
            if (mode.startsWith("preview")) {
                args.add("--preview");
            }
            if (mode.equals("input-unknown")) {
                args.addAll(List.of("--input", "unknown=" + MARKER));
            }
            if (mode.equals("invalid-option")) {
                args.add("--" + MARKER);
            }
            if (mode.equals("invalid-format")) {
                args.addAll(List.of("--format", MARKER));
            }
            if (mode.equals("catalog-refused") || mode.equals("missing-catalog")) {
                Path catalog = directory.resolve(MARKER + ".openapi.yaml");
                if (mode.equals("catalog-refused")) {
                    Files.writeString(catalog, "private: " + MARKER);
                }
                args.addAll(List.of("--openapi", catalog.toString()));
            }
            Path stdout = directory.resolve("stdout.txt");
            Path stderr = directory.resolve("stderr.txt");
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", System.getProperty("faultora.cliJar")));
            command.addAll(args);
            Process cli = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            try {
                assertThat(cli.waitFor(30, TimeUnit.SECONDS)).as("CLI finished").isTrue();
            } finally {
                if (cli.isAlive()) {
                    cli.destroyForcibly();
                    assertThat(cli.waitFor(5, TimeUnit.SECONDS)).as("CLI teardown finished").isTrue();
                }
            }
            int expectedExit = switch (mode) {
                case "pass", "preview" -> 0;
                case "fail", "indeterminate", "node-failed", "destination-blocked" -> 1;
                case "report-failure", "artifact-failure", "owned-artifact", "missing-scenario", "missing-catalog" -> 3;
                default -> 2;
            };
            String text = Files.readString(stdout);
            String technical = Files.readString(stderr);
            assertThat(cli.exitValue()).as(technical).isEqualTo(expectedExit);
            var result = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            assertThat(result.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(result.path("command").asText()).isEqualTo("test");
            assertThat(result.path("exitCode").asInt()).isEqualTo(expectedExit);
            assertThat(text).doesNotContain(MARKER, directory.toString(), "Running scenario:", "Report written:", "[INFO]", "Exception");
            assertThat(technical).doesNotContain(MARKER);
            String expectedCode = switch (mode) {
                case "fail" -> "ASSERTION_FAILED";
                case "indeterminate" -> "ASSERTION_INDETERMINATE";
                case "node-failed" -> "NODE_FAILED";
                case "destination-blocked" -> "DESTINATION_BLOCKED";
                case "preview-refused" -> "PLAN_REFUSED";
                case "report-failure" -> "REPORT_WRITE_FAILED";
                case "artifact-failure", "owned-artifact" -> "ARTIFACT_WRITE_FAILED";
                case "missing-scenario", "missing-catalog" -> "FILE_READ_FAILED";
                case "catalog-refused" -> "CATALOG_IMPORT_FAILED";
                case "invalid-yaml", "invalid-structure" -> "SCENARIO_INVALID";
                case "input-unknown" -> "INPUT_UNKNOWN";
                case "input-required" -> "INPUT_REQUIRED";
                case "invalid-option" -> "INVALID_ARGUMENT";
                case "invalid-format" -> "CAPABILITY_UNAVAILABLE";
                default -> null;
            };
            if (expectedCode != null) {
                List<String> codes = new ArrayList<>();
                result.path("diagnostics").forEach(diagnostic -> codes.add(diagnostic.path("code").asText()));
                assertThat(codes).contains(expectedCode);
            }
            if (mode.equals("fail") || mode.equals("indeterminate")) {
                assertThat(result.path("diagnostics").get(0).path("node").asText()).isEqualTo("check");
            }
            if (mode.startsWith("preview")) {
                assertThat(result.path("preview").path("compiled").asBoolean()).isEqualTo(mode.equals("preview"));
                assertThat(output).doesNotExist();
            }
            if (expectedExit == 2 || mode.startsWith("preview") || mode.startsWith("missing")
                    || mode.equals("artifact-failure") || mode.equals("owned-artifact") || mode.startsWith("node-")
                    || mode.equals("destination-blocked")) {
                assertThat(requests).hasValue(0);
            } else {
                assertThat(requests).hasValue(1);
            }
            if (mode.equals("pass") || mode.equals("fail") || mode.equals("indeterminate")) {
                for (String name : List.of("events.ndjson", "report.json", "report.html", "report.xml")) {
                    assertThat(output.resolve(name)).isRegularFile();
                    assertThat(Files.readString(output.resolve(name))).doesNotContain(MARKER);
                }
            }
            if (mode.equals("owned-artifact")) {
                assertThat(Files.readString(output.resolve("events.ndjson"))).isEqualTo("synthetic old journal");
                assertThat(output.resolve("run-owner")).doesNotExist();
            }
        } finally {
            server.stop(0);
        }
    }
}
