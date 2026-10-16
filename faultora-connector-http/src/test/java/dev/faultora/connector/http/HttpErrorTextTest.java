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
import dev.faultora.model.security.SecretHandle;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An HTTP error says what kind of failure it was, never what the transport,
 * a secret resolver or the destination policy wrote about it. Those texts can
 * carry URLs, query strings, addresses and handle names; the export policy
 * strips them on the way out, and a caller of the connector itself should not
 * receive them either.
 */
class HttpErrorTextTest {

    private static final String MARKER = "synthetic-private-marker";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aResolverFailureIsReportedWithoutItsMessage() {
        OperationResult result = execute(new HttpConnector(DestinationPolicy.permissive()),
                "http://127.0.0.1:1", "GET", "/",
                handle -> {
                    throw new IllegalStateException("vault path /secret/" + MARKER + " denied");
                },
                "handle-" + MARKER);

        assertWithheld(result, "SECRET_UNAVAILABLE", NormalizedError.ErrorCategory.VALIDATION);
        assertThat(result.error().message()).contains("Secret resolver failed");
    }

    @Test
    void aMissingResolverDoesNotNameTheHandle() {
        OperationResult result = execute(new HttpConnector(DestinationPolicy.permissive()),
                "http://127.0.0.1:1", "GET", "/", null, "handle-" + MARKER);

        assertWithheld(result, "SECRET_UNAVAILABLE", NormalizedError.ErrorCategory.VALIDATION);
        assertThat(result.error().message()).contains("No secret resolver configured");
    }

    @Test
    void anUnavailableHandleDoesNotNameTheHandle() {
        OperationResult result = execute(new HttpConnector(DestinationPolicy.permissive()),
                "http://127.0.0.1:1", "GET", "/", handle -> null, "handle-" + MARKER);

        assertWithheld(result, "SECRET_UNAVAILABLE", NormalizedError.ErrorCategory.VALIDATION);
        assertThat(result.error().message()).contains("returned null");
    }

    @Test
    void aRefusedConnectionDoesNotRepeatTheAddress() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        OperationResult result = execute(new HttpConnector(DestinationPolicy.permissive()),
                "http://127.0.0.1:" + closedPort, "GET", "/" + MARKER, null, null);

        assertWithheld(result, "CONNECTION_REFUSED", NormalizedError.ErrorCategory.NETWORK);
        assertThat(result.error().message())
                .doesNotContain(String.valueOf(closedPort))
                .doesNotContain("127.0.0.1");
    }

    @Test
    void aTimedOutReadDoesNotRepeatTheTransportText() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        OperationResult result = execute(new HttpConnector(DestinationPolicy.permissive()),
                baseUrl, "GET", "/" + MARKER, null, null, 200, 5_000);

        assertWithheld(result, "REQUEST_TIMEOUT", NormalizedError.ErrorCategory.TIMEOUT);
        assertThat(result.error().message()).doesNotContain(baseUrl);
    }

    @Test
    void aRedirectToABlockedDestinationDoesNotRepeatTheLocation() {
        RedirectingClient client = new RedirectingClient("http://" + MARKER + ".invalid/final");
        DestinationPolicy policy = new DestinationPolicy(true, Set.of(), Set.of(MARKER + ".invalid"));

        OperationResult result = executeThrough(client, policy, "http://127.0.0.1:18080");

        assertWithheld(result, "DESTINATION_BLOCKED", NormalizedError.ErrorCategory.POLICY_VIOLATION);
        assertThat(result.error().metadata()).isEmpty();
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void aRedirectToAnUnsupportedSchemeDoesNotRepeatTheScheme() {
        RedirectingClient client = new RedirectingClient(MARKER + "://example.invalid/final");

        OperationResult result = executeThrough(client, DestinationPolicy.permissive(), "http://127.0.0.1:18080");

        assertWithheld(result, "DESTINATION_BLOCKED", NormalizedError.ErrorCategory.POLICY_VIOLATION);
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void aRefusedDowngradeDoesNotRepeatEitherAddress() {
        RedirectingClient client = new RedirectingClient("http://127.0.0.1:18080/" + MARKER);

        OperationResult result = executeThrough(client, DestinationPolicy.permissive(), "https://127.0.0.1:18080");

        assertWithheld(result, "INSECURE_REDIRECT", NormalizedError.ErrorCategory.POLICY_VIOLATION);
        assertThat(result.error().message()).doesNotContain("127.0.0.1");
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void anUnexpectedFailureDoesNotRepeatItsCause() {
        OperationResult result = execute(new HttpConnector(DestinationPolicy.permissive()),
                "http://127.0.0.1:1", "BREW-" + MARKER, "/", null, null);

        assertWithheld(result, "EXECUTION_ERROR", NormalizedError.ErrorCategory.INTERNAL);
    }

    private static void assertWithheld(
            OperationResult result, String code, NormalizedError.ErrorCategory category) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().code()).isEqualTo(code);
        assertThat(result.error().category()).isEqualTo(category);
        assertThat(result.error().message()).doesNotContainIgnoringCase(MARKER);
    }

    private static OperationResult executeThrough(
            RedirectingClient client, DestinationPolicy policy, String baseUrl) {
        return execute(new HttpConnector(policy, client), baseUrl, "GET", "/start", null, null);
    }

    /** Answers the first exchange with a redirect; a second exchange would be a leak. */
    private static final class RedirectingClient extends CloseableHttpClient {
        private final String location;
        int calls;

        RedirectingClient(String location) {
            this.location = location;
        }

        @Override
        protected CloseableHttpResponse doExecute(HttpHost target, ClassicHttpRequest request,
                                                  HttpContext context) {
            calls++;
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(calls == 1 ? 302 : 204);
            if (calls == 1) {
                response.setHeader("Location", location);
            }
            return CloseableHttpResponse.adapt(response);
        }

        @Override
        public void close() {
        }

        @Override
        public void close(CloseMode mode) {
        }
    }

    private static OperationResult execute(
            HttpConnector connector, String baseUrl, String method, String path,
            Function<String, SecretHandle> resolver, String secretId) {
        return execute(connector, baseUrl, method, path, resolver, secretId, 1_000, 2_000);
    }

    private static OperationResult execute(
            HttpConnector connector, String baseUrl, String method, String path,
            Function<String, SecretHandle> resolver, String secretId,
            long requestTimeoutMs, long totalTimeoutMs) {
        Map<String, Object> config = secretId == null
                ? Map.of("baseUrl", baseUrl)
                : Map.of("baseUrl", baseUrl, HttpConnector.AUTH_SECRET_ID, secretId);
        ConnectorContext context = new ConnectorContext(
                EvidencePolicy.MINIMAL, resolver, 1_000, requestTimeoutMs, totalTimeoutMs, config);
        TargetDefinition target = new TargetDefinition(
                new TargetId("errors"), "Errors", baseUrl,
                List.of(new ProtocolId("http")), List.of(), Map.of());
        OperationDefinition operation = new OperationDefinition(
                new OperationId("errors-op"), new ProtocolId("http"), new TargetId("errors"),
                SafetyClassification.READ_ONLY, Map.of(), null, Map.of(),
                Map.of("method", method, "path", path));
        try (connector) {
            return connector.execute(connector.prepare(target, context), operation, Map.of(), context);
        }
    }
}
