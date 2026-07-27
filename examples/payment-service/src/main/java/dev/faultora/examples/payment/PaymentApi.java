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
 * <p>
 * A payment settles asynchronously: it is reported as {@code pending} until
 * {@code settlementDelayMs} has passed since creation and as {@code settled}
 * afterwards. The transition is derived from the creation timestamp on every
 * read, so it needs no background thread and stays reproducible.
 */
public class PaymentApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Map<String, Object>> payments = new ConcurrentHashMap<>();
    private final Map<String, String> paymentIdsByIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private final boolean atomicIdempotency;
    private final long settlementDelayMs;
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private int port;

    /** Default settlement delay of the example API, in milliseconds. */
    public static final long DEFAULT_SETTLEMENT_DELAY_MS = 300;

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
        this(atomicIdempotency, DEFAULT_SETTLEMENT_DELAY_MS);
    }

    /**
     * @param atomicIdempotency see {@link #PaymentApi(boolean)}
     * @param settlementDelayMs how long a payment stays {@code pending} before
     *                          reads report it as {@code settled}
     */
    public PaymentApi(boolean atomicIdempotency, long settlementDelayMs) {
        this.atomicIdempotency = atomicIdempotency;
        this.settlementDelayMs = settlementDelayMs;
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
            respond(exchange, 201, view(payments.get(storeNewPayment(request))));
            return;
        }

        if (atomicIdempotency) {
            boolean[] created = {false};
            String id = paymentIdsByIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
                created[0] = true;
                return storeNewPayment(request);
            });
            respond(exchange, created[0] ? 201 : 200, view(payments.get(id)));
            return;
        }

        // Deliberately broken variant: check-then-act with a widened race
        // window. Two concurrent requests with the same key both pass the
        // check and create two payments.
        String existingId = paymentIdsByIdempotencyKey.get(idempotencyKey);
        if (existingId != null) {
            respond(exchange, 200, view(payments.get(existingId)));
            return;
        }
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        String id = storeNewPayment(request);
        paymentIdsByIdempotencyKey.put(idempotencyKey, id);
        respond(exchange, 201, view(payments.get(id)));
    }

    private String storeNewPayment(Map<String, Object> request) {
        String id = "pay-" + idCounter.incrementAndGet();
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", id);
        payment.put("amount", request.getOrDefault("amount", 100));
        payment.put("currency", request.getOrDefault("currency", "USD"));
        payment.put("status", "pending");
        payment.put("createdAt", System.currentTimeMillis());
        payments.put(id, payment);
        return id;
    }

    private void handleGetPayment(HttpExchange exchange, String id) throws IOException {
        Map<String, Object> payment = payments.get(id);
        if (payment == null) {
            respond(exchange, 404, Map.of("error", "Payment not found: " + id));
        } else {
            respond(exchange, 200, view(payment));
        }
    }

    private void handleListPayments(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> view = new ArrayList<>();
        for (Map<String, Object> payment : payments.values()) {
            view.add(view(payment));
        }
        respond(exchange, 200, view);
    }

    /**
     * Read view of a stored payment: the settlement status is derived from the
     * creation timestamp, so a payment observed twice can legitimately change
     * from {@code pending} to {@code settled} between reads.
     */
    private Map<String, Object> view(Map<String, Object> payment) {
        long createdAt = ((Number) payment.get("createdAt")).longValue();
        Map<String, Object> settled = new LinkedHashMap<>(payment);
        settled.put("status",
                System.currentTimeMillis() - createdAt >= settlementDelayMs
                        ? "settled" : "pending");
        return settled;
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
