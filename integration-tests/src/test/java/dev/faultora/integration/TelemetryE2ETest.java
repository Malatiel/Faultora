package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.runner.TlsMaterial;
import dev.faultora.testkit.Certificates;
import dev.faultora.testkit.QualificationDispatcher;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryE2ETest {
    private static final String MARKER = "synthetic-private-scenario-value";
    private static final String COLLECTOR_MARKER = "synthetic-private-collector-response";
    private static final String OUTCOME = "faultora.outcome";

    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata:
              name: %s
            execute:
              - id: %s
                type: wait
                timeout: 10ms
            """.formatted(MARKER, MARKER);

    @TempDir Path directory;

    @Test
    void packagedExecutionExportsOnlyRunLevelTelemetry() throws Exception {
        try (Collector collector = new Collector(200)) {
            CliResult result = run("accepted", collector.endpoint(), false);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.output().resolve("events.ndjson")).isRegularFile();
            assertThat(new ObjectMapper().readTree(result.stdout()).path("exitCode").intValue()).isZero();

            assertThat(collector.requests).hasSize(2).allSatisfy(request -> {
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.contentType()).isEqualTo("application/x-protobuf");
                assertThat(request.authorization()).isNull();
                assertThat(new String(request.body(), StandardCharsets.ISO_8859_1)).doesNotContain(MARKER);
            });
            var traces = ExportTraceServiceRequest.parseFrom(collector.body("/v1/traces"));
            var metricRequest = ExportMetricsServiceRequest.parseFrom(collector.body("/v1/metrics"));
            var spanResource = traces.getResourceSpans(0);
            assertResource(spanResource.getResource().getAttributesList());
            assertThat(spanResource.getScopeSpans(0).getSpansList()).singleElement().satisfies(span -> {
                assertThat(span.getName()).isEqualTo("faultora.run");
                assertThat(span.getTraceId().size()).isEqualTo(16);
                assertThat(span.getSpanId().size()).isEqualTo(8);
                assertThat(span.getParentSpanId().isEmpty()).isTrue();
                assertThat(span.getEndTimeUnixNano()).isGreaterThanOrEqualTo(span.getStartTimeUnixNano());
                assertThat(span.getEventsCount()).isZero();
                assertOutcome(span.getAttributesList());
            });

            var metricResource = metricRequest.getResourceMetrics(0);
            assertThat(metricResource.getResource()).isEqualTo(spanResource.getResource());
            var metrics = metricResource.getScopeMetrics(0).getMetricsList();
            assertThat(metrics).extracting(Metric::getName)
                    .containsExactlyInAnyOrder("faultora.run.count", "faultora.run.duration");
            var count = metric(metrics, "faultora.run.count");
            assertThat(count.getSum().getIsMonotonic()).isTrue();
            assertThat(count.getSum().getDataPointsList()).singleElement().satisfies(point -> {
                assertThat(point.getAsInt()).isEqualTo(1);
                assertOutcome(point.getAttributesList());
            });
            var histogram = metric(metrics, "faultora.run.duration");
            assertThat(histogram.getUnit()).isEqualTo("s");
            assertThat(histogram.getHistogram().getDataPointsList()).singleElement().satisfies(point -> {
                assertThat(point.getCount()).isEqualTo(1);
                assertThat(point.getSum()).isPositive();
                assertOutcome(point.getAttributesList());
            });
        }
    }

    @Test
    void packagedRunnerExportsAcceptedDispatchesWithoutDispatcherIdentifiers() throws Exception {
        var runner = Certificates.issue(directory, "runner", 1);
        var control = Certificates.issue(directory, "control", 1);
        var signing = Certificates.issue(directory, "signing", 1);
        Path trustedControl = Certificates.trusting(directory, "runner", control);
        var tls = new TlsMaterial(control.keystore(), Certificates.trusting(directory, "control", runner),
                () -> Certificates.PASSWORD.toCharArray());
        String dispatchId = "synthetic-private-dispatch-id";
        try (var dispatcher = new QualificationDispatcher(tls.sslContext()); var collector = new Collector(200)) {
            dispatcher.offer(IsolatedAssertionE2ETest.dispatch(dispatchId, signing, SCENARIO));
            List<String> args = List.of("runner", "--dispatcher", dispatcher.address(),
                    "--keystore", runner.keystore().toString(), "--truststore", trustedControl.toString(),
                    "--tls-secret-id", "runner-tls", "--policy-key", "qualification=" + signing.certificate(),
                    "--work-dir", directory.resolve("runner-work").toString(), "--allow-private", "--once");
            CliResult result = launch("runner", collector.endpoint(), args);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(dispatcher.outcomeOf(dispatchId)).contains("PASSED");
            assertThat(collector.requests).hasSize(2).allSatisfy(request ->
                    assertThat(new String(request.body(), StandardCharsets.ISO_8859_1))
                            .doesNotContain(dispatchId, MARKER, Certificates.PASSWORD));
            var trace = ExportTraceServiceRequest.parseFrom(collector.body("/v1/traces"));
            assertThat(trace.getResourceSpans(0).getScopeSpans(0).getSpansList())
                    .singleElement().satisfies(span -> assertOutcome(span.getAttributesList()));
        }
    }

    @Test
    void previewDoesNotConnectToTheConfiguredCollector() throws Exception {
        try (Collector collector = new Collector(200)) {
            CliResult result = run("preview", collector.endpoint(), true);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.output()).doesNotExist();
            assertThat(collector.requests).isEmpty();
        }
    }

    @Test
    void collectorResponseDoesNotLeakIntoOutputOrChangeTheExitCode() throws Exception {
        try (Collector collector = new Collector(403)) {
            CliResult result = run("rejected", collector.endpoint(), false);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(collector.requests).hasSize(2);
            assertThat(result.stderr()).doesNotContain(COLLECTOR_MARKER);
            assertThat(result.stdout()).doesNotContain(COLLECTOR_MARKER);
            assertThat(result.output().resolve("events.ndjson")).isRegularFile();
        }
    }

    @Test
    void stalledCollectorDoesNotBlockProcessExit() throws Exception {
        try (Collector collector = new Collector(0)) {
            CliResult result = run("stalled", collector.endpoint(), false);
            assertThat(collector.requests).isNotEmpty();
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.output().resolve("events.ndjson")).isRegularFile();
        }
    }

    private CliResult run(String name, String endpoint, boolean preview) throws Exception {
        Path scenario = Files.writeString(directory.resolve(name + ".yaml"), SCENARIO);
        Path output = directory.resolve(name + "-result");
        var arguments = new ArrayList<>(List.of("--diagnostics=json", "test", "--scenario",
                scenario.toString(), "--output", output.toString(), "--format", "json"));
        if (preview) {
            arguments.add("--preview");
        }
        return launch(name, endpoint, arguments);
    }

    private CliResult launch(String name, String endpoint, List<String> arguments) throws Exception {
        Path stdout = directory.resolve(name + ".stdout");
        Path stderr = directory.resolve(name + ".stderr");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", System.getProperty("faultora.cliJar")));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().put("FAULTORA_OTLP_ENDPOINT", endpoint);
        builder.environment().put("FAULTORA_SECRET_RUNNER_TLS", Certificates.PASSWORD);
        int exit = BoundedProcess.run(builder, Duration.ofSeconds(15), process -> { });
        return new CliResult(exit, Files.readString(stdout), Files.readString(stderr), directory.resolve(name + "-result"));
    }

    private static Metric metric(List<Metric> metrics, String name) {
        return metrics.stream().filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
    }

    private static void assertResource(List<KeyValue> attributes) {
        assertThat(attributes).extracting(KeyValue::getKey)
                .containsExactlyInAnyOrder("service.name", "service.instance.id");
        assertThat(attributes).anySatisfy(attribute -> {
            assertThat(attribute.getKey()).isEqualTo("service.name");
            assertThat(attribute.getValue().getStringValue()).isEqualTo("faultora");
        });
    }

    private static void assertOutcome(List<KeyValue> attributes) {
        assertThat(attributes).singleElement().satisfies(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(OUTCOME);
            assertThat(attribute.getValue().getStringValue()).isEqualTo("passed");
        });
    }

    private record CliResult(int exitCode, String stdout, String stderr, Path output) { }

    private record Request(String path, String method, String contentType, String authorization, byte[] body) { }

    private static final class Collector implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
        private final CountDownLatch release = new CountDownLatch(1);
        private final java.util.concurrent.ExecutorService workers =
                Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).factory());

        private Collector(int status) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try (exchange) {
                    var headers = exchange.getRequestHeaders();
                    requests.add(new Request(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                            headers.getFirst("Content-Type"), headers.getFirst("Authorization"),
                            exchange.getRequestBody().readNBytes(64 * 1024 + 1)));
                    if (status == 0) {
                        try {
                            release.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                        }
                    } else if (status == 200) {
                        exchange.sendResponseHeaders(200, -1);
                    } else {
                        byte[] body = COLLECTOR_MARKER.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, body.length);
                        exchange.getResponseBody().write(body);
                    }
                }
            });
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private byte[] body(String path) {
            return requests.stream().filter(request -> request.path().equals(path)).findFirst().orElseThrow().body();
        }

        @Override
        public void close() {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
