package dev.faultora.examples.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A payment provider that can take a charge and lose the answer.
 * <p>
 * It is a separate service with its own store, reachable only over HTTP,
 * because that is the property the scenario depends on: this system cannot look
 * inside it. When {@link SystemConfig#providerRespondsToTheCharge()} is false,
 * {@code POST /charges} records the charge and then fails the response — the
 * money has moved and the caller does not know it. {@code GET /charges/{id}}
 * still answers truthfully, which is what makes the outcome recoverable at all,
 * and is exactly the call a reconciliation worker makes.
 * <p>
 * This is not one of the broken variants. Nothing here is a defect: a provider
 * whose response is lost is ordinary, and a payment system that cannot survive
 * it is the defect.
 */
final class ProviderSimulator implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean respondsToTheCharge;
    private final Set<String> accepted = ConcurrentHashMap.newKeySet();

    private HttpServer server;
    private ExecutorService executor;
    private int port;

    ProviderSimulator(boolean respondsToTheCharge) {
        this.respondsToTheCharge = respondsToTheCharge;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/charges", this::handleCharges);
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.start();
    }

    int port() {
        return port;
    }

    String baseUrl() {
        return "http://localhost:" + port;
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handleCharges(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            takeCharge(exchange);
        } else if ("GET".equals(exchange.getRequestMethod())) {
            reportCharge(exchange);
        } else {
            respond(exchange, 405, Map.of("error", "method not allowed"));
        }
    }

    private void takeCharge(HttpExchange exchange) throws IOException {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        if (!request.hasNonNull("paymentId")) {
            respond(exchange, 400, Map.of("error", "paymentId is required"));
            return;
        }
        // Recorded first, and only then answered. A provider that lost the
        // response after taking the money is the whole point; one that lost it
        // before would simply not have taken the charge.
        accepted.add(request.get("paymentId").asText());

        if (!respondsToTheCharge) {
            respond(exchange, 500, Map.of("error", "the response was lost"));
            return;
        }
        respond(exchange, 200, Map.of("status", "accepted"));
    }

    private void reportCharge(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String paymentId = URLDecoder.decode(
                path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
        if (accepted.contains(paymentId)) {
            respond(exchange, 200, Map.of("status", "accepted"));
        } else {
            respond(exchange, 404, Map.of("status", "unknown"));
        }
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream response = exchange.getResponseBody()) {
            response.write(payload);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
