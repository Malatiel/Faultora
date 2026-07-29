package dev.faultora.connector.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.security.SecretHandle;
import dev.faultora.net.DestinationPolicyViolation;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;

import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * HTTP connector implementing the Connector SPI.
 * Executes operations against HTTP targets using Apache HttpClient 5.
 * <p>
 * DNS pinning uses a custom {@code DnsResolver} backed by a {@code ThreadLocal}
 * to route each connection to a pre-verified IP address. This prevents DNS
 * rebinding attacks for both HTTP and HTTPS targets. The original hostname is
 * preserved in the request URI so that:
 * <ul>
 *   <li>HTTP: the {@code Host} header is derived from the URI (virtual hosting)</li>
 *   <li>HTTPS: TLS SNI uses the hostname for certificate verification</li>
 * </ul>
 * Redirect handling compares full origin (scheme + host + port) to detect
 * HTTPS→HTTP downgrades and cross-domain redirects, stripping sensitive
 * headers (including {@code Authorization}) when the origin changes.
 */
public class HttpConnector implements Connector {

    private static final Logger LOG = LoggerFactory.getLogger(HttpConnector.class);

    private static final ProtocolId PROTOCOL = new ProtocolId("http");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 30000;
    private static final long MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final int MAX_REDIRECTS = 10;

