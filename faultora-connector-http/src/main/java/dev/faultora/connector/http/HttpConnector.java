package dev.faultora.connector.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.security.SecretHandle;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;

import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
                .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
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
                .setConnectTimeout(Timeout.ofMilliseconds(DEFAULT_CONNECT_TIMEOUT_MS))
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
        return new HttpPreparedTarget(target, context, result);
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

            // Enforce payload size limits
            long maxPayload = MAX_RESPONSE_BYTES;

            // Build the ClassicHttpRequest with DNS pinning
            InetAddress[] pinnedAddresses = extractPinnedAddresses(policyResult);
            ClassicHttpRequest request = buildRequest(method, resolvedUri, pinnedAddresses, inputs, context);

            // Execute with manual redirect following
            URI currentUri = resolvedUri;
            ClassicHttpResponse response = null;
            int redirectCount = 0;

            while (true) {
                response = executeWithPinning(request, pinnedAddresses, context);

                int statusCode = response.getCode();
                if (statusCode < 300 || statusCode >= 400) {
                    break; // Not a redirect
                }

                Header locationHeader = response.getFirstHeader("location");
                if (locationHeader == null) {
                    break; // No location header, treat as final
                }

                // Close redirect response body to free connection
                try {
                    EntityUtils.consume(response.getEntity());
                } catch (IOException ignored) {}

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
                String redirectMethod;
                if (statusCode == 307 || statusCode == 308) {
                    redirectMethod = method;
                } else {
                    redirectMethod = "GET";
                }
                request = buildRequest(redirectMethod, redirectUri, pinnedAddresses, inputs, context);

                // Strip sensitive headers on cross-origin redirect
                if (crossOrigin) {
                    stripSensitiveHeaders(request);
                }

                currentUri = redirectUri;
            }

            long durationMs = (System.nanoTime() - startTime) / 1_000_000;

            // Parse response
            int statusCode = response.getCode();
            HttpEntity entity = response.getEntity();
            byte[] responseBytes = (entity != null)
                    ? EntityUtils.toByteArray(entity)
                    : new byte[0];
            EntityUtils.consumeQuietly(entity);

            // Enforce response size limit
            if (responseBytes.length > maxPayload) {
                NormalizedError error = new NormalizedError(
                        NormalizedError.ErrorCategory.POLICY_VIOLATION,
                        "RESPONSE_TOO_LARGE",
                        "Response exceeds maximum payload size of " + maxPayload + " bytes",
                        false, Map.of());
                return OperationResult.failure(error,
                        (System.nanoTime() - startTime) / 1_000_000);
            }

            // Parse JSON if possible
            JsonNode responseJson = null;
            String contentType = "";
            Header contentTypeHeader = response.getFirstHeader("content-type");
            if (contentTypeHeader != null) {
                contentType = contentTypeHeader.getValue();
            }
            if (contentType.contains("json") && responseBytes.length > 0) {
                try {
                    responseJson = MAPPER.readTree(responseBytes);
                } catch (Exception ignored) {
                    // Not valid JSON
                }
            }

            // Build response headers map
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            for (Header h : response.getHeaders()) {
                String key = h.getName().toLowerCase();
                responseHeaders.computeIfAbsent(key, k -> new ArrayList<>()).add(h.getValue());
            }

            return OperationResult.success(
                    statusCode, responseHeaders, responseBytes,
                    durationMs, Map.of());

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
            // Always clear pinned addresses from ThreadLocal to prevent leaks
            PINNED_ADDRESSES.remove();
        }
    }

    @Override
    public void release(PreparedTarget preparedTarget) {
        // Nothing to release for HTTP connections
    }

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
        try {
            // Build per-request config from ConnectorContext timeouts
            RequestConfig perRequestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(context.connectTimeoutMs()))
                    .setConnectTimeout(Timeout.ofMilliseconds(context.connectTimeoutMs()))
                    .setResponseTimeout(Timeout.ofMilliseconds(context.requestTimeoutMs()))
                    .setRedirectsEnabled(false)
                    .build();
            HttpClientContext httpContext = HttpClientContext.create();
            httpContext.setRequestConfig(perRequestConfig);
            return client.execute(request, httpContext);
        } finally {
            PINNED_ADDRESSES.remove();
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
            InetAddress[] pinnedAddresses,
            Map<String, Object> inputs,
            ConnectorContext context
    ) throws IOException {
        // Create the URI with original hostname (for Host header / SNI)
        String uriString = uri.toString();

        ClassicRequestBuilder builder = ClassicRequestBuilder.create(method.toUpperCase())
                .setUri(uriString);

        // Set method and body
        String body = buildBody(inputs);
        switch (method.toUpperCase()) {
            case "GET" -> {}
            case "DELETE" -> {}
            case "POST" -> builder.setEntity(bodyEntity(body));
            case "PUT" -> builder.setEntity(bodyEntity(body));
            case "PATCH" -> builder.setEntity(bodyEntity(body));
            case "HEAD" -> {}
            default -> builder.setEntity(bodyEntity(body));
        }

        // Add headers
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

        // Resolve and inject credentials from secret resolver.
        // If authSecretId is configured, auth is mandatory — failure to resolve
        // the secret fails the entire request (fail-closed).
        Function<String, SecretHandle> secretResolver = context.secretResolver();
        if (secretResolver != null) {
            Object authSecretId = context.config().get("authSecretId");
            if (authSecretId instanceof String secretId && !secretId.isBlank()) {
                SecretHandle handle = secretResolver.apply(secretId);
                if (handle == null) {
                    throw new IOException(
                            "Secret resolver returned null for: " + secretId);
                }
                if (handle.isExpired()) {
                    throw new IOException(
                            "Secret handle expired for: " + secretId);
                }
                char[] secretValue = handle.secretValue();
                if (secretValue == null || secretValue.length == 0) {
                    throw new IOException(
                            "Secret value is empty for: " + secretId);
                }
                try {
                    builder.addHeader("Authorization", "Bearer " + new String(secretValue));
                } finally {
                    // Zero the secret value immediately after use
                    Arrays.fill(secretValue, '\0');
                }
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

    private HttpEntity bodyEntity(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        return new StringEntity(body, ContentType.APPLICATION_JSON);
    }

    /**
     * Prepared target for HTTP operations.
     */
    private static class HttpPreparedTarget implements PreparedTarget {
        private final TargetDefinition target;
        private final ConnectorContext context;
        private final DestinationPolicy.CheckResult checkResult;

        HttpPreparedTarget(TargetDefinition target, ConnectorContext context,
                           DestinationPolicy.CheckResult checkResult) {
            this.target = target;
            this.context = context;
            this.checkResult = checkResult;
        }

        @Override
        public TargetDefinition targetDefinition() {
            return target;
        }
    }

    private static class ResponseTooLargeException extends Exception {
        ResponseTooLargeException() { super("Response too large"); }
    }
}
