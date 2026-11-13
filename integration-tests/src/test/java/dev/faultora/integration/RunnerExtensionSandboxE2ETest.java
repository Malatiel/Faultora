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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RunnerExtensionSandboxE2ETest {
    @TempDir
    Path directory;

    @Test
    void aDispatchedAssertionCannotReachTheHostFilesOrNetwork() throws Exception {
        Path launcher = Path.of("/usr/bin/bwrap");
        boolean strict = Boolean.getBoolean("faultora.qualification.strict");
        assumeTrue(strict || System.getProperty("os.name").equals("Linux") && Files.isExecutable(launcher),
                "Bubblewrap is an optional prerequisite outside strict qualification");
        assertThat(launcher).as("strict Linux qualification requires Bubblewrap").isExecutable();
        Path readable = Files.writeString(directory.resolve("runner-only.txt"), "synthetic fixture");
        Path writable = directory.resolve("unexpected-child-write.txt");
        Path plugin = IsolatedAssertionE2ETest.fixture(directory, "0.1", BoundaryAssertion.class);
        var runner = Certificates.issue(directory, "runner", 1);
        var control = Certificates.issue(directory, "control", 1);
        var signing = Certificates.issue(directory, "signing", 1);
        var trustedControl = Certificates.trusting(directory, "runner", control);
        var tls = new TlsMaterial(control.keystore(), Certificates.trusting(directory, "control", runner),
                () -> Certificates.PASSWORD.toCharArray());
        try (var port = new ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
             var dispatcher = new QualificationDispatcher(tls.sslContext())) {
            String scenario = IsolatedAssertionE2ETest.scenario("child-check") + """
                      readPath: '%s'
                      writePath: '%s'
                      hostPort: %d
                      runnerPid: %d
                """.formatted(readable.toString().replace("'", "''"), writable.toString().replace("'", "''"),
                    port.getLocalPort(), ProcessHandle.current().pid());
            String digest = IsolatedAssertionE2ETest.digest(plugin);
            dispatcher.offer(IsolatedAssertionE2ETest.dispatch("unconfined-control", signing, scenario));
            var controlRun = IsolatedAssertionE2ETest.runRunner(directory, dispatcher, runner, trustedControl,
                    signing, plugin, digest, List.of());
            assertThat(controlRun.exitCode()).as(controlRun.output()).isZero();
            assertThat(dispatcher.outcomeOf("unconfined-control")).contains("FAILED");

            String runId = "sandboxed-extension";
            dispatcher.offer(IsolatedAssertionE2ETest.dispatch(runId, signing, scenario));
            var result = IsolatedAssertionE2ETest.runRunner(directory, dispatcher, runner, trustedControl,
                    signing, plugin, digest, List.of("--require-extension-sandbox"));
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(dispatcher.outcomeOf(runId)).as(result.output()).contains("PASSED");
            assertThat(dispatcher.journalOf(runId)).anyMatch(line -> line.contains("child-check"));
            assertThat(writable).doesNotExist();
        }
    }

    public static class BoundaryAssertion implements AssertionProvider {
        @Override
        public String type() {
            return "child-check";
        }

        @Override
        public AssertionResult evaluate(String type, Map<String, Object> params, EvidenceView evidence,
                                        AssertionContext context) {
            var read = Path.of((String) params.get("readPath"));
            var write = Path.of((String) params.get("writePath"));
            int port = ((Number) params.get("hostPort")).intValue();
            long pid = ((Number) params.get("runnerPid")).longValue();
            try {
                Files.readAllBytes(read);
                return AssertionResult.fail("Host read succeeded", Map.of());
            } catch (IOException expected) {
                // The parent can read this file.
            }
            try {
                Files.writeString(write, "synthetic probe");
                return AssertionResult.fail("Host write succeeded", Map.of());
            } catch (IOException expected) {
                // The parent owns and can write the containing directory.
            }
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return AssertionResult.fail("Host loopback was reachable", Map.of());
            } catch (IOException expected) {
                // The listener remains open in the parent's namespace.
            }
            if (Files.exists(Path.of("/proc", Long.toString(pid), "cmdline"))
                    || System.getenv("FAULTORA_SECRET_RUNNER_TLS") != null) {
                return AssertionResult.fail("Parent process data was visible", Map.of());
            }
            try {
                Files.writeString(Path.of("/faultora/plugin.jar"), "synthetic probe",
                        java.nio.file.StandardOpenOption.APPEND);
                return AssertionResult.fail("The extension mount was writable", Map.of());
            } catch (IOException expected) {
                // The verified JAR is mapped read-only.
            }
            try {
                Path scratch = Files.createTempFile("assertion-", ".txt");
                Files.writeString(scratch, "synthetic probe");
                return AssertionResult.pass("Private scratch is writable");
            } catch (IOException failed) {
                return AssertionResult.fail("Private scratch failed", Map.of());
            }
        }
    }
}
