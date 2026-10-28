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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static String image;
    private static String platform;

    @TempDir
    static Path commandLogs;

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
        platform = System.getProperty("faultora.runner.platform", "");
        if (platform.isEmpty()) {
            var info = run(30, PROJECT_ROOT, "docker", "info", "--format", "{{.Architecture}}");
            assertThat(info.exitValue()).as(info.output()).isZero();
            platform = switch (info.output().strip()) {
                case "x86_64", "amd64" -> "linux/amd64";
                case "aarch64", "arm64" -> "linux/arm64";
                default -> throw new IllegalStateException("Unsupported image runtime architecture");
            };
        }
        assertThat(platform).isIn("linux/amd64", "linux/arm64");
        image = "faultora/runner:d02-test-" + platform.substring("linux/".length());
        var built = run(300, PROJECT_ROOT,
                "docker", "buildx", "bake", "-f", "deploy/runner-image.hcl", "load",
                "--set", "load.args." + jarBuildArgument(PROJECT_ROOT, jar),
                "--set", "load.args.VERSION=" + System.getProperty("faultora.version"),
                "--set", "load.platform=" + platform,
                "--set", "load.tags=" + image);
        assertThat(built.exitValue()).as(built.output()).isZero();
    }

    static String jarBuildArgument(Path projectRoot, Path jar) {
        return "JAR=" + projectRoot.relativize(jar).toString()
                .replace(projectRoot.getFileSystem().getSeparator(), "/");
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
        readableInsideTheContainer(directory);

        try (QualificationDispatcher dispatcher = new QualificationDispatcher(
                new TlsMaterial(control.keystore(),
                        Certificates.trusting(directory, "control", runner),
                        () -> Certificates.PASSWORD.toCharArray()).sslContext(),
                "0.0.0.0")) {
            dispatcher.offer(dispatch("run-in-a-container", signing));

            var container = container(180,
                    "--add-host", HOST_FROM_CONTAINER + ":host-gateway",
                    "--read-only",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "-e", "FAULTORA_SECRET_RUNNER_TLS=" + Certificates.PASSWORD,
                    "-v", directory.toAbsolutePath() + ":/etc/faultora:ro",
                    "--tmpfs", "/var/faultora:uid=65532,gid=65532",
                    image,
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
                    .as(container.output())
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

        var fresh = probe(status, "30s");
        assertThat(fresh.exitValue())
                .as(fresh.output())
                .isZero();

        Files.writeString(status, statusWritten(System.currentTimeMillis() - 600_000));

        assertThat(probe(status, "30s").exitValue())
                .as("and one that has written nothing for ten minutes is not")
                .isNotZero();
    }

    @Test
    void theImageRefusesToStartWithoutAKeyToVerifyPoliciesWith() throws Exception {
        // The configuration mistake that would otherwise start fine and take
        // its permissions from whoever reached it.
        var container = container(60, image,
                "runner", "--dispatcher", "https://control.invalid:8443",
                "--keystore", "/etc/faultora/runner.p12",
                "--truststore", "/etc/faultora/trusted.p12",
                "--tls-secret-id", "runner-tls");

        assertThat(container.exitValue()).isNotZero();
        assertThat(container.output()).contains("--policy-key");
    }

    @Test
    void eachRuntimeKeepsTheContainerBoundary() throws Exception {
        var version = container(60, "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true", image, "--version");
        assertThat(version.exitValue()).as(version.output()).isZero();
        assertThat(version.output()).contains("faultora " + System.getProperty("faultora.version"));
        var inspected = run(30, PROJECT_ROOT, "docker", "image", "inspect", image);
        assertThat(inspected.exitValue()).as(inspected.output()).isZero();
        var metadata = MAPPER.readTree(inspected.output()).get(0);
        assertThat(metadata.path("Os").asText()).isEqualTo("linux");
        assertThat(metadata.path("Architecture").asText()).isEqualTo(platform.substring("linux/".length()));
        assertThat(metadata.path("Config").path("User").asText()).isEqualTo("nonroot");
        assertThat(metadata.path("Config").path("ExposedPorts").size()).isZero();
        for (String shell : List.of("/bin/sh", "/bin/bash", "/busybox/sh")) {
            var refused = container(30, "--network", "none", "--entrypoint", shell, image, "-c", "exit 0");
            assertThat(refused.exitValue()).as("no shell at %s: %s", shell, refused.output()).isNotZero();
            assertThat(refused.output()).contains("no such file or directory");
        }
    }

    /**
     * Let the user the image runs as read what is mounted from here.
     * <p>
     * A temporary directory is created {@code rwx------} on POSIX, and the
     * image runs as uid 65532 rather than as whoever ran the build — so the
     * container cannot so much as enter this directory, its key material is
     * unreadable, and the runner exits having failed to register. The failure
     * says "could not register", which sends a reader looking at TLS and at
     * the network, and is a file mode.
     * <p>
     * Invisible on a machine whose container runtime virtualizes ownership,
     * which is why this passed for a whole release before a Linux build ran it.
     * Nothing secret lives here: these keystores exist for one test method.
     */
    private static void readableInsideTheContainer(Path directory) throws IOException {
        Set<PosixFilePermission> directoryPermissions = PosixFilePermissions.fromString("rwxr-xr-x");
        Set<PosixFilePermission> filePermissions = PosixFilePermissions.fromString("rw-r--r--");
        if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Files.setPosixFilePermissions(directory, directoryPermissions);
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                Files.setPosixFilePermissions(entry,
                        Files.isDirectory(entry) ? directoryPermissions : filePermissions);
            }
        }
    }

    private ImageCommand.Result probe(Path status, String maxAge) throws Exception {
        readableInsideTheContainer(directory);
        return container(60,
                "-v", status.toAbsolutePath() + ":/var/faultora/health.json:ro",
                image, "health", "--file", "/var/faultora/health.json",
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

    private static ImageCommand.Result container(int seconds, String... args) throws Exception {
        String name = "faultora-image-test-" + java.util.UUID.randomUUID();
        List<String> command = new ArrayList<>(List.of("docker", "run", "--rm",
                "--name", name, "--platform", platform));
        command.addAll(List.of(args));
        try {
            return run(seconds, PROJECT_ROOT, command.toArray(String[]::new));
        } finally {
            var removed = run(30, PROJECT_ROOT, "docker", "container", "rm", "--force", name);
            assertThat(removed.exitValue() == 0 || removed.output().contains("No such container"))
                    .as(removed.output()).isTrue();
        }
    }

    private static ImageCommand.Result run(int seconds, Path directory, String... command)
            throws Exception {
        return ImageCommand.run(directory, commandLogs, seconds, command);
    }
}
