package dev.faultora.extension.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.extension.protocol.JsonFrameCodec;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.RunId;
import dev.faultora.spi.contract.ReportRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionReportRendererTest {
    private static final String FORMAT = "sample.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration LIFETIME = Duration.ofSeconds(15);
    private static final ExtensionProcess.Declaration DECLARATION = new ExtensionProcess.Declaration(
            "fixture.renderer", "1", Set.of(new ExtensionProcess.Provider("renderer", FORMAT)));
    private static final RunId RUN = new RunId("run-synthetic-1");
    private static final RunEvent COMPLETED = new RunEvent.RunCompleted("RUN_COMPLETED", 1000, RUN, 0, 0, 0, 1);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path directory;

    @Test
    void versionOneExchangeMatchesThePublishedFixtures() throws Exception {
        var input = fixture("request").path("data").path("input");
        assertThat(RenderWire.input(List.of(COMPLETED), JsonFrameCodec.MAX_FRAME_BYTES)).isEqualTo(input);
        assertThat(RenderWire.readInput(input)).containsExactly(COMPLETED);
        var value = fixture("result").path("data").path("value");
        assertThat(RenderWire.result(RenderWire.readResult(value))).isEqualTo(value);
    }

    @Test
    void verifiedChildRendersEventsToTheParentWriter() throws Exception {
        try (var staged = stage(FixtureRenderer.class);
             var renderer = ExtensionReportRenderer.start(staged, runtimeClasspath(), DECLARATION, TIMEOUT, LIFETIME)) {
            assertThat(renderer.format()).isEqualTo(FORMAT);
            var output = new TrackingWriter();
            renderer.render(List.of(started(Map.of()), COMPLETED), output);
            assertThat(output.toString()).isEqualTo("RUN_STARTED,RUN_COMPLETED: run-synthetic-1 — 東京");
            renderer.render(List.of(), output);
            assertThat(output.toString()).endsWith("empty");
            assertThat(output.closed).isFalse();
        }
    }

    @Test
    void wrongFormatIsRefusedDuringHandshake() throws Exception {
        var wrongFormat = new ExtensionProcess.Declaration(
                "fixture.renderer", "1", Set.of(new ExtensionProcess.Provider("renderer", "other")));
        try (var staged = stage(FixtureRenderer.class)) {
            assertThatThrownBy(() -> ExtensionReportRenderer.start(staged, runtimeClasspath(), wrongFormat, TIMEOUT, LIFETIME))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void oversizeInputIsRejectedBeforeUsingThePeer() throws Exception {
        var oversize = started(Map.of("fixture", "東京".repeat(JsonFrameCodec.MAX_FRAME_BYTES / 3)));
        try (var staged = stage(FixtureRenderer.class);
             var renderer = ExtensionReportRenderer.start(staged, runtimeClasspath(), DECLARATION, TIMEOUT, LIFETIME)) {
            var output = new StringWriter();
            assertThatThrownBy(() -> renderer.render(List.of(oversize), output)).isInstanceOf(IOException.class);
            assertThat(output.toString()).isEmpty();
            renderer.render(List.of(COMPLETED), output);
            assertThat(output.toString()).contains("RUN_COMPLETED");
        }
    }

    @Test
    void providerFailureAndCaughtOutputOverflowNeverWritePartialText() throws Exception {
        for (Class<?> type : List.of(FailedRenderer.class, OverflowRenderer.class)) {
            try (var staged = stage(type);
                 var renderer = ExtensionReportRenderer.start(staged, runtimeClasspath(), DECLARATION, TIMEOUT, LIFETIME)) {
                var output = new StringWriter();
                assertThatThrownBy(() -> renderer.render(List.of(COMPLETED), output))
                        .isInstanceOf(IOException.class).hasMessage("EXTENSION_RENDER_FAILED");
                assertThat(output.toString()).isEmpty();
            }
        }
    }

    @Test
    void deadlineDoesNotWriteBufferedTextOrPermitAnotherCall() throws Exception {
        try (var staged = stage(SlowRenderer.class);
             var renderer = ExtensionReportRenderer.start(staged, runtimeClasspath(), DECLARATION,
                     Duration.ofMillis(200), LIFETIME)) {
            var output = new StringWriter();
            long started = System.nanoTime();
            assertThatThrownBy(() -> renderer.render(List.of(COMPLETED), output))
                    .isInstanceOf(IOException.class).hasMessage("EXTENSION_RENDER_FAILED");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofMillis(150));
            assertThatThrownBy(() -> renderer.render(List.of(COMPLETED), output))
                    .isInstanceOf(IOException.class).hasMessage("EXTENSION_RENDER_FAILED");
            assertThat(output.toString()).isEmpty();
        }
    }

    @Test
    void rejectsWrongTypedOrIncompleteEventAndNonTextReplies() throws Exception {
        var missing = fixture("request").path("data").path("input").deepCopy();
        ((ObjectNode) missing.path("events").get(0)).remove("totalNodes");
        assertThatThrownBy(() -> RenderWire.readInput(missing)).isInstanceOf(IOException.class);
        var mistyped = fixture("request").path("data").path("input").deepCopy();
        ((ObjectNode) mistyped.path("events").get(0)).put("totalNodes", "0");
        assertThatThrownBy(() -> RenderWire.readInput(mistyped)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> RenderWire.readResult(JSON.createObjectNode().put("text", 1)))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> RenderWire.readResult(JSON.createObjectNode().put("text", "ok").put("path", "x")))
                .isInstanceOf(IOException.class);
    }

    private static RunEvent started(Map<String, Object> config) {
        return new RunEvent.RunStarted("RUN_STARTED", 999, RUN, "synthetic-scenario", "synthetic-catalog", 42, config);
    }

    private static JsonNode fixture(String kind) throws IOException {
        try (var input = ExtensionReportRendererTest.class.getResourceAsStream("/extension-wire-v1/render-" + kind + ".json")) {
            if (input == null) throw new IOException("Missing renderer fixture");
            return JSON.readTree(input);
        }
    }

    private StagedArtifact stage(Class<?> entrypoint) throws IOException {
        Path source = directory.resolve("plugin.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(source))) {
            String entry = entrypoint.getName().replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(entry));
            try (var input = entrypoint.getResourceAsStream("/" + entry)) {
                input.transferTo(jar);
            }
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/services/" + ReportRenderer.class.getName()));
            jar.write(entrypoint.getName().getBytes(StandardCharsets.UTF_8));
        }
        String digest = "sha256:" + HexFormat.of().formatHex(StagedArtifact.sha256().digest(Files.readAllBytes(source)));
        return StagedArtifact.copy(source, digest, directory);
    }

    private static List<Path> runtimeClasspath() throws Exception {
        Path tests = Path.of(ExtensionReportRendererTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(File.pathSeparator)).map(Path::of).map(Path::toAbsolutePath).filter(path -> !path.equals(tests)).toList();
    }

    private static final class TrackingWriter extends StringWriter {
        private boolean closed;
        @Override public void close() { closed = true; }
    }

    public static final class FixtureRenderer implements ReportRenderer {
        @Override public String format() { return "sample.json"; }

        @Override public void render(List<RunEvent> events, Writer output) throws IOException {
            if (events.isEmpty()) {
                output.write("empty");
                return;
            }
            output.write(String.join(",", events.stream().map(RunEvent::eventType).toList()));
            output.write(": " + events.getFirst().runId().value() + " — 東京");
        }
    }

    public static final class FailedRenderer implements ReportRenderer {
        @Override public String format() { return "sample.json"; }

        @Override public void render(List<RunEvent> events, Writer output) throws IOException {
            output.write("synthetic buffer");
            throw new IOException("fixture provider detail");
        }
    }

    public static final class OverflowRenderer implements ReportRenderer {
        @Override public String format() { return "sample.json"; }

        @Override public void render(List<RunEvent> events, Writer output) throws IOException {
            output.write("synthetic buffer");
            try {
                output.write("x".repeat(JsonFrameCodec.MAX_FRAME_BYTES));
            } catch (IOException ignored) {
                // A plugin cannot convert overflow into a successful partial output.
            }
        }
    }

    public static final class SlowRenderer implements ReportRenderer {
        @Override public String format() { return "sample.json"; }

        @Override public void render(List<RunEvent> events, Writer output) throws IOException {
            output.write("synthetic buffer");
            try {
                Thread.sleep(3000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("fixture cancelled");
            }
        }
    }
}
