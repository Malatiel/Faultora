package dev.faultora.connector.http;

import com.sun.net.httpserver.HttpServer;
import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class HttpRequestBodyTest {
    private HttpServer server;
    private ExecutorService handlers;
    private HttpConnector connector;
    private String baseUrl;
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> lengths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/payload", exchange -> {
            try (exchange) {
                bodies.add(exchange.getRequestBody().readAllBytes());
                methods.add(exchange.getRequestMethod());
                lengths.add(exchange.getRequestHeaders().getFirst("Content-length"));
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        connector = new HttpConnector(DestinationPolicy.permissive());
        assertThat(execute("GET", "/payload", Map.of(), Map.of()).isSuccess()).isTrue();
        bodies.clear(); methods.clear(); lengths.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        connector.close();
        server.stop(0);
        handlers.shutdownNow();
        assertThat(handlers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH"})
    void oversizedBodiesNeverReachThePeer(String method) {
        OperationResult result = execute(method, "/payload", Map.of("body", "x".repeat(17)), limit(16));
        assertTooLarge(result);
        assertThat(bodies).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH"})
    void exactlyTheLimitIsSentAsTheCheckedBytes(String method) {
        OperationResult result = execute(method, "/payload", Map.of("body", "é😺"), limit(6));
        assertThat(result.isSuccess()).isTrue();
        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst()).containsExactly("é😺".getBytes(StandardCharsets.UTF_8));
        assertThat(lengths).containsExactly("6");
        assertThat(methods).containsExactly(method);
    }

    @Test
    void utf8BytesRatherThanUtf16LengthDetermineTheLimit() {
        assertTooLarge(execute("POST", "/payload", Map.of("body", "é😺"), limit(5)));
        assertThat(bodies).isEmpty();
    }

    @Test
    void aStructuredJsonOverflowIsNormalizedWithoutLeakingTheValueOrSendingAPrefix() {
        assertTooLarge(execute("POST", "/payload", Map.of("body", Map.of("text", "synthetic-sensitive-value")), limit(8)));
        assertThat(bodies).isEmpty();
    }

    @Test
    void structuredJsonAtTheLimitPreservesItsRepresentation() {
        byte[] expected = "{\"text\":\"é😺\"}".getBytes(StandardCharsets.UTF_8);
        OperationResult result = execute("POST", "/payload", Map.of("body", Map.of("text", "é😺")), limit(expected.length));
        assertThat(result.isSuccess()).isTrue();
        assertThat(bodies.getFirst()).containsExactly(expected);
    }

    @Test
    void aFailedPayloadDoesNotPoisonTheConnectorForTheNextRequest() {
        assertTooLarge(execute("POST", "/payload", Map.of("body", "oversized"), limit(1)));
        assertThat(execute("POST", "/payload", Map.of("body", "ok"), limit(2)).isSuccess()).isTrue();
        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst()).containsExactly("ok".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void absentAndEmptyPostBodiesStayValid() {
        assertThat(execute("POST", "/payload", Map.of(), limit(1)).isSuccess()).isTrue();
        assertThat(execute("POST", "/payload", Map.of("body", ""), limit(1)).isSuccess()).isTrue();
        assertThat(bodies).hasSize(2).allSatisfy(body -> assertThat(body).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "DELETE", "OPTIONS"})
    void methodsWithoutAnEntityDoNotSerializeAnUnusedBody(String method) {
        var unused = new AbstractList<Object>() {
            public int size() {
                throw new AssertionError("Unused body must not be serialized");
            }
            public Object get(int index) {
                throw new AssertionError("Unused body must not be serialized");
            }
        };
        assertThat(execute(method, "/payload", Map.of("body", unused), limit(1)).isSuccess()).isTrue();
        assertThat(bodies).hasSize(1).allSatisfy(body -> assertThat(body).isEmpty());
    }

    @Test
    void anInvalidLimitStopsBeforeTransportAndSecretResolution() {
        AtomicInteger secrets = new AtomicInteger();
        ConnectorContext context = new ConnectorContext(EvidencePolicy.MINIMAL,
                key -> {
                    secrets.incrementAndGet();
                    return null;
                }, 1000, 1000, 5000,
                Map.of(HttpConnector.MAX_REQUEST_BYTES, 0, HttpConnector.AUTH_SECRET_ID, "synthetic-handle"));
        TargetDefinition target = target();
        OperationResult result = connector.execute(connector.prepare(target, context), operation("POST", "/payload"),
                Map.of("body", "ok"), context);
        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.VALIDATION);
        assertThat(result.error().code()).isEqualTo("INVALID_PAYLOAD_LIMIT");
        assertThat(result.error().retryable()).isFalse();
        assertThat(secrets.get()).isZero();
        assertThat(bodies).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {307, 308})
    void redirectsReuseTheValidatedBodyEvenIfTheOriginalInputChanges(int status) {
        Map<String, Object> mutableBody = new LinkedHashMap<>(Map.of("x", "ok"));
        server.createContext("/redirect", exchange -> {
            try (exchange) {
                bodies.add(exchange.getRequestBody().readAllBytes());
                mutableBody.put("x", "x".repeat(1000));
                exchange.getResponseHeaders().set("Location", "/payload");
                exchange.sendResponseHeaders(status, -1);
            }
        });
        OperationResult result = execute("POST", "/redirect", Map.of("body", mutableBody), limit(10));
        assertThat(result.isSuccess()).isTrue();
        assertThat(bodies).hasSize(2).allSatisfy(body ->
                assertThat(body).containsExactly("{\"x\":\"ok\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303})
    void redirectsThatChangePostToGetDropTheBody(int status) {
        server.createContext("/redirect", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Location", "/payload");
                exchange.sendResponseHeaders(status, -1);
            }
        });
        assertThat(execute("POST", "/redirect", Map.of("body", "ok"), limit(2)).isSuccess()).isTrue();
        assertThat(methods).containsExactly("GET");
        assertThat(bodies.getFirst()).isEmpty();
    }

    @Test
    void responseAndRequestBodyLimitsRemainIndependentForEmbeddingCallers() {
        assertThat(execute("POST", "/payload", Map.of("body", "123456"),
                Map.of(HttpConnector.MAX_REQUEST_BYTES, 6, "maxResponseBytes", 1)).isSuccess()).isTrue();
        assertThat(bodies.getFirst()).hasSize(6);
    }

    private OperationResult execute(String method, String path, Map<String, Object> inputs, Map<String, Object> config) {
        ConnectorContext context = new ConnectorContext(EvidencePolicy.MINIMAL, key -> null,
                1000, 1000, 5000, config);
        var prepared = connector.prepare(target(), context);
        try {
            return connector.execute(prepared, operation(method, path), inputs, context);
        } finally {
            connector.release(prepared);
        }
    }

    private TargetDefinition target() {
        return new TargetDefinition(new TargetId("test"), "Test", baseUrl,
                List.of(new ProtocolId("http")), List.of(), Map.of());
    }

    private static OperationDefinition operation(String method, String path) {
        return new OperationDefinition(new OperationId("request"), new ProtocolId("http"), new TargetId("test"),
                SafetyClassification.MUTATING, Map.of(), null, Map.of(), Map.of("method", method, "path", path));
    }

    private static Map<String, Object> limit(int bytes) {
        return Map.of(HttpConnector.MAX_REQUEST_BYTES, bytes);
    }

    @ParameterizedTest
    @ValueSource(ints = {307, 308})
    void aCrossOriginRedirectCannotDeliverANonemptyBodyToTheForeignPeer(int status) throws Exception {
        var reached = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer foreign = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        foreign.createContext("/accept", exchange -> {
            try (exchange) {
                reached.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.createContext("/foreign", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + foreign.getAddress().getPort() + "/accept");
                exchange.sendResponseHeaders(status, -1);
            }
        });
        foreign.start();
        try {
            var result = execute("POST", "/foreign", Map.of("body", "synthetic-body"), limit(32));
            assertThat(result.error().code()).isEqualTo("CROSS_ORIGIN_BODY_REDIRECT");
            assertThat(reached.get()).isZero();
        } finally {
            foreign.stop(0);
        }
    }

    private static void assertTooLarge(OperationResult result) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.POLICY_VIOLATION);
        assertThat(result.error().code()).isEqualTo("REQUEST_TOO_LARGE");
        assertThat(result.error().retryable()).isFalse();
        assertThat(result.error().message()).doesNotContain("synthetic-sensitive-value");
        assertThat(result.body()).isNull();
        assertThat(result.headers()).isEmpty();
    }
}
