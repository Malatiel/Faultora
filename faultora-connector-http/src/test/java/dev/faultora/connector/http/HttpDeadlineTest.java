package dev.faultora.connector.http;

import com.sun.net.httpserver.HttpServer;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(12)
class HttpDeadlineTest {

    private static final long TOTAL_BUDGET_MS = 300;
    private HttpServer server;
    private HttpConnector connector;
    private ExecutorService handlers;
    private String baseUrl;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger fastRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/fast", exchange -> {
            fastRequests.incrementAndGet();
            try (exchange) {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        connector = new HttpConnector(DestinationPolicy.permissive());
        // Warm up client initialization before measuring a short transport budget.
        assertThat(execute("/fast", 2000, 5000).isSuccess()).isTrue();
    }

    @AfterEach
    void tearDown() throws Exception {
        release.countDown();
        connector.close();
        server.stop(0);
        handlers.shutdownNow();
        assertThat(handlers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void totalTimeoutCancelsWaitingForResponseHeaders() throws Exception {
        CountDownLatch seen = stalled("/headers", false);

        long start = System.nanoTime();
        OperationResult result = execute("/headers", 2000, TOTAL_BUDGET_MS);

        assertThat(seen.await(1, TimeUnit.SECONDS)).isTrue();
        assertTimeout(result, "TOTAL_TIMEOUT", start);
    }

    @Test
    void totalTimeoutCancelsAStalledFixedLengthBodyWithoutReturningPartialEvidence() throws Exception {
        CountDownLatch seen = stalled("/body", true);

        long start = System.nanoTime();
        OperationResult result = execute("/body", 2000, TOTAL_BUDGET_MS);

        assertThat(seen.await(1, TimeUnit.SECONDS)).isTrue();
        assertTimeout(result, "TOTAL_TIMEOUT", start);
        assertThat(result.body()).isNull();
        assertThat(result.headers()).isEmpty();
    }

    @Test
    void steadilyArrivingChunksCannotResetTheTotalBudget() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger written = new AtomicInteger();
        server.createContext("/drip", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                for (int index = 0; index < 200; index++) {
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    written.incrementAndGet();
                    pause(25);
                }
            } catch (IOException disconnected) {
                // Cancellation closes the transport while the server is still sending.
            } finally {
                stopped.countDown();
            }
        });

        long start = System.nanoTime();
        OperationResult result = execute("/drip", 2000, TOTAL_BUDGET_MS);

