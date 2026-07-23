package dev.faultora.connector.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Enforces destination policy before HTTP connections.
 * Prevents SSRF by blocking private/reserved IP ranges and enforcing allowlists.
 * DNS resolution is performed with a timeout to prevent blocking in DNS-restricted environments.
 * Resolved addresses are pinned to prevent DNS rebinding attacks.
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
            "172.30.", "172.31.", "192.168.", "169.254.",
            "100.64.", "100.65.", "100.66.", "100.67.",
            "100.68.", "100.69.", "100.70.", "100.71.",
            "100.72.", "100.73.", "100.74.", "100.75.",
            "100.76.", "100.77.", "100.78.", "100.79.",
            "100.80.", "100.81.", "100.82.", "100.83.",
            "100.84.", "100.85.", "100.86.", "100.87.",
            "100.88.", "100.89.", "100.90.", "100.91.",
            "100.92.", "100.93.", "100.94.", "100.95.",
            "100.96.", "100.97.", "100.98.", "100.99.",
            "100.100.", "100.101.", "100.102.", "100.103.",
            "100.104.", "100.105.", "100.106.", "100.107.",
            "100.108.", "100.109.", "100.110.", "100.111.",
            "100.112.", "100.113.", "100.114.", "100.115.",
            "100.116.", "100.117.", "100.118.", "100.119.",
            "100.120.", "100.121.", "100.122.", "100.123.",
            "100.124.", "100.125.", "100.126.", "100.127.",
            "198.18.", "198.19."
    );

    private static final long DNS_TIMEOUT_MS = 3000;
    private static final ExecutorService DNS_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dns-check");
        t.setDaemon(true);
        return t;
    });

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
     * Result of a destination policy check.
     * Carries the resolved addresses to prevent DNS rebinding — the connector
     * must use these addresses instead of re-resolving the hostname.
     */
    public sealed interface CheckResult {
        record Allowed(InetAddress[] resolvedAddresses) implements CheckResult {}
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

        // Check explicit blocklist
        if (blockedHosts.contains(host.toLowerCase())) {
            return new CheckResult.Blocked("Host is explicitly blocked: " + host);
        }

        // Check explicit allowlist (if non-empty, only these hosts are allowed)
        // Hosts in the allowlist are trusted and skip further private/reserved checks
        // including DNS resolution.
        if (!allowedHosts.isEmpty()) {
            if (allowedHosts.contains(host.toLowerCase())) {
                // For allowed hosts, resolve DNS to return pinned addresses
                InetAddress[] resolved = resolveHostQuietly(host);
                return new CheckResult.Allowed(resolved != null ? resolved : new InetAddress[0]);
            }
            return new CheckResult.Blocked("Host is not in the allowlist: " + host);
        }

        // Check private/reserved networks
        if (!allowPrivateNetworks) {
            if (BLOCKED_HOSTS.contains(host.toLowerCase())) {
                return new CheckResult.Blocked("Private/reserved host blocked: " + host);
            }
            for (String prefix : BLOCKED_PREFIXES) {
                if (host.startsWith(prefix)) {
                    return new CheckResult.Blocked("Private/reserved network blocked: " + host);
                }
            }

            // Classify IP literals by parsing to InetAddress and checking with
            // isPrivateOrReserved(). This catches addresses like 127.0.0.2, 224.0.0.1,
            // 240.0.0.1, IPv6 ULA, etc. that are not covered by string prefix checks.
            if (isIpLiteral(host)) {
                String ipError = classifyIpLiteral(host);
                if (ipError != null) {
                    return new CheckResult.Blocked(ipError);
                }
                // IP literal is public — parse and return as resolved address
                InetAddress addr = parseIpLiteral(host);
                if (addr != null) {
                    return new CheckResult.Allowed(new InetAddress[]{addr});
                }
                return new CheckResult.Blocked("Failed to parse IP literal: " + host);
            }

            // Resolve DNS and classify all resolved addresses.
            // Returns the verified addresses for connection pinning (prevents DNS rebinding).
            InetAddress[] resolved = resolveAndClassify(host);
            if (resolved == null) {
                // DNS resolution failed or resolved to private address — fail closed
                return new CheckResult.Blocked("DNS resolution failed or host resolves to private address: " + host);
            }
            return new CheckResult.Allowed(resolved);
        }

        // Check port
        int port = uri.getPort();
        if (port > 0 && (port < 1 || port > 65535)) {
            return new CheckResult.Blocked("Invalid port: " + port);
        }

        // Permissive mode — resolve DNS to return addresses for pinning
        InetAddress[] resolved = resolveHostQuietly(host);
        return new CheckResult.Allowed(resolved != null ? resolved : new InetAddress[0]);
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
     * Resolve hostname and check all addresses against private/reserved ranges.
     * Time-bounded to prevent blocking in DNS-restricted environments.
     * Fails closed: if DNS resolution cannot be verified, the request is blocked.
     *
     * @return resolved addresses if all are public, null if any is private/reserved or DNS failed
     */
    private static InetAddress[] resolveAndClassify(String host) {
        Future<InetAddress[]> future = DNS_EXECUTOR.submit(() -> InetAddress.getAllByName(host));
        try {
            InetAddress[] addresses = future.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            for (InetAddress addr : addresses) {
                if (isPrivateOrReserved(addr)) {
                    return null; // At least one address is private — fail closed
                }
            }
            return addresses;
        } catch (TimeoutException e) {
            // DNS timed out — fail closed. Cannot verify the resolved address
            // is safe, so block the request to prevent SSRF via slow DNS.
            future.cancel(true);
            return null;
        } catch (ExecutionException e) {
            // DNS lookup failed — fail closed.
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Resolve a hostname without classification. Returns null on failure.
     * Used for hosts that are already trusted (allowlist, permissive mode).
     */
    private static InetAddress[] resolveHostQuietly(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Classify an IP literal string by parsing it to InetAddress and checking
     * with isPrivateOrReserved(). Returns an error message if private/reserved,
     * null if the address is public.
     */
    private static String classifyIpLiteral(String host) {
        // Strip IPv6 brackets: [::1] → ::1
        String raw = host;
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }

        try {
            InetAddress addr = InetAddress.getByName(raw);
            if (isPrivateOrReserved(addr)) {
                return "Private/reserved IP literal blocked: " + host
                        + " (" + addr.getHostAddress() + ")";
            }
            return null;
        } catch (UnknownHostException e) {
            // Cannot parse — block to be safe
            return "Unparseable IP literal: " + host;
        }
    }

    /**
     * Parse an IP literal to an InetAddress. Returns null on failure.
     */
    private static InetAddress parseIpLiteral(String host) {
        String raw = host;
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        try {
            return InetAddress.getByName(raw);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Check if a resolved InetAddress is private/reserved.
     * Catches loopback, link-local, site-local, multicast,
     * IPv4-mapped private addresses, and documentation ranges.
     */
    static boolean isPrivateOrReserved(InetAddress addr) {
        if (addr.isLoopbackAddress()) return true;
        if (addr.isLinkLocalAddress()) return true;
        if (addr.isSiteLocalAddress()) return true;
        if (addr.isMulticastAddress()) return true;

        byte[] raw = addr.getAddress();

        // Check for 0.0.0.0 (unspecified)
        boolean allZero = true;
        for (byte b : raw) {
            if (b != 0) { allZero = false; break; }
        }
        if (allZero) return true;

        // IPv4-mapped IPv6 (::ffff:a.b.c.d) — check the embedded IPv4
        if (raw.length == 16) {
            boolean ipv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) { ipv4Mapped = false; break; }
            }
            if (ipv4Mapped && raw[10] == (byte) 0xff && raw[11] == (byte) 0xff) {
                InetAddress embedded = extractIpv4(raw);
                if (embedded != null && isPrivateOrReserved(embedded)) return true;
            }

            // IPv6 ULA (fc00::/7)
            if ((raw[0] & 0xfe) == 0xfc) return true;
        }

        // Documentation ranges: 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24
        String hostAddr = addr.getHostAddress();
        if (hostAddr.startsWith("192.0.2.") || hostAddr.startsWith("198.51.100.") ||
                hostAddr.startsWith("203.0.113.")) return true;

        // IPv4 reserved ranges not caught by InetAddress methods:
        // - 240.0.0.0/4 (reserved for future use, formerly Class E)
        // - 0.0.0.0/8 (current network, except 0.0.0.0 itself which is caught above)
        if (raw.length == 4) {
            int first = raw[0] & 0xFF;
            // 240.0.0.0/4: first octet 240-255
            if (first >= 240) return true;
            // 0.0.0.0/8: first octet 0 (but not all-zero, already checked)
            if (first == 0) return true;
        }

        return false;
    }

    private static InetAddress extractIpv4(byte[] raw) {
        try {
            return InetAddress.getByAddress(new byte[]{raw[12], raw[13], raw[14], raw[15]});
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Check if the host string is an IP address literal (v4 or v6).
     * IP literals are parsed directly to InetAddress for classification.
     */
    private static boolean isIpLiteral(String host) {
        // Strip IPv6 brackets
        String raw = host;
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        // IPv4: digits and dots only
        if (raw.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return true;
        // IPv6: contains colons
        if (raw.contains(":")) return true;
        return false;
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
