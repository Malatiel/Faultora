package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Operator-only profiles; preparation receives only the neutral published files. */
class PairedQualificationE2ETest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of(System.getProperty("faultora.projectRoot"));
    private static final Path EXAMPLE = ROOT.resolve("examples/payment-service/qualification");
    @TempDir Path directory;

    private enum Defect {
        NONE(Set.of()),
        RESPONSE_SCHEMA(Set.of("submit-schema", "repeat-schema", "observe-schema")),
        REPLAY_SIDE_EFFECT(Set.of("one-payment")),
        ACCEPTED_AMOUNT(Set.of("accepted-amount"));

        final Set<String> failedAssertions;

        Defect(Set<String> failedAssertions) {
            this.failedAssertions = failedAssertions;
        }
    }

    @Test
    void theReferenceScenarioAlsoPassesAgainstThePublishedPaymentApi() throws Exception {
        PaymentApi api = new PaymentApi();
        api.start();
        try {
            assertResult(run(input(), api.baseUrl()), Set.of());
        } finally {
            api.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"RESPONSE_SCHEMA,false", "RESPONSE_SCHEMA,true",
            "REPLAY_SIDE_EFFECT,false", "REPLAY_SIDE_EFFECT,true",
            "ACCEPTED_AMOUNT,false", "ACCEPTED_AMOUNT,true"})
    void aFrozenTaskAndContractFindTheDefectInEitherOrder(Defect hidden, boolean reverse) throws Exception {
        try (var service = new Service()) {
            // Freeze the complete preparer-visible input before selecting either profile.
            Path input = input();
            var frozen = snapshot(input);
            String target = service.endpoint();
            assertThat(service.requests()).isZero();
            List<Defect> order = reverse ? List.of(hidden, Defect.NONE) : List.of(Defect.NONE, hidden);
            List<JsonNode> starts = new ArrayList<>();
            for (Defect profile : order) {
                service.select(profile);
                var result = run(input, target);
                assertResult(result, profile.failedAssertions);
                assertThat(result.events().stream().filter(event -> event.path("eventType").asText()
                        .equals("ASSERTION_EVALUATED"))).hasSize(10);
                assertThat(service.requests()).isEqualTo(3);
                assertThat(snapshot(input)).isEqualTo(frozen);
                starts.add(result.event("RUN_STARTED"));
            }
            for (String field : List.of("scenarioDigest", "catalogDigest", "seed", "resolvedConfig")) {
                assertThat(starts.get(0).path(field)).as("same execution input: %s", field)
                        .isEqualTo(starts.get(1).path(field));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Defect.class, names = {"RESPONSE_SCHEMA", "REPLAY_SIDE_EFFECT", "ACCEPTED_AMOUNT"})
    void removingOnlyTheNeededAssertionsMakesTheDefectInvisibleEvenWithABrokenName(Defect hidden) throws Exception {
        try (var service = new Service()) {
            Path input = input();
            var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> scenario = yaml.load(Files.readString(input.resolve("scenario.yaml")));
            scenario.put("metadata", Map.of("name", "known-broken"));
            @SuppressWarnings("unchecked")
            var assertions = (List<Map<String, Object>>) scenario.get("assertions");
            assertThat(assertions.removeIf(assertion -> hidden.failedAssertions.contains(assertion.get("id"))))
                    .isTrue();
            Files.writeString(input.resolve("scenario.yaml"), yaml.dump(scenario));
            service.select(hidden);
            var result = run(input, service.endpoint());
            assertResult(result, Set.of());
            assertThat(result.events().stream().filter(event -> event.path("eventType").asText()
                    .equals("ASSERTION_EVALUATED"))).hasSize(10 - hidden.failedAssertions.size());
        }
    }

    private Path input() throws Exception {
        Path input = Files.createDirectory(directory.resolve("input"));
        Files.copy(EXAMPLE.resolve("README.md"), input.resolve("README.md"));
        Files.copy(ExampleFixtures.openApi(), input.resolve("openapi.yaml"));
        Files.copy(EXAMPLE.resolve("scenario.yaml"), input.resolve("scenario.yaml"));
        try (var files = Files.list(input)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("README.md", "openapi.yaml", "scenario.yaml");
        }
        for (String text : List.of(Files.readString(input.resolve("README.md")),
                Files.readString(input.resolve("openapi.yaml")), Files.readString(input.resolve("scenario.yaml")))) {
            assertThat(text.toLowerCase(java.util.Locale.ROOT)).doesNotContain("healthy", "broken");
            for (Defect profile : Defect.values()) {
                assertThat(text).doesNotContain(profile.name());
            }
        }
        return input;
    }

    private Map<String, String> snapshot(Path input) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String file : List.of("README.md", "openapi.yaml", "scenario.yaml")) {
            hashes.put(file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(input.resolve(file)))));
        }
        return hashes;
    }

    private Result run(Path input, String target) throws Exception {
        Path output = directory.resolve("run-" + UUID.randomUUID());
        int exit = PackagedExampleCli.run(output, input.resolve("scenario.yaml"), List.of(
                "--openapi", input.resolve("openapi.yaml").toString(), "--target", target, "--seed", "42"));
        List<JsonNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(output.resolve("events.ndjson"))) {
            events.add(JSON.readTree(line));
        }
        assertThat(JSON.readTree(Files.readString(output.resolve("report.json"))))
                .isEqualTo(JSON.valueToTree(events));
        return new Result(exit, events, Files.readString(output.resolve("stdout.txt")));
    }

    private record Result(int exit, List<JsonNode> events, String stdout) {
        JsonNode event(String type) {
            var found = events.stream().filter(event -> event.path("eventType").asText().equals(type)).toList();
            assertThat(found).as(type).hasSize(1);
            return found.getFirst();
        }
    }

    private void assertResult(Result result, Set<String> expectedFailures) {
        Map<String, String> outcomes = new LinkedHashMap<>();
        var operations = result.events().stream().filter(event -> event.path("eventType").asText()
                .equals("NODE_COMPLETED") && event.path("statusCode").asInt() != 0).toList();
        assertThat(operations).extracting(event -> event.path("statusCode").asInt()).containsExactly(201, 200, 200);
        for (JsonNode event : result.events()) {
            if (event.path("eventType").asText().equals("ASSERTION_EVALUATED")) {
                assertThat(outcomes.put(event.path("nodeId").asText(), event.path("outcome").asText())).isNull();
            }
        }
        assertThat(outcomes).isNotEmpty();
        assertThat(outcomes.values()).allSatisfy(outcome -> assertThat(outcome).isIn("PASS", "FAIL"));
        assertThat(outcomes.entrySet().stream().filter(entry -> entry.getValue().equals("FAIL"))
                .map(Map.Entry::getKey)).containsExactlyInAnyOrderElementsOf(expectedFailures);
        boolean passed = expectedFailures.isEmpty();
        assertThat(result.exit()).isEqualTo(passed ? 0 : 1);
        result.event(passed ? "RUN_COMPLETED" : "RUN_FAILED");
        assertThat(result.stdout()).contains("Result: " + (passed ? "PASSED" : "FAILED"));
    }

    /** Never copied into the preparer input or sent through its command line. */
    private static final class Service implements AutoCloseable {
        private final HttpServer server;
        private final List<Map<String, Object>> payments = new ArrayList<>();
        private Defect profile;
        private int requests;

        Service() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/payments", this::handle);
            server.start();
        }

        private synchronized void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                requests++;
                boolean submit = exchange.getRequestMethod().equals("POST");
                int status = submit && payments.isEmpty() ? 201 : 200;
                if (submit) {
                    JsonNode body = JSON.readTree(exchange.getRequestBody());
                    if (!"qualification-payment-42".equals(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                            || body.path("amount").asInt() != 4200 || !body.path("currency").asText().equals("EUR")) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    if (payments.isEmpty() || profile == Defect.REPLAY_SIDE_EFFECT) {
                        Map<String, Object> payment = new LinkedHashMap<>();
                        payment.put("id", "payment-" + (payments.size() + 1));
                        payment.put("amount", profile == Defect.ACCEPTED_AMOUNT ? 4201 : 4200);
                        payment.put("currency", "EUR");
                        payment.put("status", profile == Defect.RESPONSE_SCHEMA ? true : "pending");
                        payments.add(payment);
                    }
                }
                // An extra write can occur even while the cached response is replayed.
                byte[] response = JSON.writeValueAsBytes(submit ? payments.getFirst() : payments);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            }
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        synchronized void select(Defect selected) {
            profile = selected;
            payments.clear();
            requests = 0;
        }

        synchronized int requests() {
            return requests;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
