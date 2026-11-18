package dev.faultora.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.engine.journal.RunEventReader;
import dev.faultora.model.events.RunEvent;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioMigrationE2ETest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String SCENARIO = """
            # A retained comment may mention faultora.dev/v1alpha1.
            apiVersion: 'faultora.dev/v1alpha1' # keep this comment
            kind: Scenario
            metadata:
              name: migration-check
            execute:
              - id: read
                operationId: read
            assertions:
              - id: check-status
                assertionType: status
                targetStep: read
                params: {expected: 200}
            """;
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {200, 201})
    void migratingTheVersionTokenPreservesThePreviewAndTheExecutedVerdict(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                exchange.sendResponseHeaders(status, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            Path scenario = Files.writeString(directory.resolve("scenario.yaml"), SCENARIO);
            String target = "http://127.0.0.1:" + server.getAddress().getPort() + "/read";
            int expectedExit = status == 200 ? 0 : 1;
            JsonNode before = preview("before", scenario, target);
            assertThat(before.path("diagnostics")).anySatisfy(diagnostic -> {
                assertThat(diagnostic.path("code").asText()).isEqualTo("SCENARIO_WARNING");
                assertThat(diagnostic.path("severity").asText()).isEqualTo("WARNING");
            });
            assertThat(requests).hasValue(0);
            Verdict original = run("before", scenario, target, expectedExit);
            assertThat(requests).hasValue(1);

            invoke("dry-run", 0, List.of("migrate", scenario.toString()));
            assertThat(Files.readString(scenario)).isEqualTo(SCENARIO);
            invoke("write", 0, List.of("migrate", scenario.toString(), "--write"));
            assertThat(Files.readString(scenario)).isEqualTo(
                    SCENARIO.replace("apiVersion: 'faultora.dev/v1alpha1'", "apiVersion: 'faultora.dev/v1'"));

            JsonNode after = preview("after", scenario, target);
            assertThat(after.path("diagnostics")).isEmpty();
            assertThat(after.path("preview").path("actions")).isEqualTo(before.path("preview").path("actions"));
            assertThat(requests).hasValue(1);

            Verdict migrated = run("after", scenario, target, expectedExit);
            assertThat(requests).hasValue(2);
            assertThat(original).isEqualTo(new Verdict(status, status == 200 ? "PASS" : "FAIL",
                    status == 200 ? "RUN_COMPLETED" : "RUN_FAILED"));
            assertThat(migrated).isEqualTo(original);
        } finally {
            server.stop(0);
        }
    }

    private JsonNode preview(String phase, Path scenario, String target) throws Exception {
        Path output = directory.resolve(phase + "-preview-output");
        JsonNode result = invoke(phase + "-preview", 0, testArguments(scenario, target, output, "--preview"));
        assertThat(result.path("preview").path("compiled").asBoolean()).isTrue();
        assertThat(result.path("preview").path("actions")).anySatisfy(action -> {
            assertThat(action.path("nodeId").asText()).isEqualTo("read");
            assertThat(action.path("operationId").asText()).isEqualTo("read");
        });
        assertThat(output).doesNotExist();
        return result;
    }

    private Verdict run(String phase, Path scenario, String target, int expectedExit) throws Exception {
        Path output = directory.resolve(phase + "-output");
        invoke(phase + "-run", expectedExit, testArguments(scenario, target, output));
        var events = JSON.createArrayNode();
        var known = new ArrayList<RunEvent>();
        for (String line : Files.readAllLines(output.resolve("events.ndjson"))) {
            events.add(JSON.readTree(line));
            RunEventReader.readLine(line).ifPresent(known::add);
        }
        assertThat(JSON.readTree(Files.readString(output.resolve("report.json")))).isEqualTo(events);
        assertThat(known.getFirst()).isInstanceOf(RunEvent.RunStarted.class);
        var operation = known.stream().filter(RunEvent.NodeCompleted.class::isInstance)
                .map(RunEvent.NodeCompleted.class::cast).filter(node -> node.nodeId().value().equals("read")).findFirst()
                .orElseThrow();
        var assertion = known.stream().filter(RunEvent.AssertionEvaluated.class::isInstance)
                .map(RunEvent.AssertionEvaluated.class::cast).filter(node -> node.nodeId().value().equals("check-status"))
                .findFirst().orElseThrow();
        return new Verdict(operation.statusCode(), assertion.outcome(), known.getLast().eventType());
    }

    private static List<String> testArguments(Path scenario, String target, Path output, String... extra) {
        var arguments = new ArrayList<>(List.of("test", "--scenario", scenario.toString(), "--target", target,
                "--allow-private", "--format", "json", "--output", output.toString(), "--seed", "42"));
        arguments.addAll(List.of(extra));
        return arguments;
    }

    private JsonNode invoke(String name, int expectedExit, List<String> arguments) throws Exception {
        Path stdout = directory.resolve(name + ".stdout");
        Path stderr = directory.resolve(name + ".stderr");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", System.getProperty("faultora.cliJar"), "--diagnostics=json"));
        command.addAll(arguments);
        int exit = BoundedProcess.run(new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()), Duration.ofSeconds(30), process -> { });
        assertThat(exit).as(Files.readString(stderr)).isEqualTo(expectedExit);
        JsonNode result = JSON.readTree(Files.readString(stdout));
        assertThat(result.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(result.path("command").asText()).isEqualTo(arguments.getFirst());
        assertThat(result.path("exitCode").asInt()).isEqualTo(expectedExit);
        return result;
    }

    private record Verdict(int status, String assertion, String terminal) { }
}
