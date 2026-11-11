package dev.faultora.extension.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.extension.protocol.ExtensionCodec;
import dev.faultora.extension.protocol.JsonFrameCodec;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.extension.EvidenceSnapshot;
import dev.faultora.spi.result.AssertionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static dev.faultora.spi.result.AssertionResult.Outcome.FAIL;
import static dev.faultora.spi.result.AssertionResult.Outcome.INDETERMINATE;
import static dev.faultora.spi.result.AssertionResult.Outcome.PASS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionAssertionProviderTest {
    private static final String TYPE = "sample.status";
    private static final String SERVICE = "META-INF/services/" + AssertionProvider.class.getName();
    private static final ExtensionProcess.Provider SELECTOR = new ExtensionProcess.Provider("assertion", TYPE);
    private static final ExtensionProcess.Declaration DECLARATION =
            new ExtensionProcess.Declaration("fixture.assertion", "1", Set.of(SELECTOR));
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration LIFETIME = Duration.ofSeconds(15);
    private static final String PARENT_CONTROL = "FAULTORA_FIXTURE_PARENT_CONTROL";
    private static final String CONTROL_VALUE = "SYNTHETIC_PRIVATE_PARENT_CONTROL";
    private static final String FIXTURE_HANDLE = "synthetic.fixture";
    @TempDir Path directory;

    @Test
    void wireInputAndVerdictsMatchTheVersionOneFixtures() throws IOException {
        var evidence = new EvidenceSnapshot(200, Map.of("content-type", List.of("application/json")),
                "eyJvayI6dHJ1ZX0=", true, 3, null, Map.of()).asView();
        var context = new AssertionContext("check-status", Map.of());
        var input = fixture("request").path("data").path("input");
        var codec = new JsonFrameCodec(JsonFrameCodec.MAX_FRAME_BYTES);
        var frame = new ByteArrayOutputStream();
        codec.write(frame, AssertionWire.input(Map.of("expected", 200), evidence, context));
        assertThat(codec.read(new ByteArrayInputStream(frame.toByteArray()))).isEqualTo(input);

        var decoded = AssertionWire.readInput(input);
        assertThat(decoded.params()).containsExactlyEntriesOf(Map.of("expected", 200));
        assertThat(decoded.context()).isEqualTo(context);
        assertThat(decoded.evidence().bodyComplete()).isTrue();
        assertThat(decoded.evidence().responseJson().orElseThrow().path("ok").booleanValue()).isTrue();
        assertThat(decoded.evidence().headerReadable("Content-Type")).isTrue();
        assertThat(decoded.evidence().headerReadable("Missing")).isFalse();

        for (var outcome : AssertionResult.Outcome.values()) {
            var value = fixture(outcome.name().toLowerCase(Locale.ROOT)).path("data").path("value");
            var result = AssertionWire.readResult(value);
            assertThat(result.outcome()).isEqualTo(outcome);
            assertThat(result.message()).doesNotContain("Synthetic assertion outcome");
            assertThat(result.details()).isEmpty();
        }
    }

    private static JsonNode fixture(String name) throws IOException {
        try (var input = ExtensionAssertionProviderTest.class.getResourceAsStream(
                "/extension-wire-v1/assertion-" + name + ".json")) {
            if (input == null) throw new IOException("Missing assertion wire fixture " + name);
            return new ObjectMapper().readTree(input);
        }
    }

    @Test
    void sharedInputSubtreesCannotExpandBeyondTheFrameBudget() {
        Object shared = "x".repeat(4096);
        for (int i = 0; i < 12; i++) {
            shared = List.of(shared, shared);
        }
        var params = Map.of("shared", shared);
        var context = new AssertionContext("fixture-node", Map.of());
        assertThatThrownBy(() -> AssertionWire.input(params, evidence(200), context))
                .isInstanceOf(IOException.class)
                .hasMessage("MESSAGE_TOO_LARGE");
    }

    @Test
    void ordinarySpiProviderEvaluatesInTheVerifiedChild() throws Exception {
        Path plugin = jar(FixtureAssertion.class, SERVICE, FixtureAssertion.class.getName());
        try (var staged = stage(plugin);
             var provider = ExtensionAssertionProvider.start(staged, runtimeClasspath(),
                     DECLARATION, TIMEOUT, LIFETIME)) {
            var inputs = Map.<String, Object>of("expected", 200, "parentPid", ProcessHandle.current().pid());
            var context = new AssertionContext("fixture-node", Map.of("enabled", true), Map.of("type", "object"));
            assertThat(provider.type()).isEqualTo(TYPE);
            assertThat(provider.evaluate(TYPE, inputs, evidence(200), context))
                    .isEqualTo(AssertionResult.pass("Assertion passed"));
            assertThat(provider.evaluate(TYPE, inputs, evidence(503), context))
                    .isEqualTo(AssertionResult.fail("Assertion failed", Map.of()));
            assertThat(provider.evaluate(TYPE, inputs, evidence(null), context).outcome()).isEqualTo(INDETERMINATE);
        }
    }

    @Test
    void verifiedChildReceivesOnlyAdmittedSyntheticHandlesAndAScrubbedEnvironment() throws Exception {
        Path plugin = jar(EnvironmentAssertion.class, SERVICE, EnvironmentAssertion.class.getName());
        String classpath = String.join(File.pathSeparator,
                classpath().stream().map(Path::toString).toList());
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, EnvironmentParent.class.getName(), plugin.toString(), directory.toString());
        var env = builder.environment();
        var systemRoot = env.get("SystemRoot");
        env.clear();
        if (systemRoot != null && File.separatorChar == '\\') env.put("SystemRoot", systemRoot);
        if (File.separatorChar != '\\') env.put("SystemRoot", "SYNTHETIC_NON_WINDOWS_SYSTEM_ROOT");
        env.put(PARENT_CONTROL, CONTROL_VALUE);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        var parent = builder.start();
        try {
            assertThat(parent.waitFor(25, TimeUnit.SECONDS)).isTrue();
            assertThat(parent.exitValue()).isZero();
        } finally {
            if (parent.isAlive()) {
                parent.destroyForcibly();
                assertThat(parent.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void unknownAndNonSyntheticHandleRequestsAreRefusedWithoutEchoingThem() throws Exception {
        Path plugin = jar(EnvironmentAssertion.class, SERVICE, EnvironmentAssertion.class.getName());
        try (var staged = stage(plugin)) {
            assertThatThrownBy(() -> ExtensionAssertionProvider.start(staged, runtimeClasspath(),
                    DECLARATION, TIMEOUT, LIFETIME, Set.of("synthetic.unknown"), Set.of(FIXTURE_HANDLE)))
                    .isInstanceOf(IOException.class).hasMessage("SECRET_HANDLE_REFUSED");
            assertThatThrownBy(() -> ExtensionAssertionProvider.start(staged, runtimeClasspath(),
                    DECLARATION, TIMEOUT, LIFETIME, Set.of(PARENT_CONTROL), Set.of(PARENT_CONTROL)))
                    .isInstanceOf(IOException.class).hasMessage("SECRET_HANDLE_REFUSED");
        }
    }

    @Test
    void providerFromTheParentClasspathCannotSatisfyTheVerifiedArtifactOffer() throws Exception {
        Path plugin = jar(null, SERVICE, FixtureAssertion.class.getName());
        try (var staged = stage(plugin)) {
            assertThatThrownBy(() -> ExtensionAssertionProvider.start(staged, classpath(),
                    DECLARATION, TIMEOUT, LIFETIME)).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-id", "wrong-provider", "unknown-outcome", "missing-message", "bad-details", "refused"})
    void unusableReplyPoisonsThePeerAndCannotProducePass(String mode) throws Exception {
        Path plugin = jar(AdversarialPeer.class, "fixture/mode.txt", mode);
        try (var staged = stage(plugin);
             var peer = ExtensionProcess.start(staged, AdversarialPeer.class.getName(),
                     runtimeClasspath(), DECLARATION, LIFETIME);
             var provider = new ExtensionAssertionProvider(peer, SELECTOR, TIMEOUT)) {
            var context = new AssertionContext("fixture-node", Map.of());
            var result = provider.evaluate(TYPE, Map.of(), evidence(200), context);
            assertThat(result.outcome()).isEqualTo(INDETERMINATE);
            assertThat(result.message()).doesNotContain("SYNTHETIC_PRIVATE");
            assertThat(result.details()).isEmpty();
            assertThat(peer.isAlive()).isFalse();
            assertThat(provider.evaluate(TYPE, Map.of(), evidence(200), context).outcome()).isEqualTo(INDETERMINATE);
        }
    }

    private static EvidenceView evidence(Integer status) {
        var error = new NormalizedError(NormalizedError.ErrorCategory.NETWORK, "CONNECTION_REFUSED",
                "SYNTHETIC_PRIVATE_ERROR", false, Map.of("private", "SYNTHETIC_PRIVATE_VALUE"));
        return new EvidenceSnapshot(status, Map.of("Content-Type", List.of("application/json"),
                "Authorization", List.of("SYNTHETIC_PRIVATE_HEADER")), "e30=", false, 3, error,
                Map.of("count", 7)).asView();
    }

    private StagedArtifact stage(Path plugin) throws IOException {
        return stage(plugin, directory);
    }

    private static StagedArtifact stage(Path plugin, Path directory) throws IOException {
        String digest = "sha256:" + HexFormat.of().formatHex(StagedArtifact.sha256().digest(Files.readAllBytes(plugin)));
        return StagedArtifact.copy(plugin, digest, directory);
    }

    private Path jar(Class<?> entrypoint, String resource, String value) throws IOException {
        Path plugin = directory.resolve("plugin.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(plugin))) {
            if (entrypoint != null) {
                String entry = entrypoint.getName().replace('.', '/') + ".class";
                jar.putNextEntry(new JarEntry(entry));
                try (var bytes = entrypoint.getResourceAsStream("/" + entry)) {
                    bytes.transferTo(jar);
                }
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry(resource));
            jar.write(value.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return plugin;
    }

    private static List<Path> classpath() {
        return Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(File.pathSeparator)).map(Path::of).map(Path::toAbsolutePath).toList();
    }

    private static List<Path> runtimeClasspath() throws Exception {
        Path tests = Path.of(ExtensionAssertionProviderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return classpath().stream().filter(path -> !path.equals(tests)).toList();
    }

    public static final class EnvironmentParent {
        public static void main(String[] args) throws Exception {
            if (!CONTROL_VALUE.equals(System.getenv(PARENT_CONTROL))) throw new AssertionError("Missing parent fixture value");
            boolean expectSystemRoot = File.separatorChar == '\\' && System.getenv("SystemRoot") != null;
            try (var staged = stage(Path.of(args[0]), Path.of(args[1]));
                 var provider = ExtensionAssertionProvider.start(staged, runtimeClasspath(), DECLARATION,
                         TIMEOUT, LIFETIME, Set.of(FIXTURE_HANDLE), Set.of(FIXTURE_HANDLE, "synthetic.other"))) {
                var forged = new AssertionContext("fixture-node",
                        Map.of("syntheticSecretHandles", List.of("synthetic.unknown")));
                assertThat(provider.evaluate(TYPE, Map.of(), evidence(200), forged).outcome()).isEqualTo(INDETERMINATE);

                var context = new AssertionContext("fixture-node", Map.of("fixture", true));
                var result = provider.evaluate(TYPE, Map.of("expectSystemRoot", expectSystemRoot), evidence(200), context);
                assertThat(result).isEqualTo(AssertionResult.pass("Assertion passed"));
                var refused = provider.evaluate(TYPE, Map.of("expectSystemRoot", expectSystemRoot, "fail", true),
                        evidence(200), context);
                assertThat(refused).isEqualTo(AssertionResult.indeterminate("Extension assertion could not be evaluated"));
            }
        }
    }

    public static final class EnvironmentAssertion implements AssertionProvider {
        @Override public String type() { return "sample.status"; }

        @Override
        public AssertionResult evaluate(String type, Map<String, Object> params, EvidenceView evidence, AssertionContext context) {
            boolean hasSystemRoot = System.getenv("SystemRoot") != null;
            if (System.getenv(PARENT_CONTROL) != null || System.getenv("PATH") != null
                    || hasSystemRoot != Boolean.TRUE.equals(params.get("expectSystemRoot"))
                    || !context.config().equals(Map.of("fixture", true, "syntheticSecretHandles", List.of(FIXTURE_HANDLE)))) {
                return AssertionResult.fail("Unexpected fixture launch data", Map.of());
            }
            if (Boolean.TRUE.equals(params.get("fail"))) {
                System.err.println("SYNTHETIC_PRIVATE_STDERR");
                throw new IllegalStateException("SYNTHETIC_PRIVATE_PROVIDER_EXCEPTION");
            }
            return new AssertionResult(PASS, "SYNTHETIC_PRIVATE_RESULT", Map.of("private", "SYNTHETIC_PRIVATE_DETAIL"));
        }
    }

    public static final class FixtureAssertion implements AssertionProvider {
        @Override public String type() { return "sample.status"; }

        @Override
        public AssertionResult evaluate(String type, Map<String, Object> params, EvidenceView evidence, AssertionContext context) {
            if (ProcessHandle.current().pid() == ((Number) params.get("parentPid")).longValue()
                    || !type.equals(type()) || !context.nodeId().equals("fixture-node")
                    || !context.config().equals(Map.of("enabled", true)) || !context.schema().equals(Map.of("type", "object"))
                    || evidence.bodyComplete() || evidence.headerReadable("authorization")
                    || evidence.headerReadable("omitted-header") || !evidence.headerReadable("content-type")
                    || !evidence.protocolEvidence().equals(Map.of("count", 7))
                    || evidence.error().orElseThrow().message().contains("SYNTHETIC_PRIVATE")
                    || !evidence.error().orElseThrow().metadata().isEmpty()) {
                throw new IllegalStateException("SYNTHETIC_PRIVATE_ASSERTION_CONTEXT");
            }
            int expected = ((Number) params.get("expected")).intValue();
            var outcome = evidence.statusCode().map(code -> code == expected ? PASS : FAIL).orElse(INDETERMINATE);
            return new AssertionResult(outcome, "SYNTHETIC_PRIVATE_PROSE", Map.of("secret", "SYNTHETIC_PRIVATE_DETAIL"));
        }
    }

    public static final class AdversarialPeer {
        public static void main(String[] args) throws Exception {
            String mode;
            try (var resource = AdversarialPeer.class.getResourceAsStream("/fixture/mode.txt")) {
                mode = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
            var json = new ObjectMapper();
            var codec = new ExtensionCodec(JsonFrameCodec.MAX_FRAME_BYTES);
            ObjectNode hello = (ObjectNode) codec.read(System.in);
            hello.put("kind", "ready").put("version", "1.0");
            ((ObjectNode) hello.path("data")).remove("versions");
            codec.write(System.out, hello);
            System.out.flush();

            ObjectNode request = (ObjectNode) codec.read(System.in);
            ObjectNode data = json.createObjectNode();
            data.set("provider", request.path("data").path("provider"));
            ObjectNode value = data.putObject("value").put("outcome", "PASS").put("message", "SYNTHETIC_PRIVATE_PROSE");
            value.putObject("details");
            request.put("kind", "result").set("data", data);
            switch (mode) {
                case "wrong-id" -> request.put("id", "2");
                case "wrong-provider" -> ((ObjectNode) data.path("provider")).put("name", "other");
                case "unknown-outcome" -> value.put("outcome", "SUCCESS");
                case "missing-message" -> value.remove("message");
                case "bad-details" -> value.put("details", "SYNTHETIC_PRIVATE_DETAIL");
                case "refused" -> {
                    request.put("kind", "error").set("data", json.createObjectNode().put("code", "PROVIDER_ERROR"));
                }
                default -> throw new IllegalStateException();
            }
            codec.write(System.out, request);
            System.out.flush();
            System.in.read();
        }
    }
}