    /**
     * Headers that must not be forwarded to a different origin on redirect.
     * Prevents credential leakage across scheme/host/port boundaries.
     */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "proxy-authorization",
            "www-authenticate", "x-api-key"
    );

    /**
     * Thread-local pinned addresses for DNS rebinding prevention.
     * Set before each request execution and cleared after.
     * The DnsResolver reads from this to return pre-verified addresses.
     */
    private static final ThreadLocal<InetAddress[]> PINNED_ADDRESSES = new ThreadLocal<>();
    private static final ThreadLocal<Timeout> CONNECT_TIMEOUT = new ThreadLocal<>();

    private final CloseableHttpClient client;
    private final DestinationPolicy destinationPolicy;

    public HttpConnector() {
        this(DestinationPolicy.defaultPolicy());
    }

    public HttpConnector(DestinationPolicy destinationPolicy) {
        this.destinationPolicy = destinationPolicy;
        this.client = createClient();
    }

    /**
     * Create an Apache HttpClient 5 with a custom DnsResolver that returns
     * pinned addresses from the ThreadLocal. When no addresses are pinned
     * (e.g., for non-pinned requests), falls back to system DNS resolution.
     */
    private static CloseableHttpClient createClient() {
        HttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create().buildClassic())
                .setConnectionConfigResolver(route -> ConnectionConfig.custom()
                        .setConnectTimeout(Optional.ofNullable(CONNECT_TIMEOUT.get())
                                .orElse(Timeout.ofMilliseconds(DEFAULT_CONNECT_TIMEOUT_MS)))
                        .build())
                .setDnsResolver(new DnsResolver() {
                    @Override
                    public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                        InetAddress[] pinned = PINNED_ADDRESSES.get();
                        if (pinned != null && pinned.length > 0) {
                            return pinned;
                        }
                        return InetAddress.getAllByName(host);
                    }

                    @Override
                    public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException {
                        InetAddress[] addresses = resolve(host);
                        return addresses.length > 0 ? addresses[0].getCanonicalHostName() : host;
                    }
                })
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(DEFAULT_CONNECT_TIMEOUT_MS))
                .setResponseTimeout(Timeout.ofMilliseconds(DEFAULT_REQUEST_TIMEOUT_MS))
                .setRedirectsEnabled(false) // Manual redirect handling
                .build();

        return HttpClients.custom()
                .setConnectionManager(connManager)
                .setDefaultRequestConfig(requestConfig)
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
        URI uri = URI.create(target.baseUrl());
        DestinationPolicy.CheckResult result = destinationPolicy.check(uri);
        if (!result.isAllowed()) {
            throw new DestinationPolicyViolation(result.errorMessage());
        }
        return new HttpPreparedTarget(target);
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
        ClassicHttpResponse response = null;

        try {
            // Build the request URL
            String method = (String) operation.protocolMetadata().getOrDefault("method", "GET");
            String path = (String) operation.protocolMetadata().getOrDefault("path", "/");
            String url = buildUrl(httpTarget.targetDefinition().baseUrl(), path, inputs);

            // Validate the resolved URL against destination policy (includes DNS resolution)
            URI resolvedUri = URI.create(url);
            DestinationPolicy.CheckResult policyResult = destinationPolicy.check(resolvedUri);
            if (!policyResult.isAllowed()) {
                NormalizedError error = new NormalizedError(
                        NormalizedError.ErrorCategory.POLICY_VIOLATION,
                        "DESTINATION_BLOCKED",
                        "Destination policy violation: " + policyResult.errorMessage(),
                        false,
                        Map.of("url", url)
                );
                return OperationResult.failure(error, 0);
            }

            InetAddress[] pinnedAddresses = extractPinnedAddresses(policyResult);
            String currentMethod = method;
            ClassicHttpRequest request = buildRequest(currentMethod, resolvedUri, inputs, context);

            // Execute with manual redirect following
            URI currentUri = resolvedUri;
            int redirectCount = 0;

            while (true) {
                response = executeWithPinning(request, pinnedAddresses, context);

                int statusCode = response.getCode();
                if (!isRedirectStatus(statusCode)) {
                    break;
                }

                Header locationHeader = response.getFirstHeader("location");
                if (locationHeader == null) {
                    break; // No location header, treat as final
                }

                // Close redirect response body to free connection
                try {
                    EntityUtils.consume(response.getEntity());
                } catch (IOException ignored) {}
                response.close();
                response = null;

                redirectCount++;
                if (redirectCount > MAX_REDIRECTS) {
                    NormalizedError error = new NormalizedError(
                            NormalizedError.ErrorCategory.POLICY_VIOLATION,
                            "TOO_MANY_REDIRECTS",
                            "Exceeded maximum redirect count (" + MAX_REDIRECTS + ")",
                            false, Map.of());
                    return OperationResult.failure(error,
                            (System.nanoTime() - startTime) / 1_000_000);
                }

                // Resolve redirect URI
                URI redirectUri = currentUri.resolve(locationHeader.getValue());
                String redirectScheme = redirectUri.getScheme();
                if (redirectScheme == null ||
                        (!"http".equals(redirectScheme) && !"https".equals(redirectScheme))) {
                    NormalizedError error = new NormalizedError(
                            NormalizedError.ErrorCategory.POLICY_VIOLATION,
                            "DESTINATION_BLOCKED",
                            "Redirect to unsupported scheme: " + redirectScheme,
                            false, Map.of());
                    return OperationResult.failure(error,
                            (System.nanoTime() - startTime) / 1_000_000);
                }

                // Re-check destination policy for each redirect hop
                DestinationPolicy.CheckResult redirectResult = destinationPolicy.check(redirectUri);
                if (!redirectResult.isAllowed()) {
                    NormalizedError error = new NormalizedError(
                            NormalizedError.ErrorCategory.POLICY_VIOLATION,
                            "DESTINATION_BLOCKED",
                            "Redirect destination policy violation: " + redirectResult.errorMessage(),
                            false, Map.of());
                    return OperationResult.failure(error,
                            (System.nanoTime() - startTime) / 1_000_000);
                }

                // Check for HTTPS→HTTP downgrade — must block credential forwarding
                boolean schemeDowngrade = "https".equals(currentUri.getScheme())
                        && "http".equals(redirectScheme);

                // Determine if this is a cross-origin redirect (scheme + host + port)
                boolean crossOrigin = !originEquals(currentUri, redirectUri);

                // Block HTTPS→HTTP downgrade entirely (redirect itself is rejected)
                if (schemeDowngrade) {
                    NormalizedError error = new NormalizedError(
                            NormalizedError.ErrorCategory.POLICY_VIOLATION,
                            "INSECURE_REDIRECT",
                            "HTTPS→HTTP downgrade blocked: " + currentUri + " → " + redirectUri,
                            false, Map.of());
                    return OperationResult.failure(error,
                            (System.nanoTime() - startTime) / 1_000_000);
                }

                // Build redirect request with DNS pinning
                pinnedAddresses = extractPinnedAddresses(redirectResult);
                String redirectMethod = redirectedMethod(statusCode, currentMethod);
                request = buildRequest(redirectMethod, redirectUri, inputs, context);

                // Strip sensitive headers on cross-origin redirect
                if (crossOrigin) {
                    stripSensitiveHeaders(request);
                }

                currentUri = redirectUri;
                currentMethod = redirectMethod;
            }

            long durationMs = (System.nanoTime() - startTime) / 1_000_000;

            int statusCode = response.getCode();
            HttpEntity entity = response.getEntity();
            byte[] responseBytes = readBounded(entity, maxResponseBytes(context));

            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            for (Header h : response.getHeaders()) {
                String key = h.getName().toLowerCase(Locale.ROOT);
                responseHeaders.computeIfAbsent(key, k -> new ArrayList<>()).add(h.getValue());
            }

            return OperationResult.success(
                    statusCode, responseHeaders, responseBytes,
                    durationMs, Map.of());

        } catch (ResponseTooLargeException e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.POLICY_VIOLATION,
                    "RESPONSE_TOO_LARGE",
                    "Response exceeds maximum payload size of " + e.maxBytes() + " bytes",
                    false, Map.of());
            return OperationResult.failure(error, durationMs);
        } catch (java.net.ConnectException e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.NETWORK, "CONNECTION_REFUSED",
                    "Connection refused: " + e.getMessage(), true, Map.of());
            return OperationResult.failure(error, durationMs);
        } catch (org.apache.hc.client5.http.ConnectTimeoutException
                 | org.apache.hc.core5.http.ConnectionRequestTimeoutException e) {
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
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException closeFailure) {
                    LOG.debug("Failed to close HTTP response: {}", closeFailure.getMessage());
                }
            }
            PINNED_ADDRESSES.remove();
            CONNECT_TIMEOUT.remove();
        }
    }

    @Override
    public void release(PreparedTarget preparedTarget) {}

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            LOG.warn("Failed to close HTTP client: {}", e.getMessage());
        }
    }

    // ---- DNS Pinning ----

    /**
     * Extract pinned addresses from a policy check result.
     * Returns null if no addresses are available (e.g., blocked result).
     */
    private static InetAddress[] extractPinnedAddresses(DestinationPolicy.CheckResult result) {
        if (result instanceof DestinationPolicy.CheckResult.Allowed a
                && a.resolvedAddresses() != null && a.resolvedAddresses().length > 0) {
            return a.resolvedAddresses();
        }
        return null;
    }

    /**
     * Execute a request with DNS pinning and per-request timeouts.
     * Sets the ThreadLocal pinned addresses before execution and clears them after.
     * The custom DnsResolver in the HttpClient reads from the ThreadLocal to
     * route the connection to the pre-verified IP addresses, preventing DNS rebinding.
     *
     * Per-request timeouts from {@link ConnectorContext} override the client defaults
     * via a per-execution {@link RequestConfig}.
     *
     * For HTTP: the original hostname stays in the URI, so HttpClient derives the
     * correct Host header from it. The connection goes to the pinned IP address.
     *
     * For HTTPS: the original hostname stays in the URI for TLS SNI and certificate
     * verification. The connection goes to the pinned IP address.
     */
    private ClassicHttpResponse executeWithPinning(
            ClassicHttpRequest request,
            InetAddress[] pinnedAddresses,
            ConnectorContext context
    ) throws IOException {
        if (pinnedAddresses != null && pinnedAddresses.length > 0) {
            PINNED_ADDRESSES.set(pinnedAddresses);
        }
        CONNECT_TIMEOUT.set(Timeout.ofMilliseconds(context.connectTimeoutMs()));
        try {
            // Build per-request config from ConnectorContext timeouts
            RequestConfig perRequestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(context.connectTimeoutMs()))
                    .setResponseTimeout(Timeout.ofMilliseconds(context.requestTimeoutMs()))
                    .setRedirectsEnabled(false)
                    .build();
            HttpClientContext httpContext = HttpClientContext.create();
            httpContext.setRequestConfig(perRequestConfig);
            return client.executeOpen(null, request, httpContext);
        } finally {
            PINNED_ADDRESSES.remove();
            CONNECT_TIMEOUT.remove();
        }
    }

    // ---- Request Building ----

    /**
     * Build a ClassicHttpRequest with DNS pinning awareness.
     * The original hostname is preserved in the URI for Host header (HTTP)
     * and TLS SNI (HTTPS). The DnsResolver handles routing to the pinned IP.
     */
    private ClassicHttpRequest buildRequest(
            String method,
            URI uri,
            Map<String, Object> inputs,
            ConnectorContext context
    ) throws IOException {
        ClassicRequestBuilder builder = ClassicRequestBuilder.create(method.toUpperCase())
                .setUri(uri);

        String body = buildBody(inputs);
        switch (method.toUpperCase()) {
            case "POST", "PUT", "PATCH" -> builder.setEntity(bodyEntity(body));
            case "GET", "DELETE", "HEAD", "OPTIONS" -> {}
            default -> throw new IOException("Unsupported HTTP method: " + method);
        }

        addHeaders(builder, inputs, context);

        return builder.build();
    }

    /**
     * Add headers to the request builder, including auth credentials.
     * When authSecretId is configured but secret resolution fails, throws
     * an exception to fail the request rather than sending it without credentials.
     * This is fail-closed: the user explicitly requested authenticated requests,
     * so proceeding without auth would violate that expectation.
     */
    private void addHeaders(
            ClassicRequestBuilder builder,
            Map<String, Object> inputs,
            ConnectorContext context
    ) throws IOException {
        // Default content type for bodies
        if (inputs.containsKey("body")) {
            builder.addHeader("Content-Type", "application/json");
        }
        builder.addHeader("Accept", "application/json");

        Object authSecretId = context.config().get("authSecretId");
        if (authSecretId instanceof String secretId && !secretId.isBlank()) {
            Function<String, SecretHandle> secretResolver = context.secretResolver();
            if (secretResolver == null) {
                throw new IOException("No secret resolver configured for: " + secretId);
            }
            SecretHandle handle = secretResolver.apply(secretId);
            if (handle == null) {
                throw new IOException("Secret resolver returned null for: " + secretId);
            }
            if (handle.isExpired()) {
                throw new IOException("Secret handle expired for: " + secretId);
            }
            char[] secretValue = handle.secretValue();
            if (secretValue == null || secretValue.length == 0) {
                throw new IOException("Secret value is empty for: " + secretId);
            }
            try {
                builder.addHeader("Authorization", "Bearer " + new String(secretValue));
            } finally {
                Arrays.fill(secretValue, '\0');
            }
        }

        // Add user-specified headers
        Object headers = inputs.get("headers");
        if (headers instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> headerMap = (Map<String, Object>) headers;
            for (Map.Entry<String, Object> entry : headerMap.entrySet()) {
                if (entry.getValue() != null) {
                    builder.addHeader(entry.getKey(), entry.getValue().toString());
                }
            }
        }
    }

    /**
     * Strip sensitive headers from a request to prevent credential leakage
     * on cross-origin redirects. Apache HttpClient 5 supports removing headers.
     */
    private static void stripSensitiveHeaders(ClassicHttpRequest request) {
        for (String headerName : SENSITIVE_HEADERS) {
            request.removeHeaders(headerName);
        }
    }

    // ---- Origin Comparison ----

    /**
     * Compare two URIs by origin (scheme + host + port).
     * Returns true if the origins are equal, meaning credentials may be forwarded.
     * A scheme downgrade (HTTPS→HTTP) is always considered a different origin.
     */
    static boolean originEquals(URI a, URI b) {
        if (!Objects.equals(
                Optional.ofNullable(a.getScheme()).orElse(""),
                Optional.ofNullable(b.getScheme()).orElse(""))) {
            return false;
        }
        String hostA = Optional.ofNullable(a.getHost()).orElse("").toLowerCase();
        String hostB = Optional.ofNullable(b.getHost()).orElse("").toLowerCase();
        if (!Objects.equals(hostA, hostB)) {
            return false;
        }
        int portA = effectivePort(a);
        int portB = effectivePort(b);
        return portA == portB;
    }

    /**
     * Apply RFC redirect method semantics to the method of the current hop.
     * In particular, a POST changed to GET by a 301/302 must stay GET if a
     * later hop returns 307/308.
     */
    static String redirectedMethod(int statusCode, String currentMethod) {
        String normalized = currentMethod.toUpperCase(Locale.ROOT);
        return switch (statusCode) {
            case 303 -> "HEAD".equals(normalized) ? "HEAD" : "GET";
            case 301, 302 -> "POST".equals(normalized) ? "GET" : normalized;
            case 307, 308 -> normalized;
            default -> normalized;
        };
    }

    static boolean isRedirectStatus(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    /**
     * Get the effective port of a URI, using the default port for the scheme
     * if no explicit port is specified.
     */
    private static int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return switch (Optional.ofNullable(uri.getScheme()).orElse("")) {
            case "https" -> 443;
            case "http" -> 80;
            default -> -1;
        };
    }

    // ---- Helpers ----

    private String buildUrl(String baseUrl, String path, Map<String, Object> inputs) {
        String resolvedPath = path;
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            if (resolvedPath.contains(placeholder)) {
                resolvedPath = resolvedPath.replace(placeholder,
                        entry.getValue() != null ? encodePathSegment(entry.getValue().toString()) : "");
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
                queryParams.add(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" +
                        java.net.URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8));
            }
        }

        if (!queryParams.isEmpty()) {
            url.append("?").append(String.join("&", queryParams));
        }

        return url.toString();
    }

    private static String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String buildBody(Map<String, Object> inputs) throws IOException {
        Object body = inputs.get("body");
        if (body == null) return null;
        if (body instanceof String s) return s;
        return MAPPER.writeValueAsString(body);
    }

    private static long maxResponseBytes(ConnectorContext context) {
        Object configured = context.config().get("maxResponseBytes");
        if (configured == null) {
            return MAX_RESPONSE_BYTES;
        }
        if (!(configured instanceof Number number)) {
            throw new IllegalArgumentException("maxResponseBytes must be numeric");
        }
        long value = number.longValue();
        if (value <= 0 || value > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be between 1 and " + MAX_RESPONSE_BYTES);
        }
        return value;
    }

    private HttpEntity bodyEntity(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        return new StringEntity(body, ContentType.APPLICATION_JSON);
    }

    /**
     * Read an HTTP entity without ever buffering more than {@code maxBytes}.
     * Content length is checked first when supplied by the server, but the
     * streaming limit remains authoritative because that header is untrusted
     * and may be absent or incorrect.
     */
    static byte[] readBounded(HttpEntity entity, long maxBytes)
            throws IOException, ResponseTooLargeException {
        if (entity == null) {
            return new byte[0];
        }
        if (maxBytes < 0 || maxBytes > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("maxBytes must be between 0 and Integer.MAX_VALUE - 1");
        }
        if (entity.getContentLength() > maxBytes) {
            EntityUtils.consumeQuietly(entity);
            throw new ResponseTooLargeException(maxBytes);
        }

        int initialCapacity = entity.getContentLength() > 0
                ? (int) Math.min(entity.getContentLength(), maxBytes)
                : (int) Math.min(8192, maxBytes);
        try (InputStream input = entity.getContent();
             ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            while (true) {
                long remaining = maxBytes - total;
                int requested = (int) Math.min(buffer.length, remaining + 1);
                int read = input.read(buffer, 0, requested);
                if (read == -1) {
                    return output.toByteArray();
                }
                if (read > remaining) {
                    throw new ResponseTooLargeException(maxBytes);
                }
                output.write(buffer, 0, read);
                total += read;
            }
        } finally {
            EntityUtils.consumeQuietly(entity);
        }
    }

    /**
     * Prepared target for HTTP operations.
     */
    private static class HttpPreparedTarget implements PreparedTarget {
        private final TargetDefinition target;

        HttpPreparedTarget(TargetDefinition target) {
            this.target = target;
        }

        @Override
        public TargetDefinition targetDefinition() {
            return target;
        }
    }

    static final class ResponseTooLargeException extends Exception {
        private final long maxBytes;

        ResponseTooLargeException(long maxBytes) {
            super("Response exceeds " + maxBytes + " bytes");
            this.maxBytes = maxBytes;
        }

        long maxBytes() {
            return maxBytes;
        }
    }
}
