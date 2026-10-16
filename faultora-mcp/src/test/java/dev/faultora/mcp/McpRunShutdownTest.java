package dev.faultora.mcp;

import dev.faultora.model.identifier.RunId;
import dev.faultora.runtime.PlanPreview;
import dev.faultora.runtime.RunArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class McpRunShutdownTest {
    @TempDir Path directory;

    @Test
    void effectiveDeadlineUsesScenarioTimeoutWithinPolicyOrInheritsThePolicy() {
        assertThat(McpRuns.effectiveRunMs(new PlanPreview.Budgets(1000, 10, 300_000, 1024, 0))).isEqualTo(300_000);
        assertThat(McpRuns.effectiveRunMs(new PlanPreview.Budgets(1000, 10, 300_000, 1024, 500))).isEqualTo(500);
        assertThat(McpRuns.effectiveRunMs(new PlanPreview.Budgets(1000, 10, 300_000, 1024, 500_000))).isEqualTo(300_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "interrupted-close", "shutdown-hook", "deadline", "marker-unavailable", "complete"})
    void uncooperativeCallbackCannotKeepTheProcessPastItsLifecycleBudget(String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path stdout = directory.resolve("stdout.txt");
        Path stderr = directory.resolve("stderr.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", classpath, McpShutdownProcess.class.getName(), directory.toString(), mode)
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            Path ready = directory.resolve("ready");
            long startup = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < startup) {
                Thread.sleep(10);
            }
            assertThat(ready).as(Files.readString(stderr)).isRegularFile();
            // Synthetic run + cleanup + grace = 1000ms; allow startup/scheduling overhead, not an infinite join.
            assertThat(process.waitFor(3, TimeUnit.SECONDS)).as("bounded process completion").isTrue();
            String id = Files.readString(ready);
            Path run = RunArtifacts.dispatchedDirectory(directory, new RunId(id));
            Path marker = run.resolve("termination.json");
            if (mode.equals("complete")) {
                assertThat(process.exitValue()).as(Files.readString(stderr)).isZero();
                assertThat(marker).doesNotExist();
                assertThat(run.resolve("report.json")).isRegularFile();
            } else if (mode.equals("marker-unavailable")) {
                assertThat(process.exitValue()).isEqualTo(McpRunShutdown.EXIT_UNCONFIRMED);
                assertThat(marker).doesNotExist();
                assertThat(Files.readString(stderr)).contains("marker could not be written")
                        .doesNotContain(directory.toString(), "foreign-owner");
            } else {
                assertThat(process.exitValue()).as(Files.readString(stderr)).isEqualTo(McpRunShutdown.EXIT_UNCONFIRMED);
                var result = McpJson.MAPPER.readTree(Files.readString(marker));
                assertThat(result.path("runId").asText()).isEqualTo(id);
                assertThat(result.path("status").asText()).isEqualTo("ERROR");
                assertThat(result.path("cleanupConfirmed").asBoolean()).isFalse();
                assertThat(result.path("code").asText()).isEqualTo(McpRunShutdown.REASON);
                assertThat(directory.resolve("cancel-observed")).hasContent("true");
                assertThat(result.toString()).doesNotContain(directory.toString(), McpFixtures.MARKER);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
