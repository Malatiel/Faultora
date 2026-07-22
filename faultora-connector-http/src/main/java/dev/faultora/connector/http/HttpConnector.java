package dev.faultora.connector.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * HTTP connector implementing the Connector SPI.
 * Executes operations against HTTP targets using Java's built-in HttpClient.
 * Enforces deadlines, captures evidence, normalizes errors, and enforces
 * destination policy to prevent SSRF.
 */
public class HttpConnector implements Connector {

    private static final ProtocolId PROTOCOL = new ProtocolId("http");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 30000;
    private static final long MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final HttpClient client;
    private final DestinationPolicy destinationPolicy;

    public HttpConnector() {
        this(DestinationPolicy.defaultPolicy());
    }

    public HttpConnector(DestinationPolicy destinationPolicy) {
        this.destinationPolicy = destinationPolicy;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public ProtocolId protocol() {
        return PROTOCOL;
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("http-get", "http-post", "http-put", "http-patch",
                "http-delete", "http-head", "http-options",
                "json-body", "empty-body");
    }

    @Override
    public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
        // Validate destination before preparing
        URI uri = URI.create(target.baseUrl());
        String policyError = destinationPolicy.check(uri);
        if (policyError != null) {
            throw new DestinationPolicyViolation(policyError);
        }
        return new HttpPreparedTarget(target, context);
    }

