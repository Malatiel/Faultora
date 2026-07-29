package dev.faultora.connector.kafka;

import dev.faultora.net.DestinationPolicyViolation;
import dev.faultora.net.HostPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * The brokers a target names, checked against the destination policy.
 * <p>
 * A target's base URL is written {@code kafka://host:9092} or
 * {@code kafka://host-a:9092,host-b:9092}; the scheme may be left off. Every
 * host in the list is classified before the connector is allowed to reach any
 * of them, because a bootstrap list is a destination like any other and one
 * unclassified entry is enough to reach a network the run may not touch.
 * <p>
 * What this cannot do is pin the addresses it verified. The Kafka client
 * resolves its own brokers, and it re-resolves them when it learns the cluster
 * topology from the bootstrap response — so a broker that advertises a
 * different address is reached at that address without passing here. The
 * refusal is real; the rebinding protection the HTTP connector has is not
 * available, and {@code docs/SECURITY.md} says so rather than implying parity.
 */
record BootstrapServers(String value, List<String> hosts) {

    private static final String SCHEME = "kafka://";
    private static final int DEFAULT_PORT = 9092;

    /**
     * Parse a target's base URL and check every broker against the policy.
     *
     * @throws DestinationPolicyViolation when the list is unusable, or when any
     *                                    broker in it is refused
     */
    static BootstrapServers parse(String baseUrl, HostPolicy policy) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new DestinationPolicyViolation("Kafka target declares no bootstrap servers");
        }
        String withoutScheme = baseUrl.startsWith(SCHEME)
                ? baseUrl.substring(SCHEME.length()) : baseUrl;
        // A trailing path means the URL is describing something other than a
        // broker list, and guessing which part is the list would be worse than
        // refusing it.
        int path = withoutScheme.indexOf('/');
        if (path >= 0) {
            withoutScheme = withoutScheme.substring(0, path);
        }

        List<String> brokers = new ArrayList<>();
        List<String> hosts = new ArrayList<>();
        for (String entry : withoutScheme.split(",")) {
            String broker = entry.trim();
            if (broker.isEmpty()) {
                continue;
            }
            String host = hostOf(broker);
            HostPolicy.Decision decision = policy.check(host);
            if (!decision.isReachable()) {
                throw new DestinationPolicyViolation(
                        "Kafka broker " + broker + " is refused: " + decision.reason());
            }
            brokers.add(broker.contains(":") ? broker : broker + ":" + DEFAULT_PORT);
            hosts.add(host);
        }
        if (brokers.isEmpty()) {
            throw new DestinationPolicyViolation(
                    "Kafka target '" + baseUrl + "' names no broker");
        }
        return new BootstrapServers(String.join(",", brokers), List.copyOf(hosts));
    }

    /** The host part of {@code host:port}, tolerating a bracketed IPv6 literal. */
    private static String hostOf(String broker) {
        if (broker.startsWith("[")) {
            int close = broker.indexOf(']');
            return close > 0 ? broker.substring(0, close + 1) : broker;
        }
        int colon = broker.indexOf(':');
        return colon > 0 ? broker.substring(0, colon) : broker;
    }
}
