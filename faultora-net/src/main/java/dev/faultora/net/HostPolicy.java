package dev.faultora.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides whether a run may reach a host, and with which addresses.
 * <p>
 * This is the one place the destination rule lives. Every connector faces the
 * same question — a bootstrap server is as good an SSRF target as a base URL —
 * and a second copy of the rule would be a second thing to keep correct.
 * Protocol-specific checks (schemes, credentials in a URI, ports) stay with the
 * connector that understands them; what is shared is the host decision itself.
 * <p>
 * The order of the rules is the policy:
 * <ol>
 *   <li>an explicitly blocked host is refused, whatever else says;</li>
 *   <li>when an allowlist exists it decides alone — a host on it is reached
 *       without classification, and a host off it is refused. An operator who
 *       allowlists an internal host means that host, and refusing it for being
 *       internal would make the allowlist useless in the private networks this
 *       tool is built to run in. The list is operator-supplied and never
 *       scenario-supplied, which is what makes this safe;</li>
 *   <li>otherwise every address the host resolves to is classified, and one
 *       private, loopback, link-local or otherwise reserved address refuses the
 *       whole host;</li>
 *   <li>a policy that allows private networks skips classification entirely.
 *       That is for a target you own.</li>
 * </ol>
 * Classification fails closed: a name that cannot be resolved, or resolution
 * that outlives its timeout, is a refusal rather than a connection attempt.
 * <p>
 * The addresses that passed are returned so a caller can pin its connection to
 * them. A caller that cannot pin — because its client library resolves for
 * itself — still gets the refusal, but not the protection against a name that
 * resolves differently the second time.
 */
public final class HostPolicy {

