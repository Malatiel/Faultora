package dev.faultora.faults.toxiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.result.ActiveFault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests against a stub Toxiproxy admin API — no Docker required.
 * The stub knows one proxy, {@code payments}, and records every request.
 */
class ToxiproxyFaultProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record AdminRequest(String method, String path, String body) {}

    private HttpServer admin;
    private ToxiproxyFaultProvider provider;
    private final List<AdminRequest> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStubAdmin() throws IOException {
        admin = HttpServer.create(new InetSocketAddress(0), 0);
        admin.createContext("/", exchange -> {
            String body = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // The raw path is what the admin API actually received: a decoded
            // one would hide exactly the escaping this provider must do.
            String path = exchange.getRequestURI().getRawPath();
            requests.add(new AdminRequest(exchange.getRequestMethod(), path, body));

            int status;
            if (!path.startsWith("/proxies/payments/")) {
                status = 404;
            } else if ("POST".equals(exchange.getRequestMethod())) {
                status = 200;
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                status = 204;
            } else {
                status = 405;
            }
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        admin.start();
        provider = new ToxiproxyFaultProvider(
                URI.create("http://localhost:" + admin.getAddress().getPort()));
    }

    @AfterEach
    void stopStubAdmin() {
        if (admin != null) admin.stop(0);
    }

    private FaultContext context(String scope) {
        return new FaultContext(scope, System.currentTimeMillis() + 60_000, Map.of());
    }

    @Test
    void advertisesNetworkFaultCapabilities() {
        assertThat(provider.capabilities()).containsExactlyInAnyOrder(
                "network-latency", "network-timeout", "network-reset", "network-bandwidth");
    }

    @Test
    void latencyInjectionPostsAToxicWithLatencyAttributes() throws Exception {
        ActiveFault fault = provider.inject("network-latency",
                Map.of("latencyMs", 250, "jitterMs", 50), context("payments"));

        assertThat(provider.activeCount()).isEqualTo(1);
        assertThat(fault.targetScope()).isEqualTo("payments");
        assertThat(fault.handle()).startsWith("faultora-network-latency-");

        AdminRequest post = requests.get(requests.size() - 1);
        assertThat(post.method()).isEqualTo("POST");
        assertThat(post.path()).isEqualTo("/proxies/payments/toxics");
        JsonNode toxic = MAPPER.readTree(post.body());
        assertThat(toxic.get("type").asText()).isEqualTo("latency");
        assertThat(toxic.get("name").asText()).isEqualTo(fault.handle());
        assertThat(toxic.get("stream").asText()).isEqualTo("downstream");
        assertThat(toxic.get("attributes").get("latency").asLong()).isEqualTo(250);
        assertThat(toxic.get("attributes").get("jitter").asLong()).isEqualTo(50);
    }

    @Test
    void timeoutResetAndBandwidthMapToTheirToxicTypes() throws Exception {
        provider.inject("network-timeout", Map.of("timeoutMs", 1000), context("payments"));
        provider.inject("network-reset", Map.of(), context("payments"));
        provider.inject("network-bandwidth",
                Map.of("rateKbps", 64, "direction", "upstream"), context("payments"));

        List<String> types = new ArrayList<>();
        List<String> streams = new ArrayList<>();
        for (AdminRequest request : requests) {
            JsonNode toxic = MAPPER.readTree(request.body());
            types.add(toxic.get("type").asText());
            streams.add(toxic.get("stream").asText());
        }
        assertThat(types).containsExactly("timeout", "reset_peer", "bandwidth");
        assertThat(streams).containsExactly("downstream", "downstream", "upstream");
    }

    @Test
    void rollbackDeletesTheToxicAndIsIdempotent() {
        ActiveFault fault = provider.inject("network-latency",
                Map.of("latencyMs", 100), context("payments"));

        provider.rollback(fault, context("payments"));
        provider.rollback(fault, context("payments"));

        assertThat(provider.activeCount()).isZero();
        long deletes = requests.stream()
                .filter(r -> "DELETE".equals(r.method()))
                .filter(r -> r.path().endsWith("/toxics/" + fault.handle()))
                .count();
        assertThat(deletes).isEqualTo(1);
    }

    @Test
    void unknownProxyIsReportedAsInvalidTargetScope() {
        assertThatThrownBy(() -> provider.inject("network-latency",
                Map.of("latencyMs", 100), context("orders")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no proxy named 'orders'");
        assertThat(provider.activeCount()).isZero();
    }

    @Test
    void wildcardScopeIsRejectedForNetworkFaults() {
        assertThatThrownBy(() -> provider.inject("network-latency",
                Map.of("latencyMs", 100), context("*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name a Toxiproxy proxy");
        assertThat(requests).isEmpty();
    }

    @Test
    void invalidParametersAreRejectedBeforeAnyAdminCall() {
        assertThatThrownBy(() -> provider.inject("network-latency",
                Map.of(), context("payments")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latencyMs");
        assertThatThrownBy(() -> provider.inject("network-bandwidth",
                Map.of("rateKbps", 0), context("payments")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rateKbps");
        assertThatThrownBy(() -> provider.inject("network-latency",
                Map.of("latencyMs", 100, "direction", "sideways"), context("payments")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("direction");
        assertThatThrownBy(() -> provider.inject("disk-full",
                Map.of(), context("payments")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void aProxyNameCannotReachPastItsOwnPathSegment() {
        // The scenario names the proxy; unencoded, these separators would make
        // the admin API act on a resource the scenario never named.
        assertThatThrownBy(() -> provider.inject("network-latency",
                Map.of("latencyMs", 100), context("payments/../orders")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no proxy named");

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).path())
                .isEqualTo("/proxies/payments%2F..%2Forders/toxics");
    }

    @Test
    void twoRunsNeverGiveTwoToxicsTheSameName() {
        // A toxic leaked by an earlier run stays on the proxy. If the next run
        // reused its names, deleting one would delete the other's fault.
        ToxiproxyFaultProvider nextRun = new ToxiproxyFaultProvider(
                URI.create("http://localhost:" + admin.getAddress().getPort()));

        ActiveFault first = provider.inject("network-latency",
                Map.of("latencyMs", 100), context("payments"));
        ActiveFault second = nextRun.inject("network-latency",
                Map.of("latencyMs", 100), context("payments"));

        assertThat(first.handle()).isNotEqualTo(second.handle());
    }

    @Test
    void unreachableAdminFailsInjectionWithoutRegisteringAFault() {
        ToxiproxyFaultProvider offline = new ToxiproxyFaultProvider(
                URI.create("http://localhost:1"));

        assertThatThrownBy(() -> offline.inject("network-latency",
                Map.of("latencyMs", 100), context("payments")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
        assertThat(offline.activeCount()).isZero();
    }
}
