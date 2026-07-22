package dev.faultora.connector.http;

import java.net.URI;
import java.util.Set;

/**
 * Enforces destination policy before HTTP connections.
 * Prevents SSRF by blocking private/reserved IP ranges and enforcing allowlists.
 */
public final class DestinationPolicy {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "0:0:0:0:0:0:0:1",
            "[::1]", "[0:0:0:0:0:0:0:1]"
    );

    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168.", "169.254."
    );

    private final boolean allowPrivateNetworks;
    private final Set<String> allowedHosts;
    private final Set<String> blockedHosts;

    public DestinationPolicy(
            boolean allowPrivateNetworks,
            Set<String> allowedHosts,
            Set<String> blockedHosts
    ) {
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.allowedHosts = allowedHosts != null ? Set.copyOf(allowedHosts) : Set.of();
        this.blockedHosts = blockedHosts != null ? Set.copyOf(blockedHosts) : Set.of();
    }

    /**
     * Check if a URI is allowed by this policy.
     *
     * @param uri the destination URI
     * @return null if allowed, or an error message if blocked
     */
    public String check(URI uri) {
        if (uri == null) return "URI must not be null";

        String scheme = uri.getScheme();
        if (scheme == null) return "URI has no scheme";
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "Unsupported scheme: " + scheme;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) return "URI has no host";

        // Check explicit blocklist
        if (blockedHosts.contains(host.toLowerCase())) {
            return "Host is explicitly blocked: " + host;
        }

        // Check explicit allowlist (if non-empty, only these hosts are allowed)
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase())) {
            return "Host is not in the allowlist: " + host;
        }

        // Check private/reserved networks
        if (!allowPrivateNetworks) {
            if (BLOCKED_HOSTS.contains(host.toLowerCase())) {
                return "Private/reserved host blocked: " + host;
            }
            for (String prefix : BLOCKED_PREFIXES) {
                if (host.startsWith(prefix)) {
                    return "Private/reserved network blocked: " + host;
                }
            }
        }

        // Check port
        int port = uri.getPort();
        if (port > 0 && (port < 1 || port > 65535)) {
            return "Invalid port: " + port;
        }

        return null; // allowed
    }

    /**
     * Create a default policy that blocks private networks.
     */
    public static DestinationPolicy defaultPolicy() {
        return new DestinationPolicy(false, Set.of(), Set.of());
    }

    /**
     * Create a permissive policy for testing.
     */
    public static DestinationPolicy permissive() {
        return new DestinationPolicy(true, Set.of(), Set.of());
    }
}
