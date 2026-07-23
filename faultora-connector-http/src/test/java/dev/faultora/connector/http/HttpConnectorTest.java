package dev.faultora.connector.http;

import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpConnectorTest {

    private HttpConnector connector;
    private ConnectorContext context;

    @BeforeEach
    void setUp() {
        connector = new HttpConnector(DestinationPolicy.permissive());
        context = new ConnectorContext(
                EvidencePolicy.MINIMAL,
                handleId -> null,
                5000, 30000, 60000,
                Map.of()
        );
    }

    @AfterEach
    void tearDown() {
        connector.close();
    }

    @Test
    void connectorDeclaresHttpProtocol() {
        assertThat(connector.protocol()).isEqualTo(new ProtocolId("http"));
    }

    @Test
    void connectorDeclaresCapabilities() {
        assertThat(connector.capabilities()).contains("http-get", "http-post", "http-put");
    }

    @Test
    void prepareReturnsNonNullTarget() {
        TargetDefinition target = new TargetDefinition(
                new TargetId("test"), "Test", "http://localhost:1",
                List.of(new ProtocolId("http")), List.of(), Map.of()
        );

        var prepared = connector.prepare(target, context);
        assertThat(prepared).isNotNull();
        assertThat(prepared.targetDefinition()).isEqualTo(target);
    }

    @Test
    void prepareRejectsBlockedDestination() {
        HttpConnector blockingConnector = new HttpConnector(DestinationPolicy.defaultPolicy());
        TargetDefinition target = new TargetDefinition(
                new TargetId("test"), "Test", "http://localhost:8080",
                List.of(new ProtocolId("http")), List.of(), Map.of()
        );

        assertThatThrownBy(() -> blockingConnector.prepare(target, context))
                .isInstanceOf(DestinationPolicyViolation.class);
    }

    @Test
    void executeToUnreachableHostReturnsNetworkError() {
        TargetDefinition target = new TargetDefinition(
                new TargetId("unreachable"), "Unreachable", "http://192.0.2.1:1",
                List.of(new ProtocolId("http")), List.of(), Map.of()
        );
        var prepared = connector.prepare(target, context);

        OperationDefinition operation = new OperationDefinition(
                new OperationId("test-op"),
                new ProtocolId("http"),
                new TargetId("unreachable"),
                SafetyClassification.READ_ONLY,
                Map.of(), null, Map.of(),
                Map.of("method", "GET", "path", "/")
        );

        ConnectorContext shortCtx = new ConnectorContext(
                EvidencePolicy.MINIMAL, handleId -> null,
                500, 500, 1000, Map.of()
        );

        OperationResult result = connector.execute(prepared, operation, Map.of(), shortCtx);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void releaseDoesNotThrow() {
        TargetDefinition target = new TargetDefinition(
                new TargetId("test"), "Test", "http://localhost:1",
                List.of(new ProtocolId("http")), List.of(), Map.of()
        );
        var prepared = connector.prepare(target, context);

        // Should not throw
        connector.release(prepared);
    }

    @Test
    void closeDoesNotThrow() {
        connector.close();
    }

    @Test
    void connectorWithDefaultPolicyRejectsPrivateNetwork() {
        HttpConnector defaultConnector = new HttpConnector();
        TargetDefinition target = new TargetDefinition(
                new TargetId("internal"), "Internal", "http://10.0.0.1:8080",
                List.of(new ProtocolId("http")), List.of(), Map.of()
        );

        assertThatThrownBy(() -> defaultConnector.prepare(target, context))
                .isInstanceOf(DestinationPolicyViolation.class);
    }

    @Test
    void connectorWithDefaultPolicyAllowsPublicHost() {
        HttpConnector defaultConnector = new HttpConnector();
        // Use IP literal to avoid DNS dependency in sandboxed environments
        TargetDefinition target = new TargetDefinition(
                new TargetId("public"), "Public", "http://8.8.8.8",
                List.of(new ProtocolId("http")), List.of(), Map.of()
        );

        var prepared = defaultConnector.prepare(target, context);
        assertThat(prepared).isNotNull();
    }

    // ---- originEquals tests ----

    @ParameterizedTest
    @CsvSource({
            "https://api.example.com, https://api.example.com, true",
            "https://api.example.com:443, https://api.example.com, true",
            "http://api.example.com:80, http://api.example.com, true",
            "https://api.example.com, http://api.example.com, false",
            "https://api.example.com, https://api.example.com:8443, false",
            "https://api.example.com, https://evil.example.com, false",
            "http://api.example.com:8080, http://api.example.com:8080, true",
            "http://api.example.com:8080, http://api.example.com:9090, false",
    })
    void originEqualsComparesSchemeHostAndPort(String a, String b, boolean expected) {
        assertThat(HttpConnector.originEquals(URI.create(a), URI.create(b)))
                .as("originEquals(%s, %s)", a, b)
                .isEqualTo(expected);
    }

    @Test
    void originEqualsTreatsDifferentSchemeAsDifferentOrigin() {
        // HTTPS→HTTP downgrade must be detected
        assertThat(HttpConnector.originEquals(
                URI.create("https://api.example.com"),
                URI.create("http://api.example.com")))
                .isFalse();
    }

    @Test
    void originEqualsTreatsDifferentPortAsDifferentOrigin() {
        assertThat(HttpConnector.originEquals(
                URI.create("https://api.example.com"),
                URI.create("https://api.example.com:8443")))
                .isFalse();
    }

    // ---- Auth injection tests ----

    @Test
    void executeWithFailedSecretResolutionFailsRequest() {
        // When secret resolution throws, the connector should fail the request
        // (fail-closed: auth was explicitly configured but cannot be resolved)
        ConnectorContext failContext = new ConnectorContext(
                EvidencePolicy.MINIMAL,
                handleId -> { throw new RuntimeException("Secret not found"); },
                500, 500, 1000,
                Map.of("baseUrl", "http://192.0.2.1:1", "authSecretId", "missing-key"));

        TargetDefinition target = new TargetDefinition(
                new TargetId("fail"), "Fail", "http://192.0.2.1:1",
                List.of(new ProtocolId("http")), List.of(), Map.of());
        var prepared = connector.prepare(target, failContext);

        OperationDefinition operation = new OperationDefinition(
                new OperationId("test-op"),
                new ProtocolId("http"),
                new TargetId("fail"),
                SafetyClassification.READ_ONLY,
                Map.of(), null, Map.of(),
                Map.of("method", "GET", "path", "/"));

        OperationResult result = connector.execute(prepared, operation, Map.of(), failContext);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().message()).contains("Secret not found");
    }

    @Test
    void executeWithNullSecretHandleFailsRequest() {
        // When resolver returns null, the connector should fail the request
        // (fail-closed: auth was configured but secret is not available)
        ConnectorContext nullContext = new ConnectorContext(
                EvidencePolicy.MINIMAL,
                handleId -> null,
                500, 500, 1000,
                Map.of("baseUrl", "http://192.0.2.1:1", "authSecretId", "test-key"));

        TargetDefinition target = new TargetDefinition(
                new TargetId("null"), "Null", "http://192.0.2.1:1",
                List.of(new ProtocolId("http")), List.of(), Map.of());
        var prepared = connector.prepare(target, nullContext);

        OperationDefinition operation = new OperationDefinition(
                new OperationId("test-op"),
                new ProtocolId("http"),
                new TargetId("null"),
                SafetyClassification.READ_ONLY,
                Map.of(), null, Map.of(),
                Map.of("method", "GET", "path", "/"));

        OperationResult result = connector.execute(prepared, operation, Map.of(), nullContext);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().message()).contains("returned null");
    }

    @Test
    void executeWithMissingSecretResolverFailsRequest() {
        ConnectorContext missingResolverContext = new ConnectorContext(
                EvidencePolicy.MINIMAL,
                null,
                500, 500, 1000,
                Map.of("baseUrl", "http://192.0.2.1:1", "authSecretId", "test-key"));
        TargetDefinition target = new TargetDefinition(
                new TargetId("missing-resolver"), "Missing resolver", "http://192.0.2.1:1",
                List.of(new ProtocolId("http")), List.of(), Map.of());
        var prepared = connector.prepare(target, missingResolverContext);
        OperationDefinition operation = new OperationDefinition(
                new OperationId("test-op"),
                new ProtocolId("http"),
                new TargetId("missing-resolver"),
                SafetyClassification.READ_ONLY,
                Map.of(), null, Map.of(),
                Map.of("method", "GET", "path", "/"));

        OperationResult result =
                connector.execute(prepared, operation, Map.of(), missingResolverContext);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().message()).contains("No secret resolver configured");
    }

    @ParameterizedTest
    @CsvSource({
            "300, false",
            "301, true",
            "302, true",
            "303, true",
            "304, false",
            "305, false",
            "307, true",
            "308, true",
            "399, false"
    })
    void recognizesOnlyDefinedRedirectStatuses(int status, boolean expected) {
        assertThat(HttpConnector.isRedirectStatus(status)).isEqualTo(expected);
    }
}
