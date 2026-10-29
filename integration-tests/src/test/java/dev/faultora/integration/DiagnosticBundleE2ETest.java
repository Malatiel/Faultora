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
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bug-report bundle made by the packaged CLI from a real run's output: the
 * same events in the same order, reduced, and nothing the run was given or saw.
 */
class DiagnosticBundleE2ETest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "fixture-token-a91e-never-bundled";

    @TempDir
    Path directory;

    @Test
    void aRealRunsBundleKeepsItsEventsAndLeavesOutEverythingElse() throws Exception {
        Path run = directory.resolve("run");
        PaymentApi api = new PaymentApi();
        api.start();
        try {
            assertThat(cli("test", "--scenario", ExampleFixtures.scenario("passing.yaml").toString(),
                    "--openapi", ExampleFixtures.openApi().toString(), "--target", api.baseUrl(),
                    "--allow-private", "--auth-secret-id", "api", "--format", "console,json,html,junit",
                    "--output", run.toString())).isZero();
        } finally {
            api.stop();
        }
        Files.writeString(run.resolve(".env"), "FAULTORA_SECRET_API=" + SECRET);
        Path bundle = directory.resolve("bundle.zip");

        assertThat(cli("diagnose", "--run", run.toString(), "--output", bundle.toString())).isZero();

        List<String> names = new ArrayList<>();
        String events;
        String manifest;
        try (ZipFile zip = new ZipFile(bundle.toFile())) {
            Collections.list(zip.entries()).forEach(entry -> names.add(entry.getName()));
            events = new String(zip.getInputStream(zip.getEntry("events.ndjson")).readAllBytes(), StandardCharsets.UTF_8);
            manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(names).containsExactly("manifest.json", "events.ndjson");
        assertThat(events + manifest).doesNotContain(SECRET, api.baseUrl());

        List<String> original = Files.readAllLines(run.resolve("events.ndjson")).stream()
                .filter(line -> !line.isBlank()).map(line -> json(line).path("eventType").asText()).toList();
        List<String> bundled = events.lines().map(line -> json(line).path("eventType").asText()).toList();
        assertThat(bundled).isEqualTo(original).contains("RUN_STARTED", "ASSERTION_EVALUATED", "RUN_COMPLETED");
        assertThat(json(manifest).path("faultoraVersion").asText()).isEqualTo(System.getProperty("faultora.version"));
    }

    private int cli(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
                Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath().toString()));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(directory, "cli-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("FAULTORA_SECRET_API", SECRET);
        int exit = BoundedProcess.run(builder, java.time.Duration.ofSeconds(120), process -> { });
        assertThat(exit).as(Files.readString(log)).isIn(0, 1, 2, 3);
        return exit;
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception invalid) {
            throw new AssertionError(invalid);
        }
    }
}
