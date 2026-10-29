package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packaged CLI's logs as a collector sees them: one JSON object per line on
 * standard error, each naming the run and node it came from, apart from the
 * report on standard output, and with the run's credential nowhere in either.
 */
class StructuredLogsE2ETest {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Synthetic, and distinctive enough that finding it anywhere means it leaked. */
    private static final String SECRET = "fixture-token-7f3a9c1e-never-logged";

    @TempDir
    Path directory;

    @Test
    void jsonLogsAreParseableCorrelatedAndFreeOfTheCredential() throws Exception {
        Path output = directory.resolve("run");
        Files.createDirectories(output);
        PaymentApi api = new PaymentApi();
        api.start();
        Result result;
        try {
            result = cli(output, api.baseUrl());
        } finally {
            api.stop();
        }
        assertThat(result.code()).as(result.stdout() + result.stderr()).isZero();

        String runId = runIdOf(output.resolve("report.json"));
        List<JsonNode> lines = new ArrayList<>();
        for (String line : result.stderr().lines().filter(line -> !line.isBlank()).toList()) {
            JsonNode parsed = JSON.readTree(line);
            assertThat(parsed.isObject()).as("one object per line: %s", line).isTrue();
            assertThat(parsed.fieldNames()).toIterable()
                    .as("only the published fields: %s", line)
                    .isSubsetOf("time", "level", "logger", "event", "runId", "nodeId", "failure");
            assertThat(parsed.path("time").asText()).isNotBlank();
            assertThat(parsed.path("level").asText()).isNotBlank();
            assertThat(parsed.path("event").asText()).isNotBlank();
            lines.add(parsed);
        }

        Set<String> nodes = new TreeSet<>();
        for (JsonNode line : lines) {
            if (line.path("event").asText().equals("Node finished")) {
                assertThat(line.path("runId").asText()).isEqualTo(runId);
                nodes.add(line.path("nodeId").asText());
            }
        }
        assertThat(nodes).as("a finished line for each node, with its id").isNotEmpty().doesNotContain("");
        assertThat(lines).anySatisfy(line -> {
            assertThat(line.path("event").asText()).isEqualTo("Run finished");
            assertThat(line.path("runId").asText()).isEqualTo(runId);
            assertThat(line.has("nodeId")).isFalse();
        });

        assertThat(result.stdout()).as("the report is not mixed into the log")
                .doesNotContain("\"logger\"");
        assertThat(result.stdout() + result.stderr()).doesNotContain(SECRET);
        try (Stream<Path> files = Files.walk(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(file, StandardCharsets.UTF_8)).as("%s", file).doesNotContain(SECRET);
            }
        }
    }

    private Result cli(Path output, String target) throws Exception {
        Path jar = Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath();
        assertThat(jar).as("packaged CLI built earlier in the reactor").isRegularFile();
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"));
        // As arguments rather than the variable, whose "Picked up" notice the
        // JVM itself writes to standard error, outside any log format.
        String toolOptions = System.getenv("JAVA_TOOL_OPTIONS");
        if (toolOptions != null && !toolOptions.isBlank()) {
            command.addAll(List.of(toolOptions.trim().split("\\s+")));
        }
        command.addAll(List.of("-jar", jar.toString(),
                "test", "--scenario", ExampleFixtures.scenario("passing.yaml").toString(),
                "--openapi", ExampleFixtures.openApi().toString(), "--target", target,
                "--allow-private", "--auth-secret-id", "api",
                "--format", "console,json", "--output", output.toString()));
        Path stdout = directory.resolve("stdout.txt");
        Path stderr = directory.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().put("FAULTORA_LOG_FORMAT", "json");
        builder.environment().put("FAULTORA_LOG_LEVEL", "DEBUG");
        builder.environment().put("FAULTORA_SECRET_API", SECRET);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("CLI finished").isTrue();
            return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                    Files.readString(stderr, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String runIdOf(Path report) throws Exception {
        for (JsonNode event : JSON.readTree(Files.readString(report))) {
            if (event.path("runId").isTextual()) {
                return event.path("runId").asText();
            }
        }
        throw new AssertionError("no runId in " + report);
    }

    private record Result(int code, String stdout, String stderr) { }
}
