package dev.faultora.examples.payment;

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
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private HttpServer server;
    private int port;

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/payments", new PaymentsHandler());
        server.createContext("/health", exchange -> respond(exchange, 200, Map.of("status", "ok")));
        server.setExecutor(null);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
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
                : MAPPER.readValue(body, Map.class);

        String id = "pay-" + idCounter.incrementAndGet();
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", id);
        payment.put("amount", request.getOrDefault("amount", 100));
        payment.put("currency", request.getOrDefault("currency", "USD"));
        payment.put("status", "created");
        payment.put("createdAt", System.currentTimeMillis());

        payments.put(id, payment);
        respond(exchange, 201, payment);
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
