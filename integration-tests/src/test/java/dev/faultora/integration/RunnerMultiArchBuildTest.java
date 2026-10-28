package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Building an index does not need either architecture to execute or a registry to accept it. */
@EnabledIf("dockerIsAvailable")
class RunnerMultiArchBuildTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @TempDir
    Path directory;

    static boolean dockerIsAvailable() {
        return RecoveryInfrastructure.dockerIsAvailable();
    }

    @Test
    void theDefaultPlanExportsBothPlatformsToAFile() throws Exception {
        var printed = command(30, "docker", "buildx", "bake", "-f", "deploy/runner-image.hcl",
                "--print", "--progress=quiet");
        assertThat(printed.exitValue()).as(printed.output()).isZero();
        var plan = JSON.readTree(printed.output());
        assertThat(plan.path("group").path("default").path("targets").toString()).isEqualTo("[\"local\"]");
        var local = plan.path("target").path("local");
        assertThat(local.path("platforms")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("linux/amd64", "linux/arm64");
        assertThat(local.path("output")).singleElement().satisfies(output ->
                assertThat(output.path("type").asText()).isEqualTo("oci"));
    }

    @Test
    void theLocalArchiveContainsBothArchitectureConfigs() throws Exception {
        Path archive = directory.resolve("runner.oci.tar");
        Path jar = Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath().normalize();
        var built = buildArchive(archive, jar, null);
        if (built.exitValue() != 0 && (built.output().contains("OCI exporter is not supported")
                || built.output().contains("Multi-platform build is not supported"))) {
            // Classic Docker stores need a separate exporter; containerd-backed
            // stores can use the current builder without another daemon/pull.
            String builder = "faultora-oci-test-" + java.util.UUID.randomUUID();
            var created = command(30, "docker", "buildx", "create", "--name", builder,
                    "--driver", "docker-container");
            assertThat(created.exitValue()).as(created.output()).isZero();
            try {
                built = buildArchive(archive, jar, builder);
            } finally {
                var removed = command(30, "docker", "buildx", "rm", "--force", builder);
                assertThat(removed.exitValue()).as(removed.output()).isZero();
            }
        }
        assertThat(built.exitValue()).as(built.output()).isZero();
        assertThat(archive).isRegularFile();
        Set<String> platforms = new HashSet<>();
        for (JsonNode descriptor : entry(archive, "index.json").path("manifests")) {
            inspect(archive, descriptor, platforms);
        }
        assertThat(platforms).containsExactlyInAnyOrder("linux/amd64", "linux/arm64");
    }

    private ImageCommand.Result buildArchive(Path archive, Path jar, String builder) throws Exception {
        List<String> args = new ArrayList<>(List.of("docker", "buildx", "bake"));
        if (builder != null) {
            args.addAll(List.of("--builder", builder));
        }
        args.addAll(List.of("--allow=fs.write=" + directory.toAbsolutePath(),
                "-f", "deploy/runner-image.hcl", "local",
                "--set", "local.args." + RunnerImageE2ETest.jarBuildArgument(ROOT, jar),
                "--set", "local.args.VERSION=" + System.getProperty("faultora.version"),
                "--set", "local.output=type=oci,dest=" + archive.toString().replace('\\', '/')));
        return command(300, args.toArray(String[]::new));
    }

    private void inspect(Path archive, JsonNode descriptor, Set<String> platforms) throws Exception {
        JsonNode manifest = blob(archive, descriptor);
        if (manifest.has("manifests")) {
            for (JsonNode child : manifest.path("manifests")) {
                inspect(archive, child, platforms);
            }
        } else if (!descriptor.path("annotations").path("vnd.docker.reference.type")
                .asText().equals("attestation-manifest")) {
            JsonNode config = blob(archive, manifest.path("config"));
            String os = config.path("os").asText();
            String architecture = config.path("architecture").asText();
            assertThat(descriptor.path("platform").path("os").asText()).isEqualTo(os);
            assertThat(descriptor.path("platform").path("architecture").asText()).isEqualTo(architecture);
            assertThat(platforms.add(os + "/" + architecture)).as("one runtime per platform").isTrue();
            assertThat(config.path("config").path("User").asText()).isEqualTo("nonroot");
            assertThat(config.path("config").path("ExposedPorts").size()).isZero();
            assertThat(config.path("config").path("WorkingDir").asText()).isEqualTo("/var/faultora");
        }
    }

    private JsonNode blob(Path archive, JsonNode descriptor) throws Exception {
        String digest = descriptor.path("digest").asText();
        assertThat(digest).matches("sha256:[0-9a-f]{64}");
        var read = command(30, "tar", "-xOf", archive.toString(), "blobs/sha256/" + digest.substring(7));
        assertThat(read.exitValue()).as(read.output()).isZero();
        byte[] bytes = read.output().getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isEqualTo(descriptor.path("size").asInt());
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo(digest.substring(7));
        return JSON.readTree(bytes);
    }

    private JsonNode entry(Path archive, String name) throws Exception {
        var read = command(30, "tar", "-xOf", archive.toString(), name);
        assertThat(read.exitValue()).as(read.output()).isZero();
        return JSON.readTree(read.output());
    }

    private ImageCommand.Result command(int seconds, String... args) throws Exception {
        return ImageCommand.run(ROOT, directory, seconds, args);
    }
}