    /** Names that always mean the machine the run is on. */
    private static final Set<String> LOCAL_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "0:0:0:0:0:0:0:1",
            "[::1]", "[0:0:0:0:0:0:0:1]"
    );

    private static final long DNS_TIMEOUT_MS = 3000;
    private static final ExecutorService DNS_EXECUTOR = new ThreadPoolExecutor(
            2,
            4,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            runnable -> {
                Thread thread = new Thread(runnable, "faultora-dns-check");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** What the policy decided about one host. */
    public sealed interface Decision {

        /** The host may be reached, at these verified addresses. */
        record Reachable(InetAddress[] addresses) implements Decision {
            public Reachable {
                addresses = addresses == null ? new InetAddress[0] : addresses.clone();
            }

            @Override
            public InetAddress[] addresses() {
                return addresses.clone();
            }
        }

        /** The host may not be reached, and this is why. */
        record Refused(String reason) implements Decision {}

        default boolean isReachable() {
            return this instanceof Reachable;
        }

        /** Why the host was refused, or null when it was not. */
        default String reason() {
            return this instanceof Refused refused ? refused.reason() : null;
        }
    }

    private final boolean allowPrivateNetworks;
    private final Set<String> allowedHosts;
    private final Set<String> blockedHosts;

    public HostPolicy(
            boolean allowPrivateNetworks,
            Set<String> allowedHosts,
            Set<String> blockedHosts
    ) {
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.allowedHosts = allowedHosts != null ? Set.copyOf(allowedHosts) : Set.of();
        this.blockedHosts = blockedHosts != null ? Set.copyOf(blockedHosts) : Set.of();
    }

    /** The policy a run gets when the operator asks for nothing: no private networks. */
    public static HostPolicy defaultPolicy() {
        return new HostPolicy(false, Set.of(), Set.of());
    }

    /** The policy for a target you own, where private addresses are the point. */
    public static HostPolicy permissive() {
        return new HostPolicy(true, Set.of(), Set.of());
    }

    /** Whether this policy reaches private and reserved addresses. */
    public boolean allowsPrivateNetworks() {
        return allowPrivateNetworks;
    }

    /**
     * Decide about one host name or IP literal.
     *
     * @param host a host name, an IPv4 literal, or an IPv6 literal with or
     *             without its brackets
     */
    public Decision check(String host) {
        if (host == null || host.isBlank()) {
            return new Decision.Refused("No host to check");
        }
        if (blockedHosts.contains(host.toLowerCase())) {
            return new Decision.Refused("Host is explicitly blocked: " + host);
        }

        if (!allowedHosts.isEmpty()) {
            if (!allowedHosts.contains(host.toLowerCase())) {
                return new Decision.Refused("Host is not in the allowlist: " + host);
            }
            InetAddress[] resolved = resolveHostWithTimeout(host);
            return resolved == null
                    ? new Decision.Refused("DNS resolution failed for allowlisted host: " + host)
                    : new Decision.Reachable(resolved);
        }

        if (allowPrivateNetworks) {
            InetAddress[] resolved = resolveHostQuietly(host);
            return new Decision.Reachable(resolved != null ? resolved : new InetAddress[0]);
        }

        if (LOCAL_HOSTS.contains(host.toLowerCase())) {
            return new Decision.Refused("Private/reserved host blocked: " + host);
        }
        if (isIpLiteral(host)) {
            String refusal = classifyIpLiteral(host);
            if (refusal != null) {
                return new Decision.Refused(refusal);
            }
            InetAddress literal = parseIpLiteral(host);
            return literal != null
                    ? new Decision.Reachable(new InetAddress[]{literal})
                    : new Decision.Refused("Failed to parse IP literal: " + host);
        }

        InetAddress[] resolved = resolveAndClassify(host);
        return resolved == null
                ? new Decision.Refused(
                        "DNS resolution failed or host resolves to private address: " + host)
                : new Decision.Reachable(resolved);
    }

    /**
     * Resolve a host and check every address against the reserved ranges.
     * Fails closed: unverifiable resolution refuses the host.
     *
     * @return the addresses when all are public, null otherwise
     */
    private static InetAddress[] resolveAndClassify(String host) {
        InetAddress[] addresses = resolveHostWithTimeout(host);
        if (addresses == null) {
            return null;
        }
        for (InetAddress address : addresses) {
            if (isPrivateOrReserved(address)) {
                return null;
            }
        }
        return addresses;
    }

    /**
     * Resolve within a bound. A DNS server that never answers must not hold a
     * run open, which it would if resolution were left to block.
     */
    private static InetAddress[] resolveHostWithTimeout(String host) {
        Future<InetAddress[]> resolution;
        try {
            resolution = DNS_EXECUTOR.submit(() -> InetAddress.getAllByName(host));
        } catch (RejectedExecutionException overloaded) {
            return null;
        }
        try {
            return resolution.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException tooSlow) {
            resolution.cancel(true);
            return null;
        } catch (ExecutionException unresolvable) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Resolve without classifying, for a host the policy already trusts. */
    private static InetAddress[] resolveHostQuietly(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException unresolvable) {
            return null;
        }
    }

    /**
     * @return why this literal is refused, or null when it is a public address
     */
    private static String classifyIpLiteral(String host) {
        try {
            InetAddress address = InetAddress.getByName(unbracket(host));
            if (isPrivateOrReserved(address)) {
                return "Private/reserved IP literal blocked: " + host
                        + " (" + address.getHostAddress() + ")";
            }
            return null;
        } catch (UnknownHostException unparseable) {
            // Not understood is not the same as public: refuse it.
            return "Unparseable IP literal: " + host;
        }
    }

    private static InetAddress parseIpLiteral(String host) {
        try {
            return InetAddress.getByName(unbracket(host));
        } catch (UnknownHostException unparseable) {
            return null;
        }
    }

    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    /**
     * Whether a resolved address belongs to a range a run must not reach:
     * loopback, link-local, site-local, multicast, IPv4-mapped private
     * addresses, and the documentation and reserved ranges the JDK's own
     * predicates miss.
     */
    public static boolean isPrivateOrReserved(InetAddress address) {
        if (address.isLoopbackAddress()) return true;
        if (address.isLinkLocalAddress()) return true;
        if (address.isSiteLocalAddress()) return true;
        if (address.isMulticastAddress()) return true;

        byte[] raw = address.getAddress();

        boolean allZero = true;
        for (byte octet : raw) {
            if (octet != 0) { allZero = false; break; }
        }
        if (allZero) return true;

        if (raw.length == 16) {
            // IPv4-mapped IPv6 (::ffff:a.b.c.d) — classify the embedded IPv4
            boolean ipv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) { ipv4Mapped = false; break; }
            }
            if (ipv4Mapped && raw[10] == (byte) 0xff && raw[11] == (byte) 0xff) {
                InetAddress embedded = extractIpv4(raw);
                if (embedded != null && isPrivateOrReserved(embedded)) return true;
            }

            // IPv6 unique local addresses (fc00::/7)
            if ((raw[0] & 0xfe) == 0xfc) return true;

            // IPv6 discard-only prefix (100::/64)
            boolean discardOnly = raw[0] == 0x01 && raw[1] == 0x00;
            for (int i = 2; discardOnly && i < 8; i++) {
                discardOnly = raw[i] == 0;
            }
            if (discardOnly) return true;

            // IPv6 documentation prefix (2001:db8::/32)
            if ((raw[0] & 0xff) == 0x20 && (raw[1] & 0xff) == 0x01
                    && (raw[2] & 0xff) == 0x0d && (raw[3] & 0xff) == 0xb8) {
                return true;
            }
        }

        // Documentation ranges: 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24
        String literal = address.getHostAddress();
        if (literal.startsWith("192.0.2.") || literal.startsWith("198.51.100.")
                || literal.startsWith("203.0.113.")) {
            return true;
        }

        if (raw.length == 4) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            // 240.0.0.0/4: reserved for future use
            if (first >= 240) return true;
            // 0.0.0.0/8: this network — the all-zero address is caught above
            if (first == 0) return true;
            // 100.64.0.0/10: shared address space (CGNAT)
            if (first == 100 && second >= 64 && second <= 127) return true;
            // 192.0.0.0/24: IETF protocol assignments
            if (first == 192 && second == 0 && (raw[2] & 0xFF) == 0) return true;
            // 192.88.99.0/24: deprecated 6to4 relay anycast
            if (first == 192 && second == 88 && (raw[2] & 0xFF) == 99) return true;
            // 198.18.0.0/15: network benchmark testing
            if (first == 198 && (second == 18 || second == 19)) return true;
        }

        return false;
    }

    private static InetAddress extractIpv4(byte[] raw) {
        try {
            return InetAddress.getByAddress(new byte[]{raw[12], raw[13], raw[14], raw[15]});
        } catch (UnknownHostException impossible) {
            return null;
        }
    }

    /** Whether the host is written as an address rather than a name. */
    private static boolean isIpLiteral(String host) {
        String raw = unbracket(host);
        return raw.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") || raw.contains(":");
    }
}
