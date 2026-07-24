package dev.faultora.examples.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic example payment API for end-to-end testing.
 * Uses Java's built-in HttpServer — no external dependencies.
 * Payment IDs are sequential; responses are deterministic.
 */
public class PaymentApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Map<String, Object>> payments = new ConcurrentHashMap<>();
    private final Map<String, String> paymentIdsByIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private final boolean atomicIdempotency;
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private int port;

    public PaymentApi() {
        this(true);
    }

    /**
     * @param atomicIdempotency when false, the idempotency check is a
     *                          deliberate check-then-act race — the known
     *                          broken implementation the reliability suite
     *                          must detect
     */
    public PaymentApi(boolean atomicIdempotency) {
        this.atomicIdempotency = atomicIdempotency;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/payments", new PaymentsHandler());
        server.createContext("/health", exchange -> respond(exchange, 200, Map.of("status", "ok")));
        // Concurrent handling is required for the parallel reliability scenarios.
        executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    private class PaymentsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            try {
                if ("/payments".equals(path)) {
                    switch (method) {
                        case "POST" -> handleCreatePayment(exchange);
                        case "GET" -> handleListPayments(exchange);
                        default -> respond(exchange, 405, Map.of("error", "Method not allowed"));
                    }
                } else if (path.startsWith("/payments/")) {
                    String id = path.substring("/payments/".length());
                    switch (method) {
                        case "GET" -> handleGetPayment(exchange, id);
                        case "DELETE" -> handleDeletePayment(exchange, id);
                        default -> respond(exchange, 405, Map.of("error", "Method not allowed"));
                    }
                } else {
                    respond(exchange, 404, Map.of("error", "Not found"));
                }
            } catch (Exception e) {
                respond(exchange, 500, Map.of("error", "Internal server error"));
            }
        }
    }

    private void handleCreatePayment(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> request = body.isBlank()
                ? Map.of()
                : MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});

        // Replaying a known Idempotency-Key returns the original payment
        // instead of creating a duplicate.
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            respond(exchange, 201, payments.get(storeNewPayment(request)));
            return;
        }

        if (atomicIdempotency) {
            boolean[] created = {false};
            String id = paymentIdsByIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
                created[0] = true;
                return storeNewPayment(request);
            });
            respond(exchange, created[0] ? 201 : 200, payments.get(id));
            return;
        }

        // Deliberately broken variant: check-then-act with a widened race
        // window. Two concurrent requests with the same key both pass the
        // check and create two payments.
        String existingId = paymentIdsByIdempotencyKey.get(idempotencyKey);
        if (existingId != null) {
            respond(exchange, 200, payments.get(existingId));
            return;
        }
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        String id = storeNewPayment(request);
        paymentIdsByIdempotencyKey.put(idempotencyKey, id);
        respond(exchange, 201, payments.get(id));
    }

    private String storeNewPayment(Map<String, Object> request) {
        String id = "pay-" + idCounter.incrementAndGet();
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", id);
        payment.put("amount", request.getOrDefault("amount", 100));
        payment.put("currency", request.getOrDefault("currency", "USD"));
        payment.put("status", "created");
        payment.put("createdAt", System.currentTimeMillis());
        payments.put(id, payment);
        return id;
    }

    private void handleGetPayment(HttpExchange exchange, String id) throws IOException {
        Map<String, Object> payment = payments.get(id);
        if (payment == null) {
            respond(exchange, 404, Map.of("error", "Payment not found: " + id));
        } else {
            respond(exchange, 200, payment);
        }
    }

    private void handleListPayments(HttpExchange exchange) throws IOException {
        respond(exchange, 200, new ArrayList<>(payments.values()));
    }

    private void handleDeletePayment(HttpExchange exchange, String id) throws IOException {
        Map<String, Object> removed = payments.remove(id);
        if (removed == null) {
            respond(exchange, 404, Map.of("error", "Payment not found: " + id));
        } else {
            respond(exchange, 200, Map.of("id", id, "status", "deleted"));
        }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] response = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
}
