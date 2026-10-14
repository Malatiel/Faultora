package dev.faultora.connector.http;

import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.ConnectorContext;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectBoundaryMatrixTest {
    static Stream<Arguments> redirects() {
        return Stream.of(301, 302, 303, 307, 308).flatMap(status ->
                Stream.of("http://127.0.0.1:18080/final", "http://localhost:18080/final",
                        "http://127.0.0.1:18081/final", "https://127.0.0.1:18080/final")
                        .map(destination -> Arguments.of(status, destination)));
    }

    @ParameterizedTest
    @MethodSource("redirects")
    void postRedirectsKeepBodyAndCredentialsWithinTheirOriginalOrigin(int status, String destination) throws Exception {
        CapturingClient client = new CapturingClient(status, destination);
        var result = execute(client, "http://127.0.0.1:18080", "POST", "synthetic-body");
        boolean sameOrigin = destination.equals("http://127.0.0.1:18080/final");
        boolean keepsBody = status == 307 || status == 308;
        if (!sameOrigin && keepsBody) {
            assertThat(result.error().code()).isEqualTo("CROSS_ORIGIN_BODY_REDIRECT");
            assertThat(result.error().retryable()).isFalse();
            assertThat(client.calls).hasSize(1);
            return;
        }
        assertThat(result.isSuccess()).isTrue();
        assertThat(client.calls).hasSize(2);
        SeenRequest following = client.calls.getLast();
        assertThat(following.method()).isEqualTo(keepsBody ? "POST" : "GET");
        assertThat(following.body()).isEqualTo(keepsBody ? "synthetic-body" : "");
        assertThat(following.privateHeader()).isEqualTo(sameOrigin ? "synthetic-secret" : null);
        assertThat(following.authorization()).isEqualTo(sameOrigin ? "synthetic-auth" : null);
        assertThat(following.trace()).isEqualTo("synthetic-trace");
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void anHttpsDowngradeIsRefusedBeforeTheForeignExchange(int status) throws Exception {
        CapturingClient client = new CapturingClient(status, "http://127.0.0.1:18080/final");
        var result = execute(client, "https://127.0.0.1:18080", "GET", null);
        assertThat(result.error().code()).isEqualTo("INSECURE_REDIRECT");
        assertThat(client.calls).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {307, 308})
    void anEmptyBodyCanFollowACrossOriginRedirectWithoutCredentials(int status) throws Exception {
        CapturingClient client = new CapturingClient(status, "http://127.0.0.1:18081/final");
        assertThat(execute(client, "http://127.0.0.1:18080", "POST", "").isSuccess()).isTrue();
        assertThat(client.calls.getLast().body()).isEmpty();
        assertThat(client.calls.getLast().privateHeader()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"PUT,301", "PUT,302", "PATCH,301", "PATCH,302"})
    void methodsThatKeepTheirBodiesOn301And302CannotForwardThemToAnotherOrigin(String method, int status) {
        CapturingClient client = new CapturingClient(status, "http://127.0.0.1:18081/final");
        assertThat(execute(client, "http://127.0.0.1:18080", method, "synthetic-body").error().code())
                .isEqualTo("CROSS_ORIGIN_BODY_REDIRECT");
        assertThat(client.calls).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"HTTP://EXAMPLE.invalid,http://example.invalid:80,true",
            "HTTPS://EXAMPLE.invalid,https://example.invalid:443,true",
            "http://example.invalid,http://other.invalid,false",
            "http://example.invalid:80,http://example.invalid:81,false",
            "http://example.invalid,https://example.invalid,false"})
    void originComparisonUsesCaseInsensitiveSchemeAndHostAndEffectivePorts(String first, String second, boolean equal) {
        assertThat(HttpConnector.originEquals(URI.create(first), URI.create(second))).isEqualTo(equal);
    }

    private static dev.faultora.spi.result.OperationResult execute(CapturingClient client, String base,
                                                                  String method, String body) {
        EvidencePolicy policy = new EvidencePolicy(false, false, Set.of("X-Service-Secret"), 0, 0,
                List.of(), Set.of(), "session");
        ConnectorContext context = new ConnectorContext(policy, key -> null, 1000, 1000, 5000, Map.of());
        TargetDefinition target = new TargetDefinition(new TargetId("test"), "Test", base,
                List.of(new ProtocolId("http")), List.of(), Map.of());
        OperationDefinition operation = new OperationDefinition(new OperationId("redirect"), new ProtocolId("http"),
                target.id(), SafetyClassification.READ_ONLY, Map.of(), null, Map.of(), Map.of("method", method, "path", "/start"));
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        if (body != null) { input.put("body", body); }
        input.put("headers", Map.of("Authorization", "synthetic-auth", "X-Service-Secret", "synthetic-secret", "X-Trace", "synthetic-trace"));
        try (HttpConnector connector = new HttpConnector(DestinationPolicy.permissive(), client)) {
            var prepared = connector.prepare(target, context);
            try { return connector.execute(prepared, operation, input, context); }
            finally { connector.release(prepared); }
        }
    }

    private record SeenRequest(String method, String body, String privateHeader, String authorization, String trace) { }

    private static class CapturingClient extends CloseableHttpClient {
        final int status;
        final String location;
        final List<SeenRequest> calls = new ArrayList<>();
        CapturingClient(int status, String location) { this.status = status; this.location = location; }
        protected CloseableHttpResponse doExecute(HttpHost target, ClassicHttpRequest request, HttpContext context) throws IOException {
            byte[] body = request.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(request.getEntity());
            calls.add(new SeenRequest(request.getMethod(), new String(body, java.nio.charset.StandardCharsets.UTF_8),
                    header(request, "X-Service-Secret"), header(request, "Authorization"), header(request, "X-Trace")));
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(calls.size() == 1 ? status : 204);
            if (calls.size() == 1) { response.setHeader("Location", location); }
            return CloseableHttpResponse.adapt(response);
        }
        private static String header(ClassicHttpRequest request, String name) {
            return request.getFirstHeader(name) == null ? null : request.getFirstHeader(name).getValue();
        }
        public void close() { }
        public void close(CloseMode mode) { }
    }
}
