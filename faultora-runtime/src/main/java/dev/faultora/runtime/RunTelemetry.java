package dev.faultora.runtime;

import dev.faultora.engine.run.RunResult;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional, operator-configured telemetry for the shared execution boundary. */
final class RunTelemetry {
    static final String ENDPOINT = "FAULTORA_OTLP_ENDPOINT";

    private static final System.Logger LOG = System.getLogger(RunTelemetry.class.getName());
    private static final Duration EXPORT_TIMEOUT = Duration.ofMillis(500);
    private static final long SHUTDOWN_TIMEOUT_MS = 1200;
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("faultora.outcome");
    private static final AtomicBoolean REPORTED_RECORDING_FAILURE = new AtomicBoolean();

    private final Tracer tracer;
    private final LongCounter runs;
    private final DoubleHistogram durations;

    private RunTelemetry(URI base) {
        Resource resource = Resource.create(Attributes.builder()
                .put("service.name", "faultora")
                .put("service.instance.id", UUID.randomUUID().toString()).build());
        var spanExporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(base.resolve("/v1/traces").toString())
                .setTimeout(EXPORT_TIMEOUT).setConnectTimeout(EXPORT_TIMEOUT)
                .setRetryPolicy(null).setMeterProvider(MeterProvider.noop())
                .setComponentLoader(TelemetryHttpSender.COMPONENTS)
                .setMaxRequestBodySize(64 * 1024).build();
        var metricExporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(base.resolve("/v1/metrics").toString())
                .setTimeout(EXPORT_TIMEOUT).setConnectTimeout(EXPORT_TIMEOUT)
                .setRetryPolicy(null).setMeterProvider(MeterProvider.noop())
                .setComponentLoader(TelemetryHttpSender.COMPONENTS)
                .setMaxRequestBodySize(64 * 1024).build();
        var traces = SdkTracerProvider.builder().setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                        .setMaxQueueSize(128).setMaxExportBatchSize(32)
                        .setScheduleDelay(Duration.ofMillis(250)).setExporterTimeout(EXPORT_TIMEOUT).build()).build();
        var metrics = SdkMeterProvider.builder().setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                        .setInterval(Duration.ofSeconds(10)).setExporterTimeout(EXPORT_TIMEOUT).build()).build();
        tracer = traces.get("dev.faultora");
        var meter = metrics.get("dev.faultora");
        runs = meter.counterBuilder("faultora.run.count").setUnit("{run}").build();
        durations = meter.histogramBuilder("faultora.run.duration").setUnit("s").build();
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("faultora-telemetry-flush")
                .unstarted(() -> flush(traces, metrics)));
    }

    private static void flush(SdkTracerProvider traces, SdkMeterProvider metrics) {
        try {
            // Send both signals concurrently and bound the total time spent by the process.
            var pending = CompletableResultCode.ofAll(List.of(traces.forceFlush(), metrics.forceFlush()));
            pending.join(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!pending.isSuccess()) {
                LOG.log(System.Logger.Level.WARNING, "Faultora telemetry flush incomplete");
            }
        } catch (RuntimeException unavailable) {
            LOG.log(System.Logger.Level.WARNING, "Faultora telemetry flush incomplete");
        }
    }

    private static final class Configuration {
        private static final RunTelemetry TELEMETRY = configure();

        private static RunTelemetry configure() {
            try {
                String setting = System.getenv(ENDPOINT);
                return setting == null || setting.isBlank() ? null : new RunTelemetry(collector(setting));
            } catch (RuntimeException | LinkageError | ServiceConfigurationError unavailable) {
                LOG.log(System.Logger.Level.WARNING, "Faultora telemetry disabled because setup failed");
                return null;
            }
        }
    }

    static URI collector(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || port == 0 || port > 65535
                || !uri.getRawAuthority().equals(port < 0 ? host : host + ":" + port)
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("Expected an OTLP collector origin");
        }
        boolean loopback = host.equals("127.0.0.1") || host.equals("[::1]");
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback)) {
            throw new IllegalArgumentException("Expected HTTPS or a loopback HTTP collector");
        }
        return uri;
    }

    static Observation observe() {
        RunTelemetry telemetry = Configuration.TELEMETRY;
        if (telemetry != null) {
            try {
                return new Observation(telemetry, telemetry.tracer.spanBuilder("faultora.run")
                        .setNoParent().startSpan(), System.nanoTime());
            } catch (RuntimeException unavailable) {
                reportRecordingFailure();
            }
        }
        return Observation.DISABLED;
    }

    private static void reportRecordingFailure() {
        if (REPORTED_RECORDING_FAILURE.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING, "Faultora telemetry recording incomplete");
        }
    }

    static final class Observation implements AutoCloseable {
        private static final Observation DISABLED = new Observation(null, null, 0);

        private final RunTelemetry telemetry;
        private final Span span;
        private final long started;
        private RunResult.Status status = RunResult.Status.ERROR;

        private Observation(RunTelemetry telemetry, Span span, long started) {
            this.telemetry = telemetry;
            this.span = span;
            this.started = started;
        }

        void completed(RunResult.Status outcome) {
            if (telemetry != null) {
                status = outcome;
            }
        }

        @Override
        public void close() {
            if (telemetry == null) {
                return;
            }
            try {
                String outcome = status.name().toLowerCase(Locale.ROOT);
                Attributes attributes = Attributes.of(OUTCOME, outcome);
                span.setAttribute(OUTCOME, outcome);
                if (status == RunResult.Status.ERROR) {
                    span.setStatus(StatusCode.ERROR);
                }
                span.end();
                telemetry.runs.add(1, attributes, Context.root());
                telemetry.durations.record((System.nanoTime() - started) / 1_000_000_000.0,
                        attributes, Context.root());
            } catch (RuntimeException unavailable) {
                reportRecordingFailure();
            }
        }
    }
}
