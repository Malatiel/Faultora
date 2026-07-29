package dev.faultora.engine.exec;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.AuthSchemeId;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.ConnectorContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TargetResolverTest {

    private static final TargetId PAYMENTS = new TargetId("payments");
    private static final TargetId LEDGER = new TargetId("ledger");
    private static final TargetId BROKER = new TargetId("broker");

    @Test
    void usesTheCatalogDefinitionWhenNoRedirectIsConfigured() {
        TargetDefinition resolved = TargetResolver.resolve(
                PAYMENTS, catalog(), context(Map.of()));

        assertThat(resolved).isNotNull();
        assertThat(resolved.baseUrl()).isEqualTo("https://payments.example.com");
        assertThat(resolved.name()).isEqualTo("Payments");
        assertThat(resolved.authSchemeIds()).containsExactly(new AuthSchemeId("bearer"));
    }

    @Test
    void aGlobalRedirectRebindsEveryTargetButKeepsItsIdentity() {
        ConnectorContext context = context(Map.of(TargetResolver.BASE_URL, "http://localhost:9999"));

        TargetDefinition payments = TargetResolver.resolve(PAYMENTS, catalog(), context);
        TargetDefinition ledger = TargetResolver.resolve(LEDGER, catalog(), context);

        assertThat(payments.baseUrl()).isEqualTo("http://localhost:9999");
        assertThat(ledger.baseUrl()).isEqualTo("http://localhost:9999");
        // Redirecting where a target lives must not change what it is.
        assertThat(payments.authSchemeIds()).containsExactly(new AuthSchemeId("bearer"));
        assertThat(payments.protocols()).containsExactly(new ProtocolId("http"));
    }

    @Test
    void aPerTargetRedirectWinsOverTheGlobalOne() {
        ConnectorContext context = context(Map.of(
                TargetResolver.BASE_URL, "http://localhost:9999",
                TargetResolver.BASE_URL_PREFIX + "ledger", "http://localhost:7777"));

        assertThat(TargetResolver.resolve(PAYMENTS, catalog(), context).baseUrl())
                .isEqualTo("http://localhost:9999");
        assertThat(TargetResolver.resolve(LEDGER, catalog(), context).baseUrl())
                .isEqualTo("http://localhost:7777");
    }

    @Test
    void aGlobalRedirectLeavesTargetsOfAnotherProtocolAlone() {
        // A run that spans HTTP and a broker gets one --target for the API. If
        // that rebound the broker too, event operations would be sent at a web
        // server and the failure would surface as a complaint about a bootstrap
        // list rather than about target resolution.
        ConnectorContext context = context(Map.of(
                TargetResolver.BASE_URL, "http://localhost:9999"));

        assertThat(TargetResolver.resolve(PAYMENTS, mixedCatalog(), context).baseUrl())
                .isEqualTo("http://localhost:9999");
        assertThat(TargetResolver.resolve(BROKER, mixedCatalog(), context).baseUrl())
                .isEqualTo("kafka://broker.example.com:9092");
    }

    @Test
    void namingATargetRedirectsItWhateverItSpeaks() {
        ConnectorContext context = context(Map.of(
                TargetResolver.BASE_URL, "http://localhost:9999",
                TargetResolver.BASE_URL_PREFIX + "broker", "kafka://localhost:19092"));

        assertThat(TargetResolver.resolve(BROKER, mixedCatalog(), context).baseUrl())
                .isEqualTo("kafka://localhost:19092");
    }

    @Test
    void aSecuredSchemeIsTheSameProtocolAsAnUnsecuredOne() {
        ConnectorContext context = context(Map.of(
                TargetResolver.BASE_URL, "https://staging.example.com"));

        assertThat(TargetResolver.resolve(PAYMENTS, mixedCatalog(), context).baseUrl())
                .isEqualTo("https://staging.example.com");
    }

    @Test
    void anUndeclaredTargetResolvesOnlyThroughAnExplicitRedirect() {
        TargetId unknown = new TargetId("warehouse");

        assertThat(TargetResolver.resolve(unknown, catalog(), context(Map.of()))).isNull();
        assertThat(TargetResolver.resolve(unknown, catalog(),
                context(Map.of(TargetResolver.BASE_URL, "http://localhost:9999"))).baseUrl())
                .isEqualTo("http://localhost:9999");
    }

    private ApiCatalog catalog() {
        return new ApiCatalog(
                new CatalogVersion("v1alpha1-test"),
                List.of(
                        new TargetDefinition(
                                PAYMENTS, "Payments", "https://payments.example.com",
                                List.of(new ProtocolId("http")),
                                List.of(new AuthSchemeId("bearer")), Map.of()),
                        new TargetDefinition(
                                LEDGER, "Ledger", "https://ledger.example.com",
                                List.of(new ProtocolId("http")), List.of(), Map.of())),
                List.of(), Map.of(), Map.of(), List.of());
    }

    /** A catalog imported from two descriptions: an API and a broker. */
    private ApiCatalog mixedCatalog() {
        return new ApiCatalog(
                new CatalogVersion("v1alpha1-mixed"),
                List.of(
                        new TargetDefinition(
                                PAYMENTS, "Payments", "https://payments.example.com",
                                List.of(new ProtocolId("http")),
                                List.of(new AuthSchemeId("bearer")), Map.of()),
                        new TargetDefinition(
                                BROKER, "Broker", "kafka://broker.example.com:9092",
                                List.of(new ProtocolId("kafka")), List.of(), Map.of())),
                List.of(), Map.of(), Map.of(), List.of());
    }

    private ConnectorContext context(Map<String, Object> config) {
        return new ConnectorContext(
                EvidencePolicy.MINIMAL, handle -> null, 1000, 1000, 1000, config);
    }
}
