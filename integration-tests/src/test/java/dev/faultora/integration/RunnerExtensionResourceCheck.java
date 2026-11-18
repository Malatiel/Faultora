package dev.faultora.integration;

import dev.faultora.runner.TlsMaterial;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.result.AssertionResult;
import dev.faultora.testkit.Certificates;
import dev.faultora.testkit.QualificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly selected qualification on a Linux host with a delegated systemd user manager. */
class RunnerExtensionResourceCheck {
    @TempDir
    Path directory;

    @Test
    void thePackagedRunnerEnforcesAndRemovesTheExtensionScope() throws Exception {
        Path output = directory.resolve("user-cgroup.txt");
        var command = new ProcessBuilder("/usr/bin/systemctl", "--user", "show", "--property=ControlGroup",
                "--value", "--", "-.slice").redirectErrorStream(true).redirectOutput(output.toFile());
        assertThat(BoundedProcess.run(command, Duration.ofSeconds(5), process -> { }))
                .as("a working systemd user manager is required").isZero();
        String manager = Files.readString(output).strip();
        assertThat(manager).startsWith("/").doesNotContain("..");
        Path appSlice = Path.of("/sys/fs/cgroup" + manager, "app.slice");
        Set<Path> existingScopes = scopes(appSlice);

        Path plugin = IsolatedAssertionE2ETest.fixture(directory, "0.1", ResourceAssertion.class);
        var runner = Certificates.issue(directory, "runner", 1);
        var control = Certificates.issue(directory, "control", 1);
        var signing = Certificates.issue(directory, "signing", 1);
        Path trustedControl = Certificates.trusting(directory, "runner", control);
        var tls = new TlsMaterial(control.keystore(), Certificates.trusting(directory, "control", runner),
                () -> Certificates.PASSWORD.toCharArray());
        try (var dispatcher = new QualificationDispatcher(tls.sslContext());
             var executor = Executors.newSingleThreadExecutor()) {
            String runId = "resource-limited-extension";
            dispatcher.offer(IsolatedAssertionE2ETest.dispatch(runId, signing,
                    IsolatedAssertionE2ETest.scenario("child-check")));
            String digest = IsolatedAssertionE2ETest.digest(plugin);
            var run = executor.submit(() -> IsolatedAssertionE2ETest.runRunner(directory, dispatcher, runner,
                    trustedControl, signing, plugin, digest, List.of("--require-extension-sandbox",
                            "--extension-memory-mib", "384", "--extension-cpu-percent", "50")));

            Path scope = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (scope == null && !run.isDone() && System.nanoTime() < deadline) {
                for (Path candidate : scopes(appSlice)) {
                    Path events = candidate.resolve("cgroup.events");
                    if (!existingScopes.contains(candidate) && Files.isRegularFile(events)
                            && Files.readString(events).lines().anyMatch("populated 1"::equals)) {
                        assertThat(Files.readString(candidate.resolve("memory.max")).strip())
                                .isEqualTo(Long.toString(384L * 1024 * 1024));
                        assertThat(Files.readString(candidate.resolve("memory.swap.max")).strip()).isEqualTo("0");
                        assertThat(Files.readString(candidate.resolve("cpu.max")).strip()).isEqualTo("50000 100000");
                        scope = candidate;
                        break;
                    }
                }
                if (scope == null) {
                    Thread.sleep(25);
                }
            }

            var result = run.get(45, TimeUnit.SECONDS);
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(dispatcher.outcomeOf(runId)).as(result.output()).contains("PASSED");
            assertThat(scope).as("an active extension scope must be visible on the host").isNotNull();
            assertThat(scope).doesNotExist();
        }
    }

    private static Set<Path> scopes(Path appSlice) throws Exception {
        if (!Files.isDirectory(appSlice)) {
            return Set.of();
        }
        try (var entries = Files.newDirectoryStream(appSlice, "faultora-assertion-*.scope")) {
            Set<Path> scopes = new java.util.HashSet<>();
            entries.forEach(scopes::add);
            return scopes;
        }
    }

    public static class ResourceAssertion implements AssertionProvider {
        @Override
        public String type() {
            return "child-check";
        }

        @Override
        public AssertionResult evaluate(String type, Map<String, Object> params, EvidenceView evidence,
                                        AssertionContext context) {
            if (Files.exists(Path.of("/sys/fs/cgroup"))) {
                return AssertionResult.fail("The cgroup hierarchy was visible", Map.of());
            }
            try {
                // Keep the active scope visible long enough for host-side inspection.
                Thread.sleep(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return AssertionResult.fail("The synthetic assertion was interrupted", Map.of());
            }
            return AssertionResult.pass("The cgroup hierarchy was private");
        }
    }
}
