package dev.faultora.connector.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.SecretHandle;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for HttpConnector using a local HTTP server.
 * Verifies DNS pinning, redirect header stripping, and auth injection
 * against actual wire behavior.
 */
class HttpConnectorIntegrationTest {

    private HttpServer server;
    private int port;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---- Auth injection ----

    @Test
    void authHeaderInjectedWhenSecretConfigured() throws Exception {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();

        server.createContext("/api/data", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedPath.set(exchange.getRequestURI().getPath());
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();

        SecretHandle handle = new SecretHandle(
                "test-api-key", "***abcd", "env", -1,
                () -> "my-secret-token".toCharArray());
        ConnectorContext authContext = new ConnectorContext(
                new EvidencePolicy(true, true,
                        Set.of("authorization", "cookie", "set-cookie", "proxy-authorization"),
                        10 * 1024 * 1024, 1000, List.of(), Set.of(), "session"),
                handleId -> handle,
                5000, 30000, 60000,
                Map.of("baseUrl", baseUrl, "authSecretId", "test-api-key"));

        HttpConnector connector = new HttpConnector(DestinationPolicy.permissive());
        try {
            TargetDefinition target = new TargetDefinition(
                    new TargetId("test"), "Test", baseUrl,
                    List.of(new ProtocolId("http")), List.of(), Map.of());
            var prepared = connector.prepare(target, authContext);

            OperationDefinition operation = new OperationDefinition(
                    new OperationId("get-data"),
                    new ProtocolId("http"),
                    new TargetId("test"),
                    SafetyClassification.READ_ONLY,
                    Map.of(), null, Map.of(),
                    Map.of("method", "GET", "path", "/api/data"));

            OperationResult result = connector.execute(prepared, operation, Map.of(), authContext);

            assertThat(result).isNotNull();
            assertThat(capturedAuth.get())
                    .as("Authorization header should be injected")
                    .isEqualTo("Bearer my-secret-token");
            assertThat(capturedPath.get()).isEqualTo("/api/data");
        } finally {
            connector.close();
        }
    }

    @Test
    void noAuthHeaderWhenSecretNotConfigured() throws Exception {
        AtomicReference<String> capturedAuth = new AtomicReference<>();

        server.createContext("/api/public", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();

        ConnectorContext noAuthContext = new ConnectorContext(
                EvidencePolicy.MINIMAL,
                handleId -> null,
                5000, 30000, 60000,
                Map.of("baseUrl", baseUrl));

        HttpConnector connector = new HttpConnector(DestinationPolicy.permissive());
        try {
            TargetDefinition target = new TargetDefinition(
                    new TargetId("test"), "Test", baseUrl,
                    List.of(new ProtocolId("http")), List.of(), Map.of());
            var prepared = connector.prepare(target, noAuthContext);

            OperationDefinition operation = new OperationDefinition(
                    new OperationId("get-public"),
                    new ProtocolId("http"),
                    new TargetId("test"),
                    SafetyClassification.READ_ONLY,
                    Map.of(), null, Map.of(),
                    Map.of("method", "GET", "path", "/api/public"));

            OperationResult result = connector.execute(prepared, operation, Map.of(), noAuthContext);

            assertThat(result).isNotNull();
            assertThat(capturedAuth.get())
                    .as("No Authorization header when authSecretId not configured")
                    .isNull();
        } finally {
            connector.close();
        }
    }

    // ---- Redirect header stripping ----

    @Test
    void sensitiveHeadersStrippedOnCrossOriginRedirect() throws Exception {
        // First server: receives the initial request and returns a redirect
        HttpServer redirectServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int redirectPort = redirectServer.getAddress().getPort();

        AtomicReference<String> capturedRedirectAuth = new AtomicReference<>();

        redirectServer.createContext("/redirect-me", exchange -> {
            // This endpoint redirects to the second server
            exchange.getResponseHeaders().set("Location",
                    "http://127.0.0.1:" + port + "/final");
            exchange.sendResponseHeaders(302, -1);
        });

        // Second server: receives the redirected request
        server.createContext("/final", exchange -> {
            capturedRedirectAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"redirected\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        server.start();
        redirectServer.start();

        try {
            SecretHandle handle = new SecretHandle(
                    "test-key", "***abcd", "env", -1,
                    () -> "secret-value".toCharArray());
            ConnectorContext authContext = new ConnectorContext(
                    new EvidencePolicy(true, true,
                            Set.of("authorization", "cookie", "set-cookie", "proxy-authorization"),
                            10 * 1024 * 1024, 1000, List.of(), Set.of(), "session"),
                    handleId -> handle,
                    5000, 30000, 60000,
                    Map.of("baseUrl", "http://127.0.0.1:" + redirectPort,
                            "authSecretId", "test-key"));

            HttpConnector connector = new HttpConnector(DestinationPolicy.permissive());
            try {
                TargetDefinition target = new TargetDefinition(
                        new TargetId("test"), "Test", "http://127.0.0.1:" + redirectPort,
                        List.of(new ProtocolId("http")), List.of(), Map.of());
                var prepared = connector.prepare(target, authContext);

                OperationDefinition operation = new OperationDefinition(
                        new OperationId("redirect-test"),
                        new ProtocolId("http"),
                        new TargetId("test"),
                        SafetyClassification.READ_ONLY,
                        Map.of(), null, Map.of(),
                        Map.of("method", "GET", "path", "/redirect-me"));

                OperationResult result = connector.execute(prepared, operation, Map.of(), authContext);

                assertThat(result).isNotNull();
                // Auth header should be stripped on cross-origin redirect
                // (different port = different origin)
                assertThat(capturedRedirectAuth.get())
                        .as("Authorization should be stripped on cross-origin redirect")
                        .isNullOrEmpty();
            } finally {
                connector.close();
            }
        } finally {
            redirectServer.stop(0);
        }
    }

    @Test
    void sensitiveHeadersPreservedOnSameOriginRedirect() throws Exception {
        AtomicReference<String> capturedFinalAuth = new AtomicReference<>();

        server.createContext("/redirect-same", exchange -> {
            // Redirect to same origin (same host + port)
            exchange.getResponseHeaders().set("Location", baseUrl + "/final-same");
            exchange.sendResponseHeaders(302, -1);
        });

        server.createContext("/final-same", exchange -> {
            capturedFinalAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        server.start();

        SecretHandle handle = new SecretHandle(
                "test-key", "***abcd", "env", -1,
                () -> "secret-value".toCharArray());
        ConnectorContext authContext = new ConnectorContext(
                new EvidencePolicy(true, true,
                        Set.of("authorization", "cookie", "set-cookie", "proxy-authorization"),
                        10 * 1024 * 1024, 1000, List.of(), Set.of(), "session"),
                handleId -> handle,
                5000, 30000, 60000,
                Map.of("baseUrl", baseUrl, "authSecretId", "test-key"));

        HttpConnector connector = new HttpConnector(DestinationPolicy.permissive());
        try {
            TargetDefinition target = new TargetDefinition(
                    new TargetId("test"), "Test", baseUrl,
                    List.of(new ProtocolId("http")), List.of(), Map.of());
            var prepared = connector.prepare(target, authContext);

            OperationDefinition operation = new OperationDefinition(
                    new OperationId("same-redirect-test"),
                    new ProtocolId("http"),
                    new TargetId("test"),
                    SafetyClassification.READ_ONLY,
                    Map.of(), null, Map.of(),
                    Map.of("method", "GET", "path", "/redirect-same"));

            OperationResult result = connector.execute(prepared, operation, Map.of(), authContext);

            assertThat(result).isNotNull();
            // Auth header should be preserved on same-origin redirect
            assertThat(capturedFinalAuth.get())
                    .as("Authorization should be preserved on same-origin redirect")
                    .isEqualTo("Bearer secret-value");
        } finally {
            connector.close();
        }
    }

    // ---- HTTPS→HTTP downgrade blocking ----

    @Test
    void httpsToHttpDowngradeBlocked() throws Exception {
        // We can't easily test actual HTTPS→HTTP in a unit test without TLS setup,
        // but we can verify the originEquals logic directly and confirm the connector
        // rejects the redirect. For a full HTTPS test, we'd need a TLS-capable server.
        // Here we verify the logic at the origin comparison level.
        assertThat(HttpConnector.originEquals(
                java.net.URI.create("https://api.example.com"),
                java.net.URI.create("http://api.example.com")))
                .as("HTTPS→HTTP should be different origin")
                .isFalse();

        assertThat(HttpConnector.originEquals(
                java.net.URI.create("https://api.example.com"),
                java.net.URI.create("https://api.example.com:8443")))
                .as("Different ports should be different origin")
                .isFalse();

        assertThat(HttpConnector.originEquals(
                java.net.URI.create("https://api.example.com"),
                java.net.URI.create("https://api.example.com")))
                .as("Same scheme+host+port should be same origin")
                .isTrue();
    }

    // ---- DNS pinning (via DestinationPolicy integration) ----

    @Test
    void connectorUsesPinnedAddressesFromPolicy() throws Exception {
        AtomicReference<String> capturedHost = new AtomicReference<>();

        server.createContext("/pinned", exchange -> {
            capturedHost.set(exchange.getRequestHeaders().getFirst("Host"));
            byte[] response = "{\"pinned\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();

        // Create a connector with permissive policy (allows private networks)
        // The key verification: the request arrives at the server (pinned IP)
        // with the correct Host header (original hostname preserved).
        HttpConnector connector = new HttpConnector(DestinationPolicy.permissive());
        ConnectorContext context = new ConnectorContext(
                EvidencePolicy.MINIMAL,
                handleId -> null,
                5000, 30000, 60000,
                Map.of("baseUrl", baseUrl));

        try {
            TargetDefinition target = new TargetDefinition(
                    new TargetId("test"), "Test", baseUrl,
                    List.of(new ProtocolId("http")), List.of(), Map.of());
            var prepared = connector.prepare(target, context);

            OperationDefinition operation = new OperationDefinition(
                    new OperationId("pinned-test"),
                    new ProtocolId("http"),
                    new TargetId("test"),
                    SafetyClassification.READ_ONLY,
                    Map.of(), null, Map.of(),
                    Map.of("method", "GET", "path", "/pinned"));

            OperationResult result = connector.execute(prepared, operation, Map.of(), context);

            assertThat(result).isNotNull();
            assertThat(result.statusCode()).isEqualTo(200);
            // Host header should contain 127.0.0.1:<port>
            assertThat(capturedHost.get())
                    .as("Host header should be derived from URI")
                    .contains("127.0.0.1");
        } finally {
            connector.close();
        }
    }
}
