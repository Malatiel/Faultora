package dev.faultora.examples.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The command side: where a payment enters the system over HTTP.
 * <p>
 * {@code POST /payments} writes two rows — the payment, and the outbox row that
 * will become the event asking for it to be settled — in <b>one transaction</b>.
 * That is the transactional outbox, and it is the whole reason the pattern
 * exists: a system that commits the payment and then publishes the event has a
 * window in which it has taken money and told nobody, and no amount of retrying
 * closes it, because the crash happens inside the window.
 * <p>
 * {@link SystemConfig#transactionalOutbox()} false is that broken system. It
 * commits the payment and never writes the outbox row, which is what a crash
 * between the commit and the publish leaves behind. The API answers 201 either
 * way: from the caller's side the two are indistinguishable, which is exactly
 * why a scenario has to look at the ledger to tell them apart.
 */
final class PaymentsApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Database database;
    private final SystemConfig config;

    private HttpServer server;
    private ExecutorService executor;
    private int port;

    PaymentsApi(Database database, SystemConfig config) {
        this.database = database;
        this.config = config;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/payments", this::handlePayments);
        server.createContext("/health", exchange ->
                respond(exchange, 200, Map.of("status", "ok")));
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.start();
    }

    int port() {
        return port;
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

    private void handlePayments(HttpExchange exchange) throws IOException {
        try {
            if ("POST".equals(exchange.getRequestMethod())) {
                createPayment(exchange);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                readPayment(exchange);
            } else {
                respond(exchange, 405, Map.of("error", "method not allowed"));
            }
        } catch (SQLException unavailable) {
            respond(exchange, 500, Map.of("error", unavailable.getMessage()));
        }
    }

    private void createPayment(HttpExchange exchange) throws IOException, SQLException {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        String paymentId = text(request, "paymentId");
        if (paymentId == null || !request.hasNonNull("amount")) {
            respond(exchange, 400, Map.of("error", "paymentId and amount are required"));
            return;
        }
        long amount = request.get("amount").asLong();
        String currency = text(request, "currency") == null ? "EUR" : text(request, "currency");

        try (Connection connection = database.transaction()) {
            try {
                insertPayment(connection, paymentId, amount, currency);
                if (config.transactionalOutbox()) {
                    insertOutbox(connection, paymentId, amount, currency);
                    connection.commit();
                } else {
                    // The broken system: the payment is committed on its own,
                    // and the event that was supposed to follow it never
                    // exists. This is what a crash after the commit leaves.
                    connection.commit();
                }
            } catch (SQLException failed) {
                connection.rollback();
                throw failed;
            }
        }

        respond(exchange, 201, ordered(
                "paymentId", paymentId, "amount", amount,
                "currency", currency, "status", "requested"));
    }

    private void insertPayment(
            Connection connection, String paymentId, long amount, String currency)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments (payment_id, amount, currency, status) "
                        + "VALUES (?, ?, ?, 'requested')")) {
            insert.setString(1, paymentId);
            insert.setLong(2, amount);
            insert.setString(3, currency);
            insert.executeUpdate();
        }
    }

    private void insertOutbox(
            Connection connection, String paymentId, long amount, String currency)
            throws SQLException {
        String payload = MAPPER.createObjectNode()
                .put("paymentId", paymentId)
                .put("amount", amount)
                .put("currency", currency)
                .toString();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO outbox (payment_id, payload) VALUES (?, ?)")) {
            insert.setString(1, paymentId);
            insert.setString(2, payload);
            insert.executeUpdate();
        }
    }

    private void readPayment(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        String paymentId = path.substring(path.lastIndexOf('/') + 1);
        try (Connection connection = database.connection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT amount, currency, status FROM payments WHERE payment_id = ?")) {
            select.setString(1, paymentId);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    respond(exchange, 404, Map.of("error", "no such payment"));
                    return;
                }
                respond(exchange, 200, ordered(
                        "paymentId", paymentId,
                        "amount", rows.getLong("amount"),
                        "currency", rows.getString("currency"),
                        "status", rows.getString("status")));
            }
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            body.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return body;
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

    /** The base URL a scenario reaches this API at. */
    String baseUrl() {
        return "http://localhost:" + port;
    }
}
