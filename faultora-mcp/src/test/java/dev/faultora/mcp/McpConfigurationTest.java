package dev.faultora.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpConfigurationTest {
    @TempDir Path directory;

    private Path configuration(String content) throws IOException {
        Path file = directory.resolve("operator.json");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void executionRequiresACompleteOperatorConfigurationAndDoesNotCoercePermission() throws Exception {
        var keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var root = McpJson.MAPPER.createObjectNode();
        root.putArray("catalogs");
        var execution = root.putObject("execution").put("workingDirectory", ".").put("approvalPublicKey",
                java.util.Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        var loaded = McpConfiguration.load(configuration(root.toString()));
        assertThat(loaded.execution().approvalPublicKey()).isEqualTo(keys.getPublic());
        assertThat(loaded.execution().allowPrivate()).isFalse();
        execution.put("allowPrivate", "true");
        assertThatThrownBy(() -> McpConfiguration.load(configuration(root.toString()))).isInstanceOf(IOException.class);
        execution.remove("allowPrivate");
        execution.put("workingDirectory", "missing");
        assertThatThrownBy(() -> McpConfiguration.load(configuration(root.toString()))).isInstanceOf(IOException.class);
        execution.put("workingDirectory", ".").put("approvalPublicKey", "SYNTHETIC_PRIVATE_VALUE");
        assertThatThrownBy(() -> McpConfiguration.load(configuration(root.toString())))
                .isInstanceOf(IOException.class).hasMessageNotContaining(McpFixtures.MARKER);
    }

    @Test
    void relativeDeclaredSourcesAreSnapshottedAtStartup() throws Exception {
        Path source = directory.resolve("source.yaml");
        Files.writeString(source, McpFixtures.CATALOG);
        var configuration = McpConfiguration.load(configuration("""
                {"catalogs":[{"id":"demo","family":"openapi","path":"source.yaml"}]}
                """));
        Files.writeString(source, "changed after startup");
        assertThat(configuration.catalogs().get("demo").content()).isEqualTo(McpFixtures.CATALOG);
        assertThat(configuration.targets().allowedOperationClasses()).extracting(Enum::name)
                .containsExactly("READ_ONLY");
        assertThat(configuration.targets().allowedFaultTypes()).isEmpty();
        assertThatThrownBy(() -> configuration.catalogs().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingUndeclaredAndDuplicateConfigurationFieldsAreSanitized() throws Exception {
        for (String json : java.util.List.of("{}", "null", "[]", "{", "{} {}",
                "{\"catalogs\":[],\"catalogs\":[]}",
                "{\"catalogs\":[],\"target\":\"SYNTHETIC_PRIVATE_VALUE\"}",
                "{\"catalogs\":[],\"allowedOperationClasses\":[]}",
                "{\"catalogs\":[],\"allowedFaultTypes\":[\"unknown\"]}")) {
            Path path = configuration(json);
            assertThatThrownBy(() -> McpConfiguration.load(path)).isInstanceOf(IOException.class)
                    .hasMessageNotContaining(McpFixtures.MARKER).hasNoCause();
        }
    }

    @Test
    void allowedFaultTypesCannotEnableAnExternalFaultBackend() throws Exception {
        var root = McpJson.MAPPER.createObjectNode();
        root.putArray("catalogs");
        var faults = root.putArray("allowedFaultTypes")
                .add("http-latency").add("http-error").add("http-response-loss");
        var loaded = McpConfiguration.load(configuration(root.toString()));
        assertThat(loaded.targets().allowedFaultTypes())
                .containsExactlyInAnyOrder("http-latency", "http-error", "http-response-loss");

        faults.add("network-latency");
        assertThatThrownBy(() -> McpConfiguration.load(configuration(root.toString())))
                .isInstanceOf(IOException.class).hasNoCause();
    }

    @Test
    void declarationsRequireUniqueIdsAndKnownImporterFamilies() throws Exception {
        Files.writeString(directory.resolve("source.yaml"), McpFixtures.CATALOG);
        String entry = "{\"id\":\"demo\",\"family\":\"openapi\",\"path\":\"source.yaml\"}";
        for (String json : java.util.List.of(
                "{\"catalogs\":[" + entry + "," + entry + "]}",
                "{\"catalogs\":[" + entry.replace("openapi", "arbitrary-plugin") + "]}",
                "{\"catalogs\":[" + entry.replace("demo", "../outside") + "]}",
                "{\"catalogs\":[" + entry.replace("source.yaml", "absent.yaml") + "]}")) {
            Path path = configuration(json);
            assertThatThrownBy(() -> McpConfiguration.load(path)).isInstanceOf(IOException.class).hasNoCause();
        }
    }

    @Test
    void fileAndConfigurationByteLimitsAreEnforcedBeforeImport() throws Exception {
        Path source = directory.resolve("source.yaml");
        Files.writeString(source, "x".repeat(1_048_577));
        Path declared = configuration("{\"catalogs\":[{\"id\":\"demo\",\"family\":\"openapi\",\"path\":\"source.yaml\"}]}");
        assertThatThrownBy(() -> McpConfiguration.load(declared)).isInstanceOf(IOException.class);
        Path oversized = configuration(" ".repeat(65_537));
        assertThatThrownBy(() -> McpConfiguration.load(oversized)).isInstanceOf(IOException.class);
    }

    @Test
    void directoriesCannotBeDeclaredAsSourceFiles() throws Exception {
        Files.createDirectory(directory.resolve("source.yaml"));
        Path path = configuration("{\"catalogs\":[{\"id\":\"demo\",\"family\":\"openapi\",\"path\":\"source.yaml\"}]}");
        assertThatThrownBy(() -> McpConfiguration.load(path)).isInstanceOf(IOException.class).hasNoCause();
    }

    @Test
    void redirectedSourceAncestorsAreRefusedOnBothPlatforms() throws Exception {
        Path actual = Files.createDirectory(directory.resolve("actual"));
        Files.writeString(actual.resolve("source.yaml"), McpFixtures.CATALOG);
        Path redirected = directory.resolve("redirected");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process junction = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    redirected.toString(), actual.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                assertThat(junction.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(junction.exitValue()).isZero();
            } finally {
                if (junction.isAlive()) {
                    junction.destroyForcibly();
                    junction.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        } else {
            Files.createSymbolicLink(redirected, actual);
        }
        try {
            Path path = configuration("{\"catalogs\":[{\"id\":\"demo\",\"family\":\"openapi\",\"path\":\"redirected/source.yaml\"}]}");
            assertThatThrownBy(() -> McpConfiguration.load(path)).isInstanceOf(IOException.class).hasNoCause();
        } finally {
            Files.delete(redirected);
        }
    }
}