        assertTimeout(result, "TOTAL_TIMEOUT", start);
        assertThat(stopped.await(1, TimeUnit.SECONDS)).as("the peer observes disconnection").isTrue();
        assertThat(written.get()).isBetween(1, 199);
    }

    @Test
    void successDurationIncludesReadingTheBody() {
        delayedBody("/slow-success", 160);

        long start = System.nanoTime();
        OperationResult result = execute("/slow-success", 2000, 1500);

        assertThat(result.isSuccess()).isTrue();
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("ok");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(130);
        assertThat(result.durationMs()).isLessThanOrEqualTo(elapsedMs(start));
    }

    @Test
    void aShorterSocketTimeoutForHeadersRemainsARequestTimeout() throws Exception {
        CountDownLatch seen = stalled("/headers", false);

        long start = System.nanoTime();
        OperationResult result = execute("/headers", 100, 2000);

        assertThat(seen.await(1, TimeUnit.SECONDS)).isTrue();
        assertTimeout(result, "REQUEST_TIMEOUT", start);
    }

    @Test
    void aShorterSocketTimeoutForBodyReadsIsNormalizedAsATimeout() throws Exception {
        CountDownLatch seen = stalled("/body", true);

        long start = System.nanoTime();
        OperationResult result = execute("/body", 100, 2000);

        assertThat(seen.await(1, TimeUnit.SECONDS)).isTrue();
        assertTimeout(result, "REQUEST_TIMEOUT", start);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void anExhaustedBudgetDoesNotSendARequest(long budget) {
        int before = fastRequests.get();
        long start = System.nanoTime();

        OperationResult result = execute("/fast", 2000, budget);

        assertTimeout(result, "TOTAL_TIMEOUT", start);
        assertThat(fastRequests.get()).isEqualTo(before);
    }

    @Test
    void aVeryLargeBudgetDoesNotOverflowIntoAnImmediateTimeout() {
        OperationResult result = execute("/fast", 2000, Long.MAX_VALUE);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void theSameConnectorCanMakeAnotherRequestAfterATotalTimeout() {
        stalled("/body", true);
        OperationResult timedOut = execute("/body", 2000, TOTAL_BUDGET_MS);
        assertThat(timedOut.error().code()).isEqualTo("TOTAL_TIMEOUT");
        release.countDown();

        OperationResult next = execute("/fast", 2000, 1500);

        assertThat(next.isSuccess()).isTrue();
        assertThat(new String(next.body(), StandardCharsets.UTF_8)).isEqualTo("ok");
    }

    @Test
    void oneRequestsDeadlineDoesNotCancelAnOverlappingHealthyRequest() throws Exception {
        CountDownLatch seen = stalled("/body", true);
        delayedBody("/healthy", 450);
        try (ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var shortRequest = requests.submit(() -> execute("/body", 2000, TOTAL_BUDGET_MS));
            assertThat(seen.await(1, TimeUnit.SECONDS)).isTrue();

            OperationResult healthy = execute("/healthy", 2000, 1500);

            assertThat(shortRequest.get(2, TimeUnit.SECONDS).error().code()).isEqualTo("TOTAL_TIMEOUT");
            assertThat(healthy.isSuccess()).isTrue();
            assertThat(healthy.durationMs()).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void closingTheConnectorUnblocksAnActiveBodyRead() throws Exception {
        CountDownLatch seen = stalled("/body", true);
        try (ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = requests.submit(() -> execute("/body", 5000, 5000));
            assertThat(seen.await(1, TimeUnit.SECONDS)).isTrue();

            connector.close();

            assertThat(running.get(1, TimeUnit.SECONDS).isSuccess()).isFalse();
        }
    }

    @Test
    void redirectsShareOneOperationBudget() {
        server.createContext("/hops/", exchange -> {
            try (exchange) {
                int hop = Integer.parseInt(exchange.getRequestURI().getPath().substring("/hops/".length()));
                pause(130);
                if (hop < 4) {
                    exchange.getResponseHeaders().set("Location", "/hops/" + (hop + 1));
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    exchange.sendResponseHeaders(204, -1);
                }
            } catch (IOException disconnected) {
                // The shared operation budget can expire while this hop is waiting.
            }
        });

        long start = System.nanoTime();
        OperationResult result = execute("/hops/0", 2000, TOTAL_BUDGET_MS);

        assertTimeout(result, "TOTAL_TIMEOUT", start);
    }

    private CountDownLatch stalled(String path, boolean sendFirstByte) {
        CountDownLatch seen = new CountDownLatch(1);
        server.createContext(path, exchange -> {
            try (exchange) {
                if (sendFirstByte) {
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write('o');
                    exchange.getResponseBody().flush();
                }
                seen.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (!sendFirstByte) {
                    exchange.sendResponseHeaders(204, -1);
                }
            } catch (IOException disconnected) {
                // A timed-out request or connector close has already disconnected.
            }
        });
        return seen;
    }

    private void delayedBody(String path, long delayMs) {
        server.createContext(path, exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write('o');
                exchange.getResponseBody().flush();
                pause(delayMs);
                exchange.getResponseBody().write('k');
            } catch (IOException disconnected) {
                // Failure assertions in the calling test distinguish an unexpected close.
            }
        });
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private OperationResult execute(String path, long requestTimeout, long totalTimeout) {
        ConnectorContext context = new ConnectorContext(EvidencePolicy.MINIMAL, handle -> null,
                500, requestTimeout, totalTimeout, Map.of());
        TargetDefinition target = new TargetDefinition(new TargetId("test"), "Test", baseUrl,
                List.of(new ProtocolId("http")), List.of(), Map.of());
        OperationDefinition operation = new OperationDefinition(new OperationId("request"),
                new ProtocolId("http"), new TargetId("test"), SafetyClassification.READ_ONLY,
                Map.of(), null, Map.of(), Map.of("method", "GET", "path", path));
        return connector.execute(connector.prepare(target, context), operation, Map.of(), context);
    }

    private static void assertTimeout(OperationResult result, String code, long start) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.TIMEOUT);
        assertThat(result.error().code()).isEqualTo(code);
        assertThat(result.error().retryable()).isTrue();
        assertThat(elapsedMs(start)).as("the transport stops within a finite wall-clock bound").isLessThan(1200);
        assertThat(result.durationMs()).isBetween(0L, elapsedMs(start));
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
