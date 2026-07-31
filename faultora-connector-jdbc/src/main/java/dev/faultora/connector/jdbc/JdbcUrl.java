package dev.faultora.connector.jdbc;

import dev.faultora.net.DestinationPolicyViolation;
import dev.faultora.net.HostPolicy;

import java.util.List;
import java.util.Locale;

/**
 * A database URL, checked against the destination policy.
 * <p>
 * A JDBC URL is a destination like a base URL or a bootstrap list, and it faces
 * the same refusal: a run must not reach a database on a network it was not
 * allowed to touch. What it cannot do is pin the address it verified — the
 * driver resolves its own host — which is the same asymmetry the Kafka
 * connector has and {@code docs/SECURITY.md} states.
 * <p>
 * A URL that names no host is refused rather than allowed, unless its
 * subprotocol is one of the few that cannot reach a network at all. Not every
 * driver writes {@code //host}: Oracle's thin driver writes
 * {@code jdbc:oracle:thin:@db.internal:1521:ORCL}, and reading "no {@code //},
 * so nothing to classify" would have let that reach an internal host with the
 * policy never consulted. A host this class cannot find is a host it cannot
 * clear.
 *
 * @param value the URL as the driver will receive it
 * @param host  the host it reaches, or null when it reaches none
 */
record JdbcUrl(String value, String host) {

    private static final String SCHEME = "jdbc:";

    /**
     * Subprotocols that reach no network, so a URL of theirs needs no host.
     * <p>
     * Each of these runs inside this process or against a local file. They are
     * listed rather than inferred because the list is the safe side of the
     * refusal: a subprotocol missing from it is refused with a message, and a
     * subprotocol wrongly on it would be a hole.
     */
    private static final List<String> IN_PROCESS = List.of(
            "jdbc:h2:mem:", "jdbc:h2:file:", "jdbc:hsqldb:mem:",
            "jdbc:derby:memory:", "jdbc:sqlite:");

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
            String lowercase = url.toLowerCase(Locale.ROOT);
            if (IN_PROCESS.stream().noneMatch(lowercase::startsWith)) {
                throw new DestinationPolicyViolation(
                        "Cannot find the host in database URL '" + redactionOf(url)
                                + "'. A URL the destination policy cannot read is refused "
                                + "rather than allowed, because a driver that writes its "
                                + "host some other way — 'jdbc:oracle:thin:@host:1521:SID' "
                                + "— would otherwise reach it unchecked");
            }
            // Nothing leaves this process, so the policy has no opinion.
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
     * one puts {@code //host[:port]} after its name. What can precede the host
     * inside that is credentials — {@code //user:password@10.0.0.5/db} — and
     * they contain a {@code :} of their own, so the authority is cut out first
     * and the credentials dropped from it before the host is read. Reading left
     * to right would classify the user name, which is a host nobody connects to.
     */
    private static String hostOf(String url) {
        int slashes = url.indexOf("//");
        if (slashes < 0) {
            return null;
        }
        String authority = url.substring(slashes + 2);
        int end = authority.length();
        for (int index = 0; index < authority.length(); index++) {
            char character = authority.charAt(index);
            if (character == '/' || character == '?' || character == ';'
                    || character == ',') {
                end = index;
                break;
            }
        }
        authority = authority.substring(0, end);

        int credentials = authority.lastIndexOf('@');
        String host = credentials < 0
                ? authority : authority.substring(credentials + 1);
        int port = host.indexOf(':');
        host = port < 0 ? host : host.substring(0, port);
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
        return redactionOf(value);
    }

    /** The same redaction, usable before a URL has been accepted. */
    private static String redactionOf(String url) {
        int authority = url.indexOf("//");
        if (authority >= 0) {
            String host = hostOf(url);
            return url.substring(0, authority + 2) + (host == null ? "" : host);
        }
        int properties = url.length();
        for (int index = 0; index < url.length(); index++) {
            char character = url.charAt(index);
            if (character == ';' || character == '?' || character == '@') {
                properties = index;
                break;
            }
        }
        return url.substring(0, properties);
    }
}
