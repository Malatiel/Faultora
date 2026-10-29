package dev.faultora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnoseCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "fixture-secret-5d21";

    @TempDir
    Path directory;

    @Test
    void theBundleHoldsTheReducedJournalAndAManifestAndNothingElse() throws Exception {
        Path run = runDirectory();
        Files.writeString(run.resolve(".env"), "FAULTORA_SECRET_API=" + SECRET);
        Files.writeString(run.resolve("runner.p12"), SECRET);
        Files.writeString(run.resolve("body.json"), "{\"card\":\"" + SECRET + "\"}");
        Files.writeString(run.resolve("report.json"), "[{\"message\":\"" + SECRET + "\"}]");
        Path bundle = directory.resolve("bundle.zip");

        Result result = diagnose("--run", run.toString(), "--output", bundle.toString());

        assertThat(result.code()).as(result.err()).isZero();
        Map<String, String> entries = entries(bundle);
        assertThat(entries).containsOnlyKeys("manifest.json", "events.ndjson");
        entries.values().forEach(content -> assertThat(content).doesNotContain(SECRET, "internal.example"));

        List<JsonNode> events = entries.get("events.ndjson").lines().map(DiagnoseCommandTest::json).toList();
        assertThat(events).hasSize(4);
        assertThat(events).extracting(event -> event.path("eventType").asText())
                .containsExactly("RUN_STARTED", "NODE_SKIPPED", "ASSERTION_EVALUATED", "RUN_FAILED");
        assertThat(events.get(2).path("outcome").asText()).isEqualTo("FAIL");
        assertThat(events.get(2).has("message")).isFalse();
        assertThat(events.get(3).path("error").path("code").asText()).isEqualTo("CANCELLED");
        assertThat(events.get(3).path("error").has("message")).isFalse();
        assertThat(events.get(0).has("resolvedConfig")).isFalse();

        JsonNode manifest = json(entries.get("manifest.json"));
        assertThat(manifest.path("sources").get(0).path("records").asInt()).isEqualTo(4);
        assertThat(manifest.path("sources").get(0).path("sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(manifest.path("fieldsWithheld")).extracting(JsonNode::asText)
                .contains("message", "reason", "resolvedConfig");
        assertThat(result.out()).contains("4 events", "withheld message");
    }

    @Test
    void aRunnerStatusIsReducedTheSameWay() throws Exception {
        Path run = runDirectory();
        Path health = Files.writeString(directory.resolve("health.json"), """
                {"state":"WAITING","runnerId":"runner-3","registered":true,"currentRunId":null,
                 "runsServed":2,"updatedAtEpochMs":1,"dispatcher":"https://internal.example/%s"}
                """.formatted(SECRET));
        Path bundle = directory.resolve("bundle.zip");

        assertThat(diagnose("--run", run.toString(), "--output", bundle.toString(),
                "--health", health.toString()).code()).isZero();

        JsonNode status = json(entries(bundle).get("health.json"));
        assertThat(status.path("runnerId").asText()).isEqualTo("runner-3");
        assertThat(status.has("dispatcher")).isFalse();
        assertThat(status.toString()).doesNotContain(SECRET);
    }

    @Test
    void aJournalThatIsALinkIsRefusedNotFollowed() throws Exception {
        Path run = Files.createDirectory(directory.resolve("run"));
        Path outside = Files.writeString(directory.resolve("outside.ndjson"), journal());
        try {
            Files.createSymbolicLink(run.resolve("events.ndjson"), outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("this file system does not let the test create a link");
        }
        Path bundle = directory.resolve("bundle.zip");

        assertThatThrownBy(() -> diagnose("--run", run.toString(), "--output", bundle.toString()))
                .isInstanceOf(CliException.class).hasMessageContaining("is a link");
        assertThat(bundle).doesNotExist();
    }

    @Test
    void aJournalLargerThanTheBundleAllowsIsRefusedNotTruncated() throws Exception {
        Path run = Files.createDirectory(directory.resolve("run"));
        try (var file = Files.newOutputStream(run.resolve("events.ndjson"))) {
            file.write(new byte[(int) DiagnoseCommand.MAX_JOURNAL_BYTES + 1]);
        }
        assertThatThrownBy(() -> diagnose("--run", run.toString(), "--output", directory.resolve("b.zip").toString()))
                .isInstanceOf(CliException.class).hasMessageContaining("larger than");
    }

    @Test
    void anExistingBundleIsNotOverwrittenAndAMissingJournalIsNamed() throws Exception {
        Path run = runDirectory();
        Path existing = Files.writeString(directory.resolve("bundle.zip"), "keep");
        assertThatThrownBy(() -> diagnose("--run", run.toString(), "--output", existing.toString()))
                .isInstanceOf(CliException.class).hasMessageContaining("Not overwriting");
        assertThat(Files.readString(existing)).isEqualTo("keep");

        Path empty = Files.createDirectory(directory.resolve("empty"));
        assertThatThrownBy(() -> diagnose("--run", empty.toString(), "--output", directory.resolve("x.zip").toString()))
                .isInstanceOf(CliException.class).hasMessageContaining("No events.ndjson");
    }

    @Test
    void aLineThatIsNotAnEventIsRefused() throws Exception {
        Path run = Files.createDirectory(directory.resolve("run"));
        Files.writeString(run.resolve("events.ndjson"), "[1,2]\n");
        assertThatThrownBy(() -> diagnose("--run", run.toString(), "--output", directory.resolve("x.zip").toString()))
                .isInstanceOf(CliException.class).hasMessageContaining("not a journal event");
    }

    private Path runDirectory() throws IOException {
        Path run = Files.createDirectory(directory.resolve("run"));
        Files.writeString(run.resolve("events.ndjson"), journal());
        return run;
    }

    private static String journal() {
        return String.join("\n",
                "{\"eventType\":\"RUN_STARTED\",\"timestamp\":1,\"runId\":\"r\",\"scenarioDigest\":\"d\","
                        + "\"catalogDigest\":\"c\",\"seed\":7,\"resolvedConfig\":{\"baseUrl\":\"https://internal.example\"}}",
                "{\"eventType\":\"NODE_SKIPPED\",\"timestamp\":2,\"runId\":\"r\",\"nodeId\":\"n\","
                        + "\"reason\":\"depends on " + SECRET + "\"}",
                "{\"eventType\":\"ASSERTION_EVALUATED\",\"timestamp\":3,\"runId\":\"r\",\"nodeId\":\"a\","
                        + "\"assertionType\":\"jsonpath\",\"outcome\":\"FAIL\",\"message\":\"expected " + SECRET + "\"}",
                "{\"eventType\":\"RUN_FAILED\",\"timestamp\":4,\"runId\":\"r\",\"durationMs\":3,"
                        + "\"error\":{\"category\":\"CANCELLED\",\"code\":\"CANCELLED\",\"message\":\"" + SECRET
                        + "\",\"retryable\":false,\"metadata\":{\"k\":\"" + SECRET + "\"}}}") + "\n";
    }

    private Result diagnose(String... arguments) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = new DiagnoseCommand(new PrintWriter(out, true), new PrintWriter(err, true))
                .execute(List.of(arguments));
        return new Result(code, out.toString(), err.toString());
    }

    private static Map<String, String> entries(Path bundle) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(bundle.toFile())) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                entries.put(entry.getName(), new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException invalid) {
            throw new AssertionError(invalid);
        }
    }

    private record Result(int code, String out, String err) { }
}
