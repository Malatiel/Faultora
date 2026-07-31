package dev.faultora.connector.jdbc;

import dev.faultora.net.DestinationPolicyViolation;
import dev.faultora.net.HostPolicy;

/**
 * A database URL, checked against the destination policy.
 * <p>
 * A JDBC URL is a destination like a base URL or a bootstrap list, and it faces
 * the same refusal: a run must not reach a database on a network it was not
 * allowed to touch. What it cannot do is pin the address it verified — the
 * driver resolves its own host — which is the same asymmetry the Kafka
 * connector has and {@code docs/SECURITY.md} states.
 * <p>
 * An in-memory URL such as {@code jdbc:h2:mem:…} names no host, and there is
 * nothing to classify: it never leaves the process.
 *
 * @param value the URL as the driver will receive it
 * @param host  the host it reaches, or null when it reaches none
 */
record JdbcUrl(String value, String host) {

    private static final String SCHEME = "jdbc:";

    /**
     * Parse a database URL and check where it points.
     *
     * @throws DestinationPolicyViolation when it is unusable, or the host is
     *                                    refused
     */
    static JdbcUrl parse(String url, HostPolicy policy) {
        if (url == null || url.isBlank()) {
            throw new DestinationPolicyViolation("The target declares no database URL");
        }
        if (!url.startsWith(SCHEME)) {
            throw new DestinationPolicyViolation(
                    "A database URL begins 'jdbc:', and this one is '" + url + "'");
        }
        String host = hostOf(url);
        if (host == null) {
            // No host to reach: an in-process database, which the policy has
            // no opinion about because nothing leaves this machine.
            return new JdbcUrl(url, null);
        }
        HostPolicy.Decision decision = policy.check(host);
        if (!decision.isReachable()) {
            throw new DestinationPolicyViolation(
                    "Database host " + host + " is refused: " + decision.reason());
        }
        return new JdbcUrl(url, host);
    }

    /**
     * The host a URL names, or null when it names none.
     * <p>
     * Subprotocols differ in almost everything and agree on this: a networked
     * one puts {@code //host[:port]} after its name.
     */
    private static String hostOf(String url) {
        int authority = url.indexOf("//");
        if (authority < 0) {
            return null;
        }
        String rest = url.substring(authority + 2);
        int end = rest.length();
        for (int index = 0; index < rest.length(); index++) {
            char character = rest.charAt(index);
            if (character == '/' || character == ':' || character == '?'
                    || character == ';' || character == ',') {
                end = index;
                break;
            }
        }
        String host = rest.substring(0, end);
        return host.isBlank() ? null : host;
    }

    /**
     * The URL as it may appear in a message.
     * <p>
     * A JDBC URL is a common place to find a password — H2 and SQL Server both
     * take one as a {@code ;}-separated property, and several drivers take one
     * in the query string. So a diagnostic names the driver and the host it
     * reaches, and drops everything a driver reads as configuration.
     */
    String redacted() {
        int authority = value.indexOf("//");
        if (authority >= 0) {
            return value.substring(0, authority + 2) + (host == null ? "" : host);
        }
        int properties = value.length();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == ';' || character == '?') {
                properties = index;
                break;
            }
        }
        return value.substring(0, properties);
    }
}
