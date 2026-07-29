package dev.faultora.faults.toxiproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.result.ActiveFault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fault provider that injects real network faults through a
 * <a href="https://github.com/Shopify/toxiproxy">Toxiproxy</a> instance.
 * <p>
 * The fault step's {@code targetScope} names the Toxiproxy proxy to poison;
 * a concrete proxy name is required — network faults never apply to {@code *}.
 * That name is scenario input reaching a management API, so it is
 * percent-encoded into the request path: a name containing a slash addresses
 * the proxy that bears it, and can never reach a different admin resource.
 * <p>
 * Toxics are created with names unique to this provider instance, which is one
 * per run, so a toxic leaked by a previous run (e.g. after a JVM crash, which
 * the in-process watchdog cannot survive) can never collide with the names this
 * run creates. The run token in the name is what makes a leak identifiable and
 * removable with {@code toxiproxy-cli}. The admin endpoint is operator-supplied
 * configuration, not scenario input.
 */
public final class ToxiproxyFaultProvider implements FaultProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ToxiproxyFaultProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Adds latency on the proxied connection. Params: {@code latencyMs}, optional {@code jitterMs}, {@code direction}. */
    public static final String NETWORK_LATENCY = "network-latency";
    /** Stops all data and closes the connection after a delay. Params: {@code timeoutMs}, optional {@code direction}. */
    public static final String NETWORK_TIMEOUT = "network-timeout";
    /** Resets the TCP connection. Params: optional {@code timeoutMs}, {@code direction}. */
    public static final String NETWORK_RESET = "network-reset";
    /** Limits connection bandwidth. Params: {@code rateKbps}, optional {@code direction}. */
    public static final String NETWORK_BANDWIDTH = "network-bandwidth";

    private static final Set<String> CAPABILITIES = Set.of(
            NETWORK_LATENCY, NETWORK_TIMEOUT, NETWORK_RESET, NETWORK_BANDWIDTH);

    private record ActiveToxic(String proxyName, String toxicName) {}

    private final URI adminBaseUrl;
    private final HttpClient client;
    private final ConcurrentMap<String, ActiveToxic> activeToxics = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    /** Distinguishes this run's toxics from any a previous run left behind. */
    private final String runToken;

    public ToxiproxyFaultProvider(URI adminBaseUrl) {
        this.adminBaseUrl = adminBaseUrl;
        this.runToken = UUID.randomUUID().toString().substring(0, 8);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public Set<String> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public ActiveFault inject(String faultType, Map<String, Object> params, FaultContext context) {
        if (!CAPABILITIES.contains(faultType)) {
            throw new IllegalArgumentException("Unsupported fault type: " + faultType);
        }
        String proxyName = context.targetScope();
        if (proxyName == null || proxyName.isBlank() || "*".equals(proxyName)) {
            throw new IllegalArgumentException(
                    "Network faults require targetScope to name a Toxiproxy proxy");
        }
        Map<String, Object> safeParams = params == null ? Map.of() : Map.copyOf(params);

        String toxicName =
                "faultora-" + faultType + "-" + runToken + "-" + sequence.incrementAndGet();
        ObjectNode toxic = MAPPER.createObjectNode();
        toxic.put("name", toxicName);
        toxic.put("stream", direction(safeParams));
        toxic.put("toxicity", 1.0);
        ObjectNode attributes = toxic.putObject("attributes");

        switch (faultType) {
            case NETWORK_LATENCY -> {
                long latencyMs = requirePositive(safeParams, "latencyMs");
                toxic.put("type", "latency");
                attributes.put("latency", latencyMs);
                attributes.put("jitter", toLong(safeParams.get("jitterMs"), 0));
            }
            case NETWORK_TIMEOUT -> {
                long timeoutMs = requirePositive(safeParams, "timeoutMs");
                toxic.put("type", "timeout");
                attributes.put("timeout", timeoutMs);
            }
            case NETWORK_RESET -> {
                toxic.put("type", "reset_peer");
                attributes.put("timeout", toLong(safeParams.get("timeoutMs"), 0));
            }
            case NETWORK_BANDWIDTH -> {
                long rateKbps = requirePositive(safeParams, "rateKbps");
                toxic.put("type", "bandwidth");
                attributes.put("rate", rateKbps);
            }
            default -> throw new IllegalArgumentException("Unsupported fault type: " + faultType);
        }

        postToxic(proxyName, toxic);

        // Register before reporting success so rollback can always find it.
        activeToxics.put(toxicName, new ActiveToxic(proxyName, toxicName));

        long now = System.currentTimeMillis();
        long activatedAtMs = Math.min(now, context.hardExpiryMs() - 1);
        return new ActiveFault(
                toxicName, faultType, proxyName,
                activatedAtMs, context.hardExpiryMs(),
                "DELETE toxic " + toxicName + " from proxy " + proxyName);
    }

    @Override
    public void rollback(ActiveFault fault, FaultContext context) {
        ActiveToxic toxic = activeToxics.remove(fault.handle());
        if (toxic == null) {
            return;
        }
        URI uri = adminBaseUrl.resolve("/proxies/" + encodeSegment(toxic.proxyName())
                + "/toxics/" + encodeSegment(toxic.toxicName()));
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(5))
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            // 404 means the toxic is already gone — rollback is idempotent.
            if (response.statusCode() >= 300 && response.statusCode() != 404) {
                throw new IllegalStateException("Toxiproxy admin returned "
                        + response.statusCode() + " deleting toxic " + toxic.toxicName());
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Toxiproxy admin unreachable while deleting toxic "
                            + toxic.toxicName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while deleting toxic "
                    + toxic.toxicName(), e);
        }
    }

    /** Number of toxics this provider still tracks (for tests and diagnostics). */
    public int activeCount() {
        return activeToxics.size();
    }

    private void postToxic(String proxyName, ObjectNode toxic) {
        URI uri = adminBaseUrl.resolve("/proxies/" + encodeSegment(proxyName) + "/toxics");
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    MAPPER.writeValueAsString(toxic)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new IllegalArgumentException(
                        "Toxiproxy has no proxy named '" + proxyName
                                + "' (targetScope must match an existing proxy)");
            }
            if (response.statusCode() >= 300) {
                throw new IllegalArgumentException("Toxiproxy admin rejected toxic: HTTP "
                        + response.statusCode() + " " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Toxiproxy admin unreachable at " + adminBaseUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating toxic", e);
        }
    }

    /**
     * One path segment of an admin URL, with everything outside the unreserved
     * set percent-encoded.
     * <p>
     * The proxy name comes from the scenario. Interpolated raw, a name
     * containing {@code /}, {@code ?} or {@code #} would address a different
     * admin resource than the one it names — or make the URL unparseable.
     * Encoded, it addresses exactly the proxy the scenario asked for, whatever
     * characters that proxy's name happens to contain.
     */
    static String encodeSegment(String segment) {
        StringBuilder encoded = new StringBuilder(segment.length());
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int octet = b & 0xFF;
            if (isUnreserved(octet)) {
                encoded.append((char) octet);
            } else {
                encoded.append('%').append(String.format("%02X", octet));
            }
        }
        return encoded.toString();
    }

    /** RFC 3986 unreserved characters, which never need escaping. */
    private static boolean isUnreserved(int octet) {
        return (octet >= 'a' && octet <= 'z')
                || (octet >= 'A' && octet <= 'Z')
                || (octet >= '0' && octet <= '9')
                || octet == '-' || octet == '.' || octet == '_' || octet == '~';
    }

    private static String direction(Map<String, Object> params) {
        Object direction = params.get("direction");
        if (direction == null) {
            return "downstream";
        }
        String value = direction.toString().trim().toLowerCase();
        if (!"downstream".equals(value) && !"upstream".equals(value)) {
            throw new IllegalArgumentException(
                    "direction must be 'upstream' or 'downstream', got: " + direction);
        }
        return value;
    }

    private static long requirePositive(Map<String, Object> params, String key) {
        long value = toLong(params.get(key), -1);
        if (value <= 0) {
            throw new IllegalArgumentException(
                    key + " must be a positive number, got: " + params.get(key));
        }
        return value;
    }

    private static long toLong(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
