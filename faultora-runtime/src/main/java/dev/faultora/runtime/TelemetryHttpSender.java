package dev.faultora.runtime;

import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.HttpResponse;
import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.MessageWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Keeps collector response text and exception details out of OpenTelemetry's diagnostics. */
final class TelemetryHttpSender implements HttpSender {
    static final ComponentLoader COMPONENTS = new ComponentLoader() {
        @Override
        public <T> Iterable<T> load(Class<T> service) {
            if (service == HttpSenderProvider.class) {
                return List.of(service.cast((HttpSenderProvider) TelemetryHttpSender::new));
            }
            return List.of();
        }
    };

    private final HttpSenderConfig config;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), Thread.ofPlatform()
                    .daemon(true).name("faultora-otlp-http-", 0).factory());
    private final HttpClient client;

    private TelemetryHttpSender(HttpSenderConfig config) {
        this.config = config;
        client = HttpClient.newBuilder().connectTimeout(config.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null)).executor(executor).build();
    }

    @Override
    public void send(MessageWriter writer, Consumer<HttpResponse> onResponse, Consumer<Throwable> onError) {
        try {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            writer.writeMessage(data);
            HttpRequest request = HttpRequest.newBuilder(config.getEndpoint())
                    .timeout(config.getTimeout())
                    .header("Content-Type", config.getContentType())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(data.toByteArray())).build();
            var inFlight = client.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            // Close a response stream even if its headers arrive concurrently with the deadline.
            var status = new CompletableFuture<ResponseStatus>();
            inFlight.whenComplete((response, failure) -> {
                if (failure != null) {
                    status.completeExceptionally(failure);
                    return;
                }
                try (InputStream body = response.body()) {
                    // An OTLP export needs only the status here. Closing cancels body reads.
                } catch (IOException unavailable) {
                    status.completeExceptionally(unavailable);
                    return;
                }
                status.complete(new ResponseStatus(response.statusCode()));
            });
            status.orTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((response, failure) -> {
                        if (failure != null) {
                            inFlight.cancel(true);
                            failed(onError);
                        } else {
                            onResponse.accept(response);
                        }
                    });
        } catch (IOException | RuntimeException unavailable) {
            failed(onError);
        }
    }

    private static void failed(Consumer<Throwable> onError) {
        onError.accept(new IOException("Faultora telemetry transport failed"));
    }

    @Override
    public CompletableResultCode shutdown() {
        client.shutdownNow();
        executor.shutdownNow();
        return CompletableResultCode.ofSuccess();
    }

    private record ResponseStatus(int code) implements HttpResponse {
        @Override
        public int getStatusCode() {
            return code;
        }

        @Override
        public String getStatusMessage() {
            return "";
        }

        @Override
        public byte[] getResponseBody() {
            return new byte[0];
        }
    }
}
