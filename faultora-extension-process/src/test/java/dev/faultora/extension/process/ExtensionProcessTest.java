package dev.faultora.extension.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionProcessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExtensionProcess.Provider PROVIDER = new ExtensionProcess.Provider("renderer", "fixture.text");
    private static final ExtensionProcess.Declaration PLUGIN = new ExtensionProcess.Declaration("fixture.peer", "1", Set.of(PROVIDER));
    @TempDir static Path compilation;
    @TempDir Path directory;
    private static byte[] peerClass;

    @BeforeAll
    static void compileIndependentPlugin() throws Exception {
        Path source = compilation.resolve("fixture/Peer.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package fixture;
                import com.fasterxml.jackson.databind.*;
                import com.fasterxml.jackson.databind.node.ObjectNode;
                import dev.faultora.extension.protocol.*;
                import java.nio.file.*;
                public class Peer {
                    static final ObjectMapper JSON = new ObjectMapper();
                    static JsonNode config;
                    static {
                        try {
                            config = JSON.readTree(Peer.class.getClassLoader().getResourceAsStream("fixture/data.json"));
                            Files.writeString(Path.of(config.path("marker").asText()), "loaded");
                        } catch (Exception failure) { throw new ExceptionInInitializerError(); }
                    }
                    public static void main(String[] args) throws Exception {
                        String mode = config.path("mode").asText();
                        if (mode.equals("silent")) Thread.sleep(600000);
                        ExtensionCodec read = new ExtensionCodec(JsonFrameCodec.MAX_FRAME_BYTES);
                        JsonFrameCodec write = new JsonFrameCodec(JsonFrameCodec.MAX_FRAME_BYTES);
                        ObjectNode hello = (ObjectNode) read.read(System.in);
                        ObjectNode ready = hello.deepCopy();
                        ready.put("kind", "ready").put("version", "1.0");
                        ((ObjectNode) ready.get("data")).remove("versions");
                        if (mode.equals("bad-version")) ready.put("version", "2.0");
                        if (mode.equals("bad-identity")) ((ObjectNode) ready.path("data").path("plugin")).put("id", "other");
                        if (mode.equals("bad-provider")) ((ObjectNode) ready.path("data").path("providers").get(0)).put("kind", "connector");
                        if (mode.equals("short-limit")) ((ObjectNode) ready.get("data")).put("maxFrameBytes", 1024);
                        write.write(System.out, ready); System.out.flush();
                        while (true) {
                            ObjectNode message = (ObjectNode) read.read(System.in);
                            if (message == null) return;
                            if (message.path("kind").asText().equals("shutdown")) {
                                if (mode.equals("stop-hang")) Thread.sleep(600000);
                                message.put("kind", "stopped"); message.set("data", JSON.createObjectNode());
                                write.write(System.out, message); System.out.flush();
                                if (mode.equals("stopped-alive")) Thread.sleep(600000);
                                return;
                            }
                            if (mode.equals("hang")) Thread.sleep(600000);
                            if (mode.equals("crash")) throw new IllegalStateException("SYNTHETIC_PRIVATE_PROVIDER_ERROR");
                            if (mode.equals("oversized")) {
                                System.out.write(new byte[]{-1,-1,-1,-1}); System.out.flush(); Thread.sleep(600000);
                            }
                            if (mode.equals("malformed")) {
                                System.out.write(new byte[]{0,0,0,1,123}); System.out.flush(); Thread.sleep(600000);
                            }
                            if (mode.equals("stderr")) System.err.write("SYNTHETIC_PRIVATE_STDERR".repeat(1000).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            ObjectNode provider = (ObjectNode) message.path("data").path("provider");
                            ObjectNode data = JSON.createObjectNode(); data.set("provider", provider);
                            String value = new String(Peer.class.getClassLoader().getResourceAsStream("fixture/payload.txt").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            data.set("value", JSON.createObjectNode().put("text", value));
                            message.put("kind", "result"); message.set("data", data);
                            if (mode.equals("wrong-id")) message.put("id", "2");
                            if (mode.equals("wrong-session")) message.put("sessionId", "f".repeat(32));
                            write.write(System.out, message); System.out.flush();
                        }
                    }
                }
                """);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "-classpath", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "-d", compilation.toString(), source.toString());
        assertThat(code).as(errors.toString(StandardCharsets.UTF_8)).isZero();
        peerClass = Files.readAllBytes(compilation.resolve("fixture/Peer.class"));
    }

    private Path artifact(String mode, String value, Path marker) throws Exception {
        Path jar = directory.resolve("source-" + UUID.randomUUID() + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : Map.of("fixture/Peer.class", peerClass,
                    "fixture/data.json", JSON.writeValueAsBytes(Map.of("mode", mode, "marker", marker.toString())),
                    "fixture/payload.txt", value.getBytes(StandardCharsets.UTF_8)).entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey())); out.write(entry.getValue()); out.closeEntry();
            }
        }
        return jar;
    }

    private static String digest(Path jar) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }

    private static List<Path> classpath() {
        return Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(java.io.File.pathSeparator)).map(Path::of).toList();
    }

    private ExtensionProcess start(StagedArtifact staged, Duration lifetime) throws Exception {
        return ExtensionProcess.start(staged, "fixture.Peer", classpath(), PLUGIN, lifetime);
    }

    @Test
    void sourceReplacementAndLaterCopyReplacementNeverChangeTheLoadedBytes() throws Exception {
        Path marker = directory.resolve("loaded");
        Path source = artifact("normal", "ORIGINAL", marker);
        Path replacement = artifact("normal", "REPLACED", marker);
        try (var staged = StagedArtifact.copy(source, digest(source), directory)) {
            Files.copy(replacement, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (var peer = start(staged, Duration.ofSeconds(10))) {
                assertThat(peer.request(PROVIDER, JSON.createObjectNode(), Duration.ofSeconds(2)).path("text").asText()).isEqualTo("ORIGINAL");
                Files.copy(replacement, staged.artifact(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                assertThat(peer.request(PROVIDER, JSON.createObjectNode(), Duration.ofSeconds(2)).path("text").asText()).isEqualTo("ORIGINAL");
                peer.stop(Duration.ofSeconds(2));
                assertThat(peer.isAlive()).isFalse();
                assertThat(peer.cleanupConfirmed()).isTrue();
                assertThat(marker).hasContent("loaded");
            }
        }
    }

    @Test
    void copyReplacementBeforeLaunchIsRefusedBeforePluginInitialization() throws Exception {
        Path marker = directory.resolve("must-not-load");
        Path source = artifact("normal", "ORIGINAL", marker);
        try (var staged = StagedArtifact.copy(source, digest(source), directory)) {
            Files.write(staged.artifact(), new byte[]{1, 2, 3});
            assertThatThrownBy(() -> start(staged, Duration.ofSeconds(5))).isInstanceOf(IOException.class);
            assertThat(marker).doesNotExist();
        }
    }

    @Test
    void aWrongApprovedDigestDoesNotLeaveAStagedDirectory() throws Exception {
        Path source = artifact("normal", "ORIGINAL", directory.resolve("marker"));
        assertThatThrownBy(() -> StagedArtifact.copy(source, "sha256:" + "0".repeat(64), directory))
                .hasMessage("ARTIFACT_CHANGED");
        try (var files = Files.list(directory)) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith("extension-")).toList()).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"bad-version,INCOMPATIBLE_VERSION", "bad-identity,IDENTITY_MISMATCH", "bad-provider,UNSUPPORTED_PROVIDER",
            "silent,DEADLINE_EXCEEDED"})
    void invalidOrMissingHandshakeNeverProducesAnAdmittedPeer(String mode, String code) throws Exception {
        Path source = artifact(mode, "ORIGINAL", directory.resolve("marker"));
        try (var staged = StagedArtifact.copy(source, digest(source), directory)) {
            assertThatThrownBy(() -> start(staged, Duration.ofMillis(mode.equals("silent") ? 800 : 5000)))
                    .isInstanceOf(IOException.class).hasMessage(code);
        }
    }

    @ParameterizedTest
    @CsvSource({"wrong-id,ID_MISMATCH", "wrong-session,SESSION_MISMATCH", "oversized,MESSAGE_TOO_LARGE",
            "malformed,MALFORMED_MESSAGE", "hang,DEADLINE_EXCEEDED", "crash,PEER_CLOSED", "stderr,STDERR_LIMIT_EXCEEDED"})
    void aBadReplyOrUncooperativePeerIsClosedWithConfirmedProcessExit(String mode, String code) throws Exception {
        Path source = artifact(mode, "ORIGINAL", directory.resolve("marker"));
        try (var staged = StagedArtifact.copy(source, digest(source), directory);
             var peer = start(staged, Duration.ofSeconds(10))) {
            assertThatThrownBy(() -> peer.request(PROVIDER, JSON.createObjectNode(),
                    Duration.ofMillis(mode.equals("hang") ? 150 : 3000)))
                    .isInstanceOf(IOException.class).hasMessage(code);
            assertThat(peer.isAlive()).isFalse();
            assertThat(peer.cleanupConfirmed()).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"stop-hang,DEADLINE_EXCEEDED", "stopped-alive,SHUTDOWN_DEADLINE_EXCEEDED"})
    void shutdownRequiresBothTheAcknowledgementAndActualExit(String mode, String code) throws Exception {
        Path source = artifact(mode, "ORIGINAL", directory.resolve("marker"));
        try (var staged = StagedArtifact.copy(source, digest(source), directory);
             var peer = start(staged, Duration.ofSeconds(10))) {
            assertThatThrownBy(() -> peer.stop(Duration.ofMillis(150))).hasMessage(code);
            assertThat(peer.isAlive()).isFalse();
        }
    }

    @Test
    void negotiatedCeilingAppliesBeforeSendingAnOversizedRequest() throws Exception {
        Path source = artifact("short-limit", "ORIGINAL", directory.resolve("marker"));
        try (var staged = StagedArtifact.copy(source, digest(source), directory);
             var peer = start(staged, Duration.ofSeconds(10))) {
            assertThatThrownBy(() -> peer.request(PROVIDER, JSON.createObjectNode().put("text", "x".repeat(1024)), Duration.ofSeconds(2)))
                    .hasMessage("MESSAGE_TOO_LARGE");
            assertThat(peer.isAlive()).isFalse();
        }
    }
}
