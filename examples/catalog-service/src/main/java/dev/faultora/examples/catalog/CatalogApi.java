package dev.faultora.examples.catalog;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A loopback-only, in-memory bulk catalog. It is a local synthetic fixture, not a deployment. */
public final class CatalogApi implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 65536;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final Map<String, List<JsonNode>> collections = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final boolean ignoreLabelFilter;
    private HttpServer server;
    private ExecutorService executor;

    public CatalogApi() { this(false); }

    /** Operator-selected defect used to prove that assertions detect a wrong query result. */
    public CatalogApi(boolean ignoreLabelFilter) { this.ignoreLabelFilter = ignoreLabelFilter; }

    public void start() throws IOException { start(0); }

    public void start(int port) throws IOException {
        if (server != null) throw new IllegalStateException("Create a new catalog instance to restart");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        executor = Executors.newFixedThreadPool(4);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public int requests() { return requests.get(); }
    public int collectionCount() { return collections.size(); }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try {
            String[] path = exchange.getRequestURI().getRawPath().split("/", -1);
            if (path.length != 4 || !path[1].equals("collections") || !path[3].equals("documents")
                    || !path[2].matches("[A-Za-z0-9_-]{1,64}")) {
                respond(exchange, 404, Map.of("code", "NOT_FOUND"));
                return;
            }
            String collection = path[2];
            switch (exchange.getRequestMethod()) {
                case "PUT" -> replace(exchange, collection);
                case "GET" -> query(exchange, collection);
                case "DELETE" -> {
                    collections.remove(collection);
                    exchange.sendResponseHeaders(204, -1);
                }
                default -> respond(exchange, 405, Map.of("code", "METHOD_NOT_ALLOWED"));
            }
        } finally {
            exchange.close();
        }
    }

    private void replace(HttpExchange exchange, String collection) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            respond(exchange, 413, Map.of("code", "BODY_TOO_LARGE"));
            return;
        }
        JsonNode batch;
        try {
            batch = JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes);
        } catch (JsonProcessingException invalid) {
            respond(exchange, 400, Map.of("code", "INVALID_DOCUMENTS"));
            return;
        }
        if (batch == null || !batch.isArray() || batch.size() > 32) {
            respond(exchange, 400, Map.of("code", "INVALID_DOCUMENTS"));
            return;
        }
        Set<String> keys = new HashSet<>();
        List<JsonNode> documents = new ArrayList<>();
        for (JsonNode document : batch) {
            if (!validDocument(document) || !keys.add(document.path("key").asText())) {
                respond(exchange, 400, Map.of("code", "INVALID_DOCUMENTS"));
                return;
            }
            documents.add(document.deepCopy());
        }
        documents.sort(Comparator.comparing(value -> value.path("key").asText()));
        collections.put(collection, List.copyOf(documents));
        respond(exchange, 200, Map.of("stored", documents.size()));
    }

    private static boolean validDocument(JsonNode document) {
        if (!document.isObject() || document.size() != 3 || !document.path("key").isTextual()
                || !document.path("key").asText().matches("[A-Za-z0-9_-]{1,64}")
                || !document.path("labels").isArray() || document.path("labels").size() > 16
                || !document.path("attributes").isObject() || document.path("attributes").size() != 1) return false;
        JsonNode pages = document.path("attributes").path("pages");
        if (!pages.isIntegralNumber() || !pages.canConvertToInt() || pages.asInt() < 1 || pages.asInt() > 1000) return false;
        for (JsonNode label : document.get("labels")) {
            if (!label.isTextual() || label.asText().isBlank() || label.asText().length() > 64) return false;
        }
        return true;
    }

    private void query(HttpExchange exchange, String collection) throws IOException {
        List<JsonNode> documents = collections.get(collection);
        if (documents == null) {
            respond(exchange, 404, Map.of("code", "NOT_FOUND"));
            return;
        }
        String label = null;
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            String[] query = raw.split("=", 2);
            if (query.length != 2 || !query[0].equals("label") || query[1].contains("&")) {
                respond(exchange, 400, Map.of("code", "INVALID_QUERY"));
                return;
            }
            try { label = URLDecoder.decode(query[1], StandardCharsets.UTF_8); }
            catch (IllegalArgumentException invalid) {
                respond(exchange, 400, Map.of("code", "INVALID_QUERY"));
                return;
            }
            if (label.isBlank() || label.length() > 64) {
                respond(exchange, 400, Map.of("code", "INVALID_QUERY"));
                return;
            }
        }
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode document : documents) {
            boolean matches = label == null || ignoreLabelFilter;
            for (JsonNode candidate : document.get("labels")) matches |= candidate.asText().equals(label);
            if (matches) result.add(document);
        }
        respond(exchange, 200, result);
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        collections.clear();
    }
}
