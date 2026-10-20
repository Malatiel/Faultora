package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The HTTP families in examples/README.md; the fifth uses CrossComponentE2ETest. */
class ExampleFamiliesE2ETest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"passing.yaml", "fault-retry.yaml", "eventually-settlement.yaml", "fault-lifecycle.yaml"})
    void publishedExamplePassesWithEvaluatedEvidence(String scenario) throws Exception {
        PaymentApi api = new PaymentApi();
        api.start();
        try {
            assertThat(run(scenario, api.baseUrl())).isZero();
            assertThat(events("ASSERTION_EVALUATED")).isNotEmpty().allSatisfy(event ->
                    assertThat(event.path("outcome").asText()).isEqualTo("PASS"));
            switch (scenario) {
                case "passing.yaml" -> assertThat(events("ASSERTION_EVALUATED"))
                        .anySatisfy(event -> assertThat(event.path("assertionType").asText()).isEqualTo("schema"));
                case "fault-retry.yaml" -> assertThat(events("OPERATION_RETRIED")).isNotEmpty();
                case "eventually-settlement.yaml" -> assertThat(events("CONDITION_POLLED"))
                        .anySatisfy(event -> assertThat(event.path("satisfied").asBoolean()).isTrue());
                case "fault-lifecycle.yaml" -> {
                    assertCleanupAndRollback();
                    try (HttpClient client = HttpClient.newHttpClient()) {
                        var response = client.send(HttpRequest.newBuilder(URI.create(api.baseUrl() + "/payments"))
                                .GET().build(), HttpResponse.BodyHandlers.ofString());
                        assertThat(response.statusCode()).isEqualTo(200);
                        assertThat(MAPPER.readTree(response.body())).isEmpty();
                    }
                }
                default -> throw new AssertionError("Unknown family");
            }
        } finally {
            api.stop();
        }
    }

    @Test
    void contractExampleRejectsAResponseWithTheWrongAmountType() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                boolean create = exchange.getRequestMethod().equals("POST");
                byte[] body = (create
                        ? "{\"id\":\"synthetic-payment\",\"amount\":\"wrong-type\",\"currency\":\"EUR\",\"status\":\"pending\"}"
                        : "[]").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(create ? 201 : 200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            assertThat(run("passing.yaml", "http://127.0.0.1:" + server.getAddress().getPort())).isEqualTo(1);
            assertThat(events("ASSERTION_EVALUATED")).anySatisfy(event -> {
                assertThat(event.path("assertionType").asText()).isEqualTo("schema");
                assertThat(event.path("outcome").asText()).isEqualTo("FAIL");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void eventualExampleFailsIfSettlementNeverBecomesVisible() throws Exception {
        PaymentApi api = new PaymentApi(true, Long.MAX_VALUE);
        api.start();
        try {
            assertThat(run("eventually-settlement.yaml", api.baseUrl())).isEqualTo(1);
            assertThat(events("NODE_FAILED")).anySatisfy(event ->
                    assertThat(event.path("error").path("code").asText()).isEqualTo("EVENTUALLY_TIMEOUT"));
        } finally {
            api.stop();
        }
    }

    @Test
    void lifecycleStillCleansUpAndRollsBackWhenItsAssertionFails() throws Exception {
        AtomicInteger deleted = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                String method = exchange.getRequestMethod();
                if (method.equals("DELETE")) {
                    deleted.incrementAndGet();
                }
                byte[] body = "{\"id\":\"synthetic-payment\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(method.equals("POST") ? 201 : method.equals("DELETE") ? 200 : 404,
                        body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            assertThat(run("fault-lifecycle.yaml", "http://127.0.0.1:" + server.getAddress().getPort())).isEqualTo(1);
            assertThat(events("ASSERTION_EVALUATED")).anySatisfy(event ->
                    assertThat(event.path("outcome").asText()).isEqualTo("FAIL"));
            assertCleanupAndRollback();
            assertThat(deleted).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    private int run(String scenario, String target) throws Exception {
        List<String> options = new ArrayList<>(List.of(
                "--openapi", ExampleFixtures.openApi().toString(), "--target", target));
        if (scenario.equals("fault-lifecycle.yaml")) {
            options.add("--allow-destructive");
        }
        return PackagedExampleCli.run(directory, ExampleFixtures.scenario(scenario), options);
    }

    private void assertCleanupAndRollback() throws Exception {
        assertThat(events("NODE_COMPLETED")).anySatisfy(event ->
                assertThat(event.path("nodeId").asText()).isEqualTo("delete-created-payment"));
        assertThat(events("FAULT_INJECTED")).hasSize(1);
        assertThat(events("FAULT_ROLLED_BACK")).singleElement().satisfies(event ->
                assertThat(event.path("rollbackStatus").asText()).isEqualTo("run-end"));
        assertThat(events("CLEANUP_COMPLETED")).hasSize(1);
    }

    private List<JsonNode> events(String type) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (String line : Files.readAllLines(directory.resolve("events.ndjson"))) {
            JsonNode event = MAPPER.readTree(line);
            if (event.path("eventType").asText().equals(type)) {
                result.add(event);
            }
        }
        return result;
    }
}
