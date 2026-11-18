package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;
import dev.faultora.spi.extension.PluginManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class IsolatedRendererE2ETest {
    private static final String FORMAT = "custom";
    private static final String SECRET = "synthetic-renderer-authorization";
    private static final String RESPONSE_BODY = "synthetic-response-body";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata:
              name: isolated-renderer
            execute:
              - id: read
                operationId: read
            assertions:
              - id: check-status
                assertionType: status
                targetStep: read
                params: {expected: 200}
            """;

    @TempDir Path directory;

    @Test
    void packagedCliRendersProjectedEventsIntoAHostOwnedTextReport() throws Exception {
        Path plugin = fixture("valid", FixtureRenderer.class, "0.1", "");
        try (var target = target()) {
            var result = run("valid", target, plugin, IsolatedAssertionE2ETest.digest(plugin), FORMAT,
                    "--format", "json,custom");
            assertThat(result.exit()).as(result.log()).isZero();
            assertThat(result.log()).contains("Result: PASSED");
            assertThat(target.requests()).hasValue(1);
            assertThat(target.authorized()).isTrue();
            var journal = Files.readAllLines(result.reports().resolve("events.ndjson"));
            var custom = Files.readAllLines(result.reports().resolve("report.custom.txt"));
            var eventTypes = new ArrayList<String>();
            for (String event : journal) {
                eventTypes.add(JSON.readTree(event).path("eventType").textValue());
            }
            assertThat(custom).containsExactlyElementsOf(eventTypes);
            assertThat(custom).contains("EVIDENCE_CAPTURED");
            assertThat(custom.getLast()).isEqualTo("RUN_COMPLETED");
            assertThat(result.reports().resolve("report.json")).isRegularFile();
            assertThat(String.join("\n", journal)).doesNotContain(SECRET, RESPONSE_BODY);
        }
    }

    @Test
    void rejectsMissingChangedIncompatibleAndCapabilityRequestingArtifactsBeforeTargetCalls() throws Exception {
        Path compatible = fixture("compatible", FixtureRenderer.class, "0.1", "");
        Path incompatible = fixture("incompatible", FixtureRenderer.class, "99.0", "");
        Path capability = fixture("capability", FixtureRenderer.class, "0.1", """
                capabilities:
                  secretHandles:
                    - synthetic.renderer
                """);
        try (var target = target()) {
            String digest = IsolatedAssertionE2ETest.digest(compatible);
            assertRefused(run("missing", target, directory.resolve("missing.jar"), digest, FORMAT,
                    "--format", FORMAT), "could not be verified");
            assertRefused(run("digest", target, compatible, "sha256:" + "0".repeat(64), FORMAT,
                    "--format", FORMAT), "could not be verified");
            assertRefused(run("version", target, incompatible, IsolatedAssertionE2ETest.digest(incompatible), FORMAT,
                    "--format", FORMAT), "must support this Faultora version");
            assertRefused(run("capability", target, capability, IsolatedAssertionE2ETest.digest(capability), FORMAT,
                    "--format", FORMAT), "declare no capabilities");
            assertRefused(run("provider", target, compatible, digest, "other", "--format", "other"),
                    "could not be verified");
            assertThat(target.requests()).hasValue(0);
        }
    }

    @Test
    void rejectsPreviewClasspathBuiltInAndUnrequestedFormats() throws Exception {
        Path plugin = fixture("valid", FixtureRenderer.class, "0.1", "");
        try (var target = target()) {
            String digest = IsolatedAssertionE2ETest.digest(plugin);
            assertRefused(run("preview", target, plugin, digest, FORMAT,
                    "--format", FORMAT, "--preview"), "--preview or --allow-extension");
            assertRefused(run("classpath", target, plugin, digest, FORMAT,
                    "--format", FORMAT, "--allow-extension", "fixture.Class"), "--preview or --allow-extension");
            assertRefused(run("builtin", target, plugin, digest, "html", "--format", "html"), "--isolated-renderer");
            assertRefused(run("unsafe", target, plugin, digest, "custom:stream", "--format", "custom:stream"),
                    "--isolated-renderer");
            assertRefused(run("unrequested", target, plugin, digest, FORMAT, "--format", "json"),
                    "requires a matching --format");
            assertThat(target.requests()).hasValue(0);
        }
    }

    @Test
    void rendererFailureKeepsTheJournalAndBuiltInReportWithoutLeavingPartialText() throws Exception {
        Path plugin = fixture("failed", FailedRenderer.class, "0.1", "");
        try (var target = target()) {
            var result = run("failed", target, plugin, IsolatedAssertionE2ETest.digest(plugin), FORMAT,
                    "--format", "custom,json");
            assertThat(result.exit()).as(result.log()).isEqualTo(3);
            assertThat(result.log()).contains("provider details withheld").doesNotContain("synthetic renderer detail");
            assertThat(target.requests()).hasValue(1);
            assertThat(Files.readString(result.reports().resolve("events.ndjson"))).contains("RUN_COMPLETED");
            assertThat(result.reports().resolve("report.json")).isRegularFile();
            try (var reports = Files.list(result.reports())) {
                assertThat(reports.map(path -> path.getFileName().toString()).toList())
                        .containsExactlyInAnyOrder("events.ndjson", "report.json", "run-owner");
            }
        }
    }

    private static void assertRefused(Result result, String diagnostic) {
        assertThat(result.exit()).as(result.log()).isEqualTo(2);
        assertThat(result.log()).contains(diagnostic);
        assertThat(result.reports()).doesNotExist();
    }

    private Result run(String name, Target target, Path plugin, String digest, String format, String... extra)
            throws Exception {
        Path work = Files.createDirectory(directory.resolve(name + "-work"));
        Path scenario = Files.writeString(work.resolve("scenario.yaml"), SCENARIO);
        Path reports = work.resolve("reports");
        Path output = work.resolve("output.txt");
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
                System.getProperty("faultora.cliJar"), "test", "--scenario", scenario.toString(),
                "--target", "http://127.0.0.1:" + target.server().getAddress().getPort() + "/read",
                "--allow-private", "--auth-secret-id", "renderer-fixture", "--output", reports.toString(),
                "--isolated-renderer", format, digest, plugin.toString()));
        command.addAll(List.of(extra));
        var launch = new ProcessBuilder(command).directory(work.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        launch.environment().put("FAULTORA_SECRET_RENDERER_FIXTURE", SECRET);
        int exit = BoundedProcess.run(launch, Duration.ofSeconds(30), ignored -> { });
        return new Result(exit, Files.readString(output), reports);
    }

    private record Result(int exit, String log, Path reports) { }

    private static Target target() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        var authorized = new AtomicBoolean();
        server.createContext("/", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                authorized.set(("Bearer " + SECRET).equals(exchange.getRequestHeaders().getFirst("Authorization")));
                byte[] body = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        return new Target(server, calls, authorized);
    }

    private record Target(HttpServer server, AtomicInteger requests, AtomicBoolean authorized) implements AutoCloseable {
        @Override public void close() { server.stop(0); }
    }

    private Path fixture(String name, Class<?> renderer, String from, String capabilities) throws IOException {
        Path artifact = directory.resolve(name + ".jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            String entry = renderer.getName().replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(entry));
            try (var input = renderer.getResourceAsStream("/" + entry)) {
                input.transferTo(jar);
            }
            jar.closeEntry();
            writeEntry(jar, "META-INF/services/" + ReportRenderer.class.getName(), renderer.getName());
            writeEntry(jar, PluginManifest.LOCATION, """
                    id: fixture.renderer
                    version: "1"
                    requiresApi:
                      from: "%s"
                    """.formatted(from) + capabilities);
        }
        return artifact;
    }

    private static void writeEntry(JarOutputStream jar, String name, String contents) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(contents.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    public static final class FixtureRenderer implements ReportRenderer {
        @Override public String format() { return FORMAT; }

        @Override public void render(List<RunEvent> events, Writer output) throws IOException {
            if (System.getenv("FAULTORA_SECRET_RENDERER_FIXTURE") != null
                    || events.stream().map(RunEvent::toString)
                    .anyMatch(event -> event.contains(SECRET) || event.contains(RESPONSE_BODY))) {
                throw new IllegalStateException("Unexpected fixture data");
            }
            for (RunEvent event : events) {
                output.write(event.eventType() + "\n");
            }
        }
    }

    public static final class FailedRenderer implements ReportRenderer {
        @Override public String format() { return FORMAT; }

        @Override public void render(List<RunEvent> events, Writer output) throws IOException {
            output.write("synthetic partial report");
            throw new IOException("synthetic renderer detail");
        }
    }
}
