package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.TlsMaterial;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.testkit.Certificates;
import dev.faultora.testkit.QualificationDispatcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The image, serving a dispatch.
 * <p>
 * A container that has never run a scenario is a Dockerfile somebody believes
 * in. This one builds the image, runs a real runner in it, and dispatches to
 * that runner over mutual TLS from outside the container — which is the only
 * way to find out that a distroless base with no shell can still run a probe,
 * that the non-root user can write the working directory, and that the
 * entrypoint takes the arguments the examples pass it.
 * <p>
 * The dispatcher listens on every interface here, and only here. Nothing about
 * the arrangement changes: the runner still dials out, and its container has no
 * port. A container simply cannot reach a loopback socket in another network
 * namespace, and the host's certificate has to carry the name the container
 * reaches it under, or the handshake fails in a way that reads like a broken
 * runner.
 */
@EnabledIf("dockerIsAvailable")
class RunnerImageE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How a container reaches the host it runs on, on Docker Desktop and Linux. */
    private static final String HOST_FROM_CONTAINER = "host.docker.internal";

    private static final String IMAGE = "faultora/runner:test";

    private static final Path PROJECT_ROOT =
            Path.of(System.getProperty("user.dir")).getParent();

    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1alpha1
            kind: Scenario
            metadata:
              name: run-inside-a-container
            execute:
              - id: pause
                type: wait
                timeout: 50ms
            """;

    @TempDir
    Path directory;

    @SuppressWarnings("unused")
    static boolean dockerIsAvailable() {
        if (RecoveryInfrastructure.dockerIsAvailable()) {
            return true;
        }
        System.err.println("Runner image suite skipped: no container runtime. "
                + "The packaged runner is NOT proved to run by this build.");
        return false;
    }

    @BeforeAll
    static void buildTheImage() throws Exception {
        Path jar = shadedJar();
        int built = run(60, PROJECT_ROOT,
                "docker", "build",
                "--build-arg", "JAR=" + PROJECT_ROOT.relativize(jar),
                "-t", IMAGE, ".").exitValue();
        assertThat(built).as("the image builds").isZero();
    }

    /**
     * The jar the packaging copies in.
     * <p>
     * Found rather than named, because the version moves and a test that
     * hard-coded it would start testing an image built from something older.
     */
    private static Path shadedJar() throws IOException {
        Path target = PROJECT_ROOT.resolve("faultora-cli").resolve("target");
        try (var entries = Files.list(target)) {
            return entries
                    .filter(path -> path.getFileName().toString().startsWith("faultora-")
                            && path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().startsWith("original-"))
                    .filter(path -> !path.getFileName().toString().startsWith("faultora-cli-"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No packaged jar in " + target + " — run ./mvnw package first"));
        }
    }

    @Test
    void aRunnerInTheImageDialsOutAndServesADispatch() throws Exception {
        Certificates.Identity runner = Certificates.issue(directory, "runner", 1);
        Certificates.Identity control = Certificates.issue(
                directory, "control", 1, "dns:" + HOST_FROM_CONTAINER);
        Certificates.Identity signing = Certificates.issue(directory, "policy-signing", 1);
        Path truststore = Certificates.trusting(directory, "runner", control);

        try (QualificationDispatcher dispatcher = new QualificationDispatcher(
                new TlsMaterial(control.keystore(),
                        Certificates.trusting(directory, "control", runner),
                        () -> Certificates.PASSWORD.toCharArray()).sslContext(),
                "0.0.0.0")) {
            dispatcher.offer(dispatch("run-in-a-container", signing));

            Process container = run(120, PROJECT_ROOT,
                    "docker", "run", "--rm",
                    "--add-host", HOST_FROM_CONTAINER + ":host-gateway",
                    "--read-only",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "-e", "FAULTORA_SECRET_RUNNER_TLS=" + Certificates.PASSWORD,
                    "-v", directory.toAbsolutePath() + ":/etc/faultora:ro",
                    "--tmpfs", "/var/faultora:uid=65532,gid=65532",
                    IMAGE,
                    "runner",
                    "--dispatcher",
                    "https://" + HOST_FROM_CONTAINER + ":" + dispatcher.port(),
                    "--keystore", "/etc/faultora/" + runner.keystore().getFileName(),
                    "--truststore", "/etc/faultora/" + truststore.getFileName(),
                    "--tls-secret-id", "runner-tls",
                    "--policy-key",
                    "control-2026=/etc/faultora/" + signing.certificate().getFileName(),
                    "--work-dir", "/var/faultora",
                    "--runner-id", "runner-in-a-container",
                    "--allow-private",
                    "--once");

            assertThat(container.exitValue())
                    .as(() -> "the container said: " + output(container))
                    .isZero();
            assertThat(dispatcher.registrations()).singleElement().satisfies(registration ->
                    assertThat(registration.runnerId()).isEqualTo("runner-in-a-container"));
            assertThat(dispatcher.journalOf("run-in-a-container"))
                    .as("what the run learned crossed the container boundary")
                    .isNotEmpty();
            assertThat(dispatcher.outcomeOf("run-in-a-container")).contains("PASSED");
        }
    }

    @Test
    void theProbeInTheImageAnswersWithoutAShell() throws Exception {
        // Distroless has no shell, so `test -f` is not available and the probe
        // has to be a Faultora command. Both halves: a fresh status file is
        // healthy and a stale one is not, or a probe that always succeeded
        // would keep a wedged runner alive forever.
        Path status = directory.resolve("health.json");
        Files.writeString(status, statusWritten(System.currentTimeMillis()));

        assertThat(probe(status, "30s").exitValue())
                .as("a runner that wrote a moment ago is alive").isZero();

        Files.writeString(status, statusWritten(System.currentTimeMillis() - 600_000));

        assertThat(probe(status, "30s").exitValue())
                .as("and one that has written nothing for ten minutes is not")
                .isNotZero();
    }

    @Test
    void theImageRefusesToStartWithoutAKeyToVerifyPoliciesWith() throws Exception {
        // The configuration mistake that would otherwise start fine and take
        // its permissions from whoever reached it.
        Process container = run(60, PROJECT_ROOT,
                "docker", "run", "--rm", IMAGE,
                "runner", "--dispatcher", "https://control.invalid:8443",
                "--keystore", "/etc/faultora/runner.p12",
                "--truststore", "/etc/faultora/trusted.p12",
                "--tls-secret-id", "runner-tls");

        assertThat(container.exitValue()).isNotZero();
        assertThat(output(container)).contains("--policy-key");
    }

    private Process probe(Path status, String maxAge) throws Exception {
        return run(60, PROJECT_ROOT,
                "docker", "run", "--rm",
                "-v", status.toAbsolutePath() + ":/var/faultora/health.json:ro",
                IMAGE, "health", "--file", "/var/faultora/health.json",
                "--max-age", maxAge);
    }

    private static String statusWritten(long at) {
        return "{\"state\":\"WAITING\",\"runnerId\":\"probe\",\"updatedAtEpochMs\":" + at
                + ",\"registered\":true,\"currentRunId\":null,\"runsServed\":1}";
    }

    private static Dispatch dispatch(String runId, Certificates.Identity signing)
            throws Exception {
        String policy = MAPPER.writeValueAsString(EffectivePolicy.of(new TargetPolicy(
                Set.of(), Set.of(SafetyClassification.READ_ONLY),
                100, 2, 60_000, 1024, Set.of(), Set.of())));
        return new Dispatch(
                runId, System.currentTimeMillis(), runId + "-nonce", SCENARIO, List.of(),
                Map.of(), Map.of("", "http://localhost:1"), Dispatch.Credentials.none(),
                5L,
                new SignedPolicy(policy, "control-2026",
                        Certificates.sign(signing, "RSA", policy)),
                new Lease(System.currentTimeMillis(), 120_000, 20_000),
                ContentDigest.sha256Uri(SCENARIO), Dispatch.digestOfDocuments(List.of()));
    }

    /** Run a command, wait for it, and keep what it said. */
    private static Process run(int seconds, Path directory, String... command)
            throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(List.of(command)))
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] said = process.getInputStream().readAllBytes();
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("`" + String.join(" ", command) + "` never finished");
        }
        OUTPUT.put(process, new String(said));
        return process;
    }

    private static final Map<Process, String> OUTPUT = new java.util.concurrent.ConcurrentHashMap<>();

    private static String output(Process process) {
        return OUTPUT.getOrDefault(process, "");
    }
}
