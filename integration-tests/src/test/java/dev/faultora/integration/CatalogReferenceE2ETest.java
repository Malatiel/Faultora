package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.examples.catalog.CatalogApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogReferenceE2ETest {
    private static final Path ROOT = Path.of(System.getProperty("faultora.projectRoot"));
    private static final Path EXAMPLE = ROOT.resolve("examples/catalog-service");
    private static final Path SCENARIO = EXAMPLE.resolve("scenarios/contract.yaml");
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test
    void theSameValidateAndPreviewCommandsPrepareTheCatalogWithoutApiCalls() throws Exception {
        try (var api = new CatalogApi()) {
            api.start();
            List<String> inputs = List.of("--scenario", SCENARIO.toString(), "--openapi", EXAMPLE.resolve("openapi.yaml").toString());
            List<String> validate = new ArrayList<>(List.of("validate"));
            validate.addAll(inputs);
            assertThat(command(validate).exit()).isZero();
            List<String> preview = new ArrayList<>(List.of("test", "--preview"));
            preview.addAll(inputs);
            preview.addAll(List.of("--target", api.baseUrl(), "--allow-private", "--allow-destructive"));
            Result prepared = command(preview);
            assertThat(prepared.exit()).as(prepared.output()).isZero();
            JsonNode plan = JSON.readTree(prepared.stdout().trim());
            assertThat(plan.isObject()).isTrue();
            assertThat(api.requests()).isZero();
            assertThat(api.collectionCount()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryAssertionsDistinguishAValidQueryFromAFilterDefectAndCleanupBoth(boolean ignoreFilter) throws Exception {
        try (var api = new CatalogApi(ignoreFilter)) {
            api.start();
            Path output = directory.resolve("run");
            int exit = PackagedExampleCli.run(output, SCENARIO, List.of(
                    "--openapi", EXAMPLE.resolve("openapi.yaml").toString(),
                    "--target", api.baseUrl(), "--allow-destructive"));
            assertThat(exit).isEqualTo(ignoreFilter ? 1 : 0);
            Map<String, String> outcomes = new LinkedHashMap<>();
            List<JsonNode> operationEvents = new ArrayList<>();
            List<String> terminal = new ArrayList<>();
            for (JsonNode event : JSON.readTree(Files.readString(output.resolve("report.json")))) {
                String kind = event.path("eventType").asText();
                if (kind.equals("ASSERTION_EVALUATED")) {
                    assertThat(outcomes.put(event.path("nodeId").asText(), event.path("outcome").asText())).isNull();
                }
                if (kind.equals("NODE_COMPLETED") && event.path("statusCode").asInt() != 0) operationEvents.add(event);
                if (kind.equals("RUN_COMPLETED") || kind.equals("RUN_FAILED")) terminal.add(kind);
            }
            assertThat(outcomes).hasSize(6);
            assertThat(outcomes.values()).allSatisfy(value -> assertThat(value).isIn("PASS", "FAIL"));
            assertThat(outcomes.entrySet().stream().filter(entry -> entry.getValue().equals("FAIL"))
                    .map(Map.Entry::getKey).toList()).containsExactlyInAnyOrderElementsOf(
                    ignoreFilter ? List.of("query-count", "query-keys", "query-pages") : List.of());
            assertThat(outcomes).containsEntry("query-status", "PASS").containsEntry("query-schema", "PASS");
            assertThat(operationEvents).extracting(event -> event.path("statusCode").asInt()).containsExactly(200, 200, 204);
            assertThat(terminal).containsExactly(ignoreFilter ? "RUN_FAILED" : "RUN_COMPLETED");
            assertThat(api.requests()).isEqualTo(3);
            assertThat(api.collectionCount()).as("cleanup after either assertion verdict").isZero();
        }
    }

    private Result command(List<String> arguments) throws Exception {
        Path log = Files.createTempFile(directory, "cli-", ".log");
        Path errors = Files.createTempFile(directory, "cli-stderr-", ".log");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar",
                Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath().toString()));
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectOutput(log.toFile()).redirectError(errors.toFile()).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("catalog CLI command completed").isTrue();
            return new Result(process.exitValue(), Files.readString(log), Files.readString(errors));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private record Result(int exit, String stdout, String stderr) {
        String output() { return stdout + stderr; }
    }
}
