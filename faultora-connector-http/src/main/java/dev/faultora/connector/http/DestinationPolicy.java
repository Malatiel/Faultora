package dev.faultora.connector.http;

import dev.faultora.net.HostPolicy;

import java.net.InetAddress;
import java.net.URI;

import java.util.Set;

/**
 * Enforces destination policy before HTTP connections.
 * <p>
 * What is specific to HTTP lives here: the scheme must be one this connector
 * speaks, a URI must not carry credentials, and a port must be a port. The host
 * decision itself — allowlists, blocklists, and refusal of private and reserved
 * ranges — belongs to {@link HostPolicy}, which every connector shares, because
 * a bootstrap server is as reachable a destination as a base URL.
 * <p>
 * The verified addresses come back with the decision so the connector can pin
 * the connection to them, which is what makes a name that resolves differently
 * on the second lookup unable to reach anywhere new.
 */
public final class DestinationPolicy {

    private final HostPolicy hostPolicy;

    public DestinationPolicy(
            boolean allowPrivateNetworks,
            Set<String> allowedHosts,
            Set<String> blockedHosts
    ) {
        this.hostPolicy = new HostPolicy(allowPrivateNetworks, allowedHosts, blockedHosts);
    }

    /**
     * Result of a destination policy check.
     * Carries the resolved addresses to prevent DNS rebinding — the connector
     * must use these addresses instead of re-resolving the hostname.
     */
    public sealed interface CheckResult {
        record Allowed(InetAddress[] resolvedAddresses) implements CheckResult {
            public Allowed {
                resolvedAddresses = resolvedAddresses == null
                        ? new InetAddress[0]
                        : resolvedAddresses.clone();
            }

            @Override
            public InetAddress[] resolvedAddresses() {
                return resolvedAddresses.clone();
            }
        }
        record Blocked(String reason) implements CheckResult {}

        default boolean isAllowed() {
            return this instanceof Allowed;
        }

        default String errorMessage() {
            return this instanceof Blocked b ? b.reason() : null;
        }

        /**
         * Returns the first resolved address, or null if blocked.
         */
        default InetAddress resolvedAddress() {
            return this instanceof Allowed a && a.resolvedAddresses().length > 0
                    ? a.resolvedAddresses()[0] : null;
        }
    }

    /**
     * Check if a URI is allowed by this policy.
     * Returns a {@link CheckResult} carrying the resolved (verified) addresses
     * so the connector can pin the connection to those addresses.
     *
     * @param uri the destination URI
     * @return CheckResult.Allowed with resolved addresses if allowed, or Blocked with reason
     */
    public CheckResult check(URI uri) {
        if (uri == null) return new CheckResult.Blocked("URI must not be null");

        String scheme = uri.getScheme();
        if (scheme == null) return new CheckResult.Blocked("URI has no scheme");
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return new CheckResult.Blocked("Unsupported scheme: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) return new CheckResult.Blocked("URI has no host");
        if (uri.getRawUserInfo() != null) {
            return new CheckResult.Blocked("Credentials in destination URI are not allowed");
        }

        int port = uri.getPort();
        if (port > 65535) {
            return new CheckResult.Blocked("Invalid port: " + port);
        }

        return switch (hostPolicy.check(host)) {
            case HostPolicy.Decision.Reachable reachable ->
                    new CheckResult.Allowed(reachable.addresses());
            case HostPolicy.Decision.Refused refused ->
                    new CheckResult.Blocked(refused.reason());
        };
    }

    /**
     * Backward-compatible check that returns null if allowed, error message if blocked.
     * Prefer {@link #check(URI)} for new code to get resolved addresses.
     */
    public String checkLegacy(URI uri) {
        CheckResult result = check(uri);
        return result instanceof CheckResult.Blocked b ? b.reason() : null;
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
