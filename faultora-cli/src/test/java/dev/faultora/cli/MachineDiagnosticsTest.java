package dev.faultora.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.identifier.RunId;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MachineDiagnosticsTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String MARKER = "SYNTHETIC_PRIVATE_DIAGNOSTIC_VALUE";
    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata: {name: synthetic}
            execute:
              - id: pause
                type: wait
                timeout: 1ms
            """;
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"test", "validate", "discover", "init", "runner", "health", "migrate", "diagnose"})
    void everyCommandReportsInvalidArgumentsWithoutHumanText(String command) throws Exception {
        var result = invoke(command, "--" + MARKER);
        assertEnvelope(result, 2);
        assertThat(result.json().path("command").asText()).isEqualTo(command);
        assertThat(result.json().path("diagnostics").get(0).path("code").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(result.stderr()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "validate", "discover", "init", "health", "migrate"})
    void inputFailuresPreserveEachCommandsExistingExitCode(String command) throws Exception {
        String option = switch (command) {
            case "test", "validate" -> "--scenario";
            case "discover", "init" -> "--from-openapi";
            case "health" -> "--file";
            default -> "--path";
        };
        var result = invoke(command, option, directory.resolve(MARKER).toString());
        assertEnvelope(result, command.equals("validate") || command.equals("migrate") ? 2 : 3);
        assertThat(result.json().path("diagnostics").isEmpty()).isFalse();
    }

    @Test
    void unknownCommandsAndMissingCommandsDoNotEchoArgumentValues() throws Exception {
        var unknown = invoke(MARKER);
        assertEnvelope(unknown, 2);
        assertThat(unknown.json().path("command").isNull()).isTrue();
        assertThat(unknown.json().path("diagnostics").get(0).path("code").asText()).isEqualTo("UNKNOWN_COMMAND");
        var missing = invoke();
        assertEnvelope(missing, 2);
        assertThat(missing.json().path("diagnostics").get(0).path("code").asText()).isEqualTo("COMMAND_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "--version"})
    void globalInformationCommandsStillProduceOneMachineResult(String option) throws Exception {
        var result = invoke(option);
        assertEnvelope(result, 0);
        assertThat(result.json().path("diagnostics").isEmpty()).isTrue();
    }

    @Test
    void scenarioDiagnosticsHaveStableFieldsAndDocumentLocations() throws Exception {
        Path scenario = directory.resolve("scenario.yaml");
        Files.writeString(scenario, SCENARIO + "timeout: " + MARKER + "\n");
        var result = invoke("validate", "--scenario", scenario.toString());
        assertEnvelope(result, 2);
        JsonNode diagnostic = result.json().path("diagnostics").get(0);
        assertThat(diagnostic.properties()).extracting(entry -> entry.getKey())
                .containsExactlyInAnyOrder("code", "severity", "node", "path", "hint");
        assertThat(diagnostic.path("code").asText()).isEqualTo("SCENARIO_INVALID");
        assertThat(diagnostic.path("severity").asText()).isEqualTo("ERROR");
        assertThat(diagnostic.path("path").asText()).isEqualTo("timeout");
        assertThat(diagnostic.path("node").isNull()).isTrue();
        assertThat(diagnostic.path("hint").asText()).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({
            "%s: true, SCENARIO_UNKNOWN_FIELD, execute[0]",
            "retry: {maxAttempts: %s}, SCENARIO_TYPE_MISMATCH, execute[0].retry.maxAttempts"
    })
    void parserDetailsKeepTheBroadCodeAndIdentifyTheStructuralPath(
            String field, String detailCode, String path) throws Exception {
        Path scenario = directory.resolve("scenario.yaml");
        Files.writeString(scenario, SCENARIO + "    " + field.formatted(MARKER) + "\n");

        var result = invoke("validate", "--scenario", scenario.toString());

        assertEnvelope(result, 2);
        assertThat(result.json().path("diagnostics")).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.properties()).extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder("code", "severity", "node", "path", "hint", "detailCode");
            assertThat(diagnostic.path("code").asText()).isEqualTo("SCENARIO_INVALID");
            assertThat(diagnostic.path("detailCode").asText()).isEqualTo(detailCode);
            assertThat(diagnostic.path("path").asText()).isEqualTo(path);
            assertThat(diagnostic.path("severity").asText()).isEqualTo("ERROR");
            assertThat(diagnostic.path("node").isNull()).isTrue();
        });
    }

    @Test
    void previewAndDiagnosticsUseOneEnvelopeAndDoNotReplaceTheJournal() throws Exception {
        Path scenario = directory.resolve("scenario.yaml");
        Files.writeString(scenario, SCENARIO);
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        Path journal = artifacts.resolve("events.ndjson");
        Files.writeString(journal, "existing journal");
        var result = invoke("test", "--preview", "--scenario", scenario.toString(), "--output", artifacts.toString());
        assertEnvelope(result, 0);
        JsonNode preview = result.json().path("preview");
        assertThat(preview.path("compiled").booleanValue()).isTrue();
        assertThat(preview.path("actions")).isEqualTo(JSON.readTree("""
                [{
                  "nodeId": "pause",
                  "kind": "wait",
                  "operationId": null,
                  "target": null,
                  "protocol": null,
                  "deadlineMs": 1,
                  "maxAttempts": 0,
                  "repetitions": 1
                }]
                """));
        assertThat(Files.readString(journal)).isEqualTo("existing journal");
    }

    @Test
    void previewInputFailureHasAnExplicitNullPreviewAndStableDiagnostic() throws Exception {
        var result = invoke("test", "--preview", "--scenario", directory.resolve(MARKER + ".yaml").toString());

        assertEnvelope(result, 3);
        assertThat(result.json().path("command").textValue()).isEqualTo("test");
        assertThat(result.json().path("preview").isNull()).isTrue();
        assertThat(result.json().path("diagnostics")).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.path("code").textValue()).isEqualTo("FILE_READ_FAILED");
            assertThat(diagnostic.path("severity").textValue()).isEqualTo("ERROR");
            assertThat(diagnostic.path("node").isNull()).isTrue();
            assertThat(diagnostic.path("path").isNull()).isTrue();
            assertThat(diagnostic.has("detailCode")).isFalse();
        });
    }

    @Test
    void commandStateDoesNotLeakBetweenInvocationsOfTheSameCli() throws Exception {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        var cli = new FaultoraCli(new PrintWriter(stdout), new PrintWriter(stderr));
        assertThat(cli.run(new String[]{"--diagnostics=json", MARKER})).isEqualTo(2);
        stdout.getBuffer().setLength(0);
        assertThat(cli.run(new String[]{"--diagnostics=json", "--version"})).isZero();
        assertThat(JSON.readTree(stdout.toString()).path("diagnostics").isEmpty()).isTrue();
    }

    @Test
    void humanExecutionAndConsoleReportsUseTheInjectedWriter() throws Exception {
        Path scenario = directory.resolve("scenario.yaml");
        Files.writeString(scenario, SCENARIO);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        var originalOut = System.out;
        var originalErr = System.err;
        int exit = new FaultoraCli(new PrintWriter(stdout), new PrintWriter(stderr)).run(new String[]{
                "test", "--scenario", scenario.toString(), "--output", directory.resolve("reports").toString()});
        assertThat(exit).isZero();
        assertThat(stdout.toString()).contains("Running scenario:", "Nodes", "Result: PASSED");
        assertThat(System.out).isSameAs(originalOut);
        assertThat(System.err).isSameAs(originalErr);
    }

    @Test
    void warningsRetainWarningSeverityWithoutChangingSuccess() throws Exception {
        Path scenario = directory.resolve("scenario.yaml");
        Files.writeString(scenario, SCENARIO.replace("faultora.dev/v1", "faultora.dev/v1alpha1"));
        var result = invoke("validate", "--scenario", scenario.toString());
        assertEnvelope(result, 0);
        assertThat(result.json().path("diagnostics").get(0).path("code").asText()).isEqualTo("SCENARIO_WARNING");
        assertThat(result.json().path("diagnostics").get(0).path("severity").asText()).isEqualTo("WARNING");
    }

    @Test
    void catalogWarningsNameTheFamilyButNotWhatTheDocumentDeclares() throws Exception {
        Path scenario = directory.resolve("scenario.yaml");
        Files.writeString(scenario, SCENARIO);
        Path asyncApi = directory.resolve("asyncapi.yaml");
        Files.writeString(asyncApi, hostlessServer(MARKER));
        var result = invoke("test", "--preview", "--scenario", scenario.toString(), "--asyncapi", asyncApi.toString());
        assertEnvelope(result, 0);
        assertThat(result.json().path("diagnostics")).anySatisfy(diagnostic -> {
            assertThat(diagnostic.path("code").asText()).isEqualTo("CATALOG_WARNING");
            assertThat(diagnostic.path("severity").asText()).isEqualTo("WARNING");
            assertThat(diagnostic.path("path").asText()).isEqualTo("asyncapi");
        });
    }

    @Test
    void aHumanRunPrintsTheImportersOwnWordsAndStillRuns() throws Exception {
        Path scenario = directory.resolve("scenario.yaml");
        Files.writeString(scenario, SCENARIO);
        Path asyncApi = directory.resolve("asyncapi.yaml");
        Files.writeString(asyncApi, hostlessServer("legacy"));
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = new FaultoraCli(new PrintWriter(stdout), new PrintWriter(stderr)).run(new String[]{
                "test", "--scenario", scenario.toString(), "--asyncapi", asyncApi.toString(),
                "--output", directory.resolve("reports").toString()});
        assertThat(exit).isZero();
        assertThat(stderr.toString())
                .contains("WARNING: asyncapi catalog: Server 'legacy' names no host and was skipped");
        assertThat(stdout.toString()).contains("Result: PASSED");
    }

    private static String hostlessServer(String name) {
        return """
                asyncapi: 3.0.0
                info: { title: Payments, version: 1.0.0 }
                servers:
                  %s: { protocol: kafka }
                """.formatted(name);
    }

    @ParameterizedTest
    @EnumSource(value = RunResult.Status.class, names = {"ERROR", "CANCELLED"})
    void infrastructureAndCancellationDiagnosticsExcludeUnderlyingErrorObjects(RunResult.Status status) throws Exception {
        var output = new CommandOutput(new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()), true);
        output.run(new RunResult(new RunId("synthetic-run"), status, 0, 0, 0, Map.of(), 1,
                new NormalizedError(NormalizedError.ErrorCategory.VALIDATION, MARKER, MARKER, false, Map.of("private", MARKER))));
        String text = JSON.writeValueAsString(output.finish("test", 3));
        assertThat(text).doesNotContain(MARKER);
        assertThat(JSON.readTree(text).path("diagnostics").get(0).path("code").asText())
                .isEqualTo(status == RunResult.Status.ERROR ? "RUN_ERROR" : "RUN_CANCELLED");
    }

    @Test
    void locationsCannotBecomeFilesystemPathsOrUnboundedPayloads() {
        assertThat(CliDiagnostic.of(CliDiagnostic.Code.PLAN_REFUSED, "ERROR", "read", "http://api.example.com/private").path()).isNull();
        assertThat(CliDiagnostic.of(CliDiagnostic.Code.PLAN_REFUSED, "ERROR", "n".repeat(257), "execute[0]").node()).isNull();
        assertThat(CliDiagnostic.of(CliDiagnostic.Code.PLAN_REFUSED, "ERROR", "read", "execute[0]").path()).isEqualTo("execute[0]");
    }

    @Test
    void aDiagnosticFlagUsedAsAnOptionValueDoesNotSelectMachineMode() {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = new FaultoraCli(new PrintWriter(stdout), new PrintWriter(stderr)).run(
                new String[]{"validate", "--scenario", directory.resolve("--diagnostics=json").toString()});
        assertThat(exit).isEqualTo(2);
        assertThat(stdout.toString()).isEmpty();
        assertThat(stderr.toString()).contains("Cannot read scenario file:");
    }

    private static Invocation invoke(String... args) throws Exception {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        List<String> command = new ArrayList<>(List.of("--diagnostics=json"));
        command.addAll(List.of(args));
        int exit = new FaultoraCli(new PrintWriter(stdout), new PrintWriter(stderr)).run(command.toArray(String[]::new));
        String text = stdout.toString();
        return new Invocation(exit, JSON.readTree(text), text, stderr.toString());
    }

    private static void assertEnvelope(Invocation invocation, int expectedExit) {
        assertThat(invocation.exit()).isEqualTo(expectedExit);
        JsonNode result = invocation.json();
        assertThat(result.path("schemaVersion")).isEqualTo(IntNode.valueOf(1));
        assertThat(result.path("exitCode")).isEqualTo(IntNode.valueOf(expectedExit));
        assertThat(result.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("schemaVersion", "command", "exitCode", "diagnostics", "preview");
        assertThat(result.path("command").getNodeType()).isIn(JsonNodeType.STRING, JsonNodeType.NULL);
        assertThat(result.path("preview").getNodeType()).isIn(JsonNodeType.OBJECT, JsonNodeType.NULL);
        assertThat(result.path("diagnostics").isArray()).isTrue();
        for (JsonNode diagnostic : result.path("diagnostics")) {
            assertThat(diagnostic.path("code").textValue()).isNotBlank();
            assertThat(diagnostic.path("severity").textValue()).isIn("ERROR", "WARNING", "INFO");
            assertThat(diagnostic.path("node").getNodeType()).isIn(JsonNodeType.STRING, JsonNodeType.NULL);
            assertThat(diagnostic.path("path").getNodeType()).isIn(JsonNodeType.STRING, JsonNodeType.NULL);
            assertThat(diagnostic.path("hint").textValue()).isNotBlank();
            assertThat(diagnostic.path("detailCode").getNodeType()).isIn(JsonNodeType.STRING, JsonNodeType.MISSING);
        }
        assertThat(invocation.stdout()).startsWith("{").endsWith("}" + System.lineSeparator());
        assertThat(invocation.stdout().lines()).hasSize(1);
        assertThat(invocation.stdout()).doesNotContain(MARKER, "Exception", "Running scenario", "Report written:");
        assertThat(invocation.stderr()).doesNotContain(MARKER);
    }

    private record Invocation(int exit, JsonNode json, String stdout, String stderr) { }
}