    @Override
    public OperationResult execute(
            PreparedTarget preparedTarget,
            OperationDefinition operation,
            Map<String, Object> inputs,
            ConnectorContext context
    ) {
        HttpPreparedTarget httpTarget = (HttpPreparedTarget) preparedTarget;
        long startTime = System.nanoTime();

        try {
            // Build the request URL
            String method = (String) operation.protocolMetadata().getOrDefault("method", "GET");
            String path = (String) operation.protocolMetadata().getOrDefault("path", "/");
            String url = buildUrl(httpTarget.targetDefinition().baseUrl(), path, inputs);

            // Validate the resolved URL against destination policy
            URI resolvedUri = URI.create(url);
            String policyError = destinationPolicy.check(resolvedUri);
            if (policyError != null) {
                NormalizedError error = new NormalizedError(
                        NormalizedError.ErrorCategory.POLICY_VIOLATION,
                        "DESTINATION_BLOCKED",
                        "Destination policy violation: " + policyError,
                        false,
                        Map.of("url", url)
                );
                return OperationResult.failure(error, 0);
            }

            // Enforce payload size limits (use config or default)
            long maxPayload = MAX_RESPONSE_BYTES;

            // Build the request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(resolvedUri)
                    .timeout(Duration.ofMillis(
                            context.requestTimeoutMs() > 0 ?
                                    context.requestTimeoutMs() : DEFAULT_REQUEST_TIMEOUT_MS));

            // Set method and body
            String body = buildBody(inputs);
            switch (method.toUpperCase()) {
                case "GET" -> requestBuilder.GET();
                case "DELETE" -> requestBuilder.DELETE();
                case "POST" -> requestBuilder.POST(bodyPublisher(body));
                case "PUT" -> requestBuilder.PUT(bodyPublisher(body));
                case "PATCH" -> requestBuilder.method("PATCH", bodyPublisher(body));
                case "HEAD" -> requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                default -> requestBuilder.method(method.toUpperCase(), bodyPublisher(body));
            }

            // Add headers
            addHeaders(requestBuilder, inputs, context);

            // Execute
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            long durationNs = System.nanoTime() - startTime;
            long durationMs = durationNs / 1_000_000;

            // Parse response
            int statusCode = response.statusCode();
            byte[] responseBytes = response.body() != null ?
                    response.body().getBytes(StandardCharsets.UTF_8) : new byte[0];

            // Enforce response size limit
            if (responseBytes.length > maxPayload) {
                NormalizedError error = new NormalizedError(
                        NormalizedError.ErrorCategory.POLICY_VIOLATION,
                        "RESPONSE_TOO_LARGE",
                        "Response exceeds maximum payload size of " + maxPayload + " bytes",
                        false,
                        Map.of("responseBytes", responseBytes.length)
                );
                return OperationResult.failure(error, durationMs);
            }

            // Parse JSON if possible
            JsonNode responseJson = null;
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (contentType.contains("json") && response.body() != null) {
                try {
                    responseJson = MAPPER.readTree(response.body());
                } catch (Exception ignored) {
                    // Not valid JSON
                }
            }

            // Build response headers map
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            response.headers().map().forEach((key, values) -> {
                if (key != null) {
                    responseHeaders.put(key.toLowerCase(), values);
                }
            });

            return OperationResult.success(
                    statusCode, responseHeaders, responseBytes,
                    durationMs, Map.of()
            );

        } catch (java.net.ConnectException e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.NETWORK, "CONNECTION_REFUSED",
                    "Connection refused: " + e.getMessage(), true, Map.of());
            return OperationResult.failure(error, durationMs);
        } catch (java.net.http.HttpTimeoutException e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.TIMEOUT, "REQUEST_TIMEOUT",
                    "Request timed out: " + e.getMessage(), true, Map.of());
            return OperationResult.failure(error, durationMs);
        } catch (javax.net.ssl.SSLException e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.NETWORK, "TLS_ERROR",
                    "TLS error: " + e.getMessage(), false, Map.of());
            return OperationResult.failure(error, durationMs);
        } catch (DestinationPolicyViolation e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.POLICY_VIOLATION,
                    "DESTINATION_BLOCKED",
                    e.getMessage(), false, Map.of());
            return OperationResult.failure(error, durationMs);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.INTERNAL, "EXECUTION_ERROR",
                    "Execution error: " + e.getMessage(), false, Map.of());
            return OperationResult.failure(error, durationMs);
        }
    }

    @Override
    public void release(PreparedTarget preparedTarget) {
        // Nothing to release for HTTP connections
    }

    @Override
    public void close() {
        // HttpClient doesn't require explicit close in Java 21
    }

    private String buildUrl(String baseUrl, String path, Map<String, Object> inputs) {
        // Replace path parameters
        String resolvedPath = path;
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            if (resolvedPath.contains(placeholder)) {
                resolvedPath = resolvedPath.replace(placeholder,
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }

        // Add query parameters
        StringBuilder url = new StringBuilder(baseUrl.endsWith("/") ?
                baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        url.append(resolvedPath.startsWith("/") ? resolvedPath : "/" + resolvedPath);

        // Collect query params (inputs not used in path)
        List<String> queryParams = new ArrayList<>();
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            if (!path.contains("{" + entry.getKey() + "}") &&
                    entry.getValue() != null &&
                    !entry.getKey().equals("body") &&
                    !entry.getKey().equals("headers")) {
                queryParams.add(entry.getKey() + "=" +
                        java.net.URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8));
            }
        }

        if (!queryParams.isEmpty()) {
            url.append("?").append(String.join("&", queryParams));
        }

        return url.toString();
    }

    private String buildBody(Map<String, Object> inputs) {
        Object body = inputs.get("body");
        if (body == null) return null;
        if (body instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return body.toString();
        }
    }

    private HttpRequest.BodyPublisher bodyPublisher(String body) {
        if (body == null || body.isEmpty()) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
    }

    private void addHeaders(HttpRequest.Builder builder, Map<String, Object> inputs,
                             ConnectorContext context) {
        // Default content type for bodies
        if (inputs.containsKey("body")) {
            builder.header("Content-Type", "application/json");
        }
        builder.header("Accept", "application/json");

        // Add user-specified headers
        Object headers = inputs.get("headers");
        if (headers instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> headerMap = (Map<String, Object>) headers;
            for (Map.Entry<String, Object> entry : headerMap.entrySet()) {
                if (entry.getValue() != null) {
                    builder.header(entry.getKey(), entry.getValue().toString());
                }
            }
        }
    }

    /**
     * Prepared target for HTTP operations.
     */
    private static class HttpPreparedTarget implements PreparedTarget {
        private final TargetDefinition target;
        private final ConnectorContext context;

        HttpPreparedTarget(TargetDefinition target, ConnectorContext context) {
            this.target = target;
            this.context = context;
        }

        @Override
        public TargetDefinition targetDefinition() {
            return target;
        }
    }
}
