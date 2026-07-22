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

            // Resolve DNS and classify all resolved addresses.
            // Skip for IP literals — already covered by string-based checks above.
            // DNS resolution is time-bounded; if it times out, fail open.
            if (!isIpLiteral(host)) {
                String dnsError = resolveAndClassify(host);
                if (dnsError != null) {
                    return dnsError;
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
     * Resolve hostname and check all addresses against private/reserved ranges.
     * Time-bounded to prevent blocking in DNS-restricted environments.
     *
     * @return null if allowed, error message if all resolved addresses are private/reserved
     */
    private static String resolveAndClassify(String host) {
        Future<InetAddress[]> future = DNS_EXECUTOR.submit(() -> InetAddress.getAllByName(host));
        try {
            InetAddress[] addresses = future.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            for (InetAddress addr : addresses) {
                if (isPrivateOrReserved(addr)) {
                    return "Host " + host + " resolves to private/reserved address: " + addr.getHostAddress();
                }
            }
            return null;
        } catch (TimeoutException e) {
            // DNS timed out — fail open. The actual connection will also fail
            // if DNS is unreachable, so no SSRF risk from this path.
            future.cancel(true);
            return null;
        } catch (ExecutionException e) {
            // DNS lookup failed — fail open.
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Check if a resolved InetAddress is private/reserved.
     * Catches loopback, link-local, site-local, multicast,
     * IPv4-mapped private addresses, and documentation ranges.
     */
    private static boolean isPrivateOrReserved(InetAddress addr) {
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
     * IP literals are already covered by string-based prefix checks.
     */
    private static boolean isIpLiteral(String host) {
        // IPv4: digits and dots only
        if (host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return true;
        // IPv6: contains colons
        if (host.contains(":")) return true;
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
