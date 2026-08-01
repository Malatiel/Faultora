package dev.faultora.connector.jdbc;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.security.SecretHandle;
import dev.faultora.net.DestinationPolicyViolation;
import dev.faultora.net.HostPolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.evidence.TableEvidence;
import dev.faultora.spi.result.OperationResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Observes a database, and only observes it.
 * <p>
 * Three things stop this connector from writing, and the third is the one that
 * matters:
 * <ul>
 *   <li>the declared statement must be a single reading statement, checked
 *       before a connection is opened — see {@link ReadOnlyStatement};</li>
 *   <li>the connection is set read-only, so a driver that can enforce it
 *       does;</li>
 *   <li><b>the credentials should be read-only.</b> Faultora cannot check this
 *       and does not pretend to. The two rules above are code, and code is one
 *       defect away from being wrong; a grant is not. An operator who gives
 *       this connector a writing account has removed the only guarantee that
 *       does not depend on this project being correct.</li>
 * </ul>
 * Values a scenario supplies are bound through a prepared statement and never
 * assembled into the text, so what a run may read is what the operator's
 * document says it may read.
 */
public final class JdbcConnector implements Connector {

    /** Protocol identifier of operations this connector executes. */
    public static final String PROTOCOL = "jdbc";

    /** Metadata key carrying the declared statement. */
    static final String SQL = "sql";

    private static final ProtocolId PROTOCOL_ID = new ProtocolId(PROTOCOL);

    /** Config key naming the user observations connect as. */
    public static final String USER = "databaseUser";

    /** Config key naming the secret handle that supplies its password. */
    public static final String SECRET_ID = "databaseSecretId";

    private final HostPolicy hostPolicy;

    public JdbcConnector() {
        this(HostPolicy.defaultPolicy());
    }

    public JdbcConnector(HostPolicy hostPolicy) {
        this.hostPolicy = hostPolicy;
    }

    @Override
    public ProtocolId protocol() {
        return PROTOCOL_ID;
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("jdbc-observe", "tabular-evidence");
    }

    /**
     * A handle for one observation.
     * <p>
     * Not cached, and not pooled. A JDBC connection belongs to one thread, and
     * the engine prepares and releases around every invocation — the same shape
     * the Kafka consumer has, for the same reason: a shared handle would be
     * driven by two steps of a parallel group at once, and the first to finish
     * would close it under the second.
     */
    @Override
    public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
        JdbcUrl url = JdbcUrl.parse(target.baseUrl(), hostPolicy);
        return new JdbcPreparedTarget(target, url, () -> open(url, context));
    }

    private Connection open(JdbcUrl url, ConnectorContext context) {
        Properties properties = new Properties();

        long connectSeconds = Math.max(1,
                (context == null ? 5000 : context.connectTimeoutMs()) / 1000);
        String timeoutProperty = connectTimeoutPropertyFor(url);
        if (timeoutProperty != null) {
            properties.setProperty(timeoutProperty, Long.toString(connectSeconds));
        }

        // The password is resolved here and used immediately: it never becomes
        // a field of this connector, and nothing above it ever holds the value.
        String user = text(context, USER);
        if (user != null) {
            properties.setProperty("user", user);
        }
        char[] password = passwordOf(context);
        if (password != null) {
            try {
                properties.setProperty("password", new String(password));
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        }
        try {
            Connection connection = DriverManager.getConnection(url.value(), properties);
            // A driver that can refuse writes on a read-only connection now
            // will. One that cannot leaves the statement check and the grant.
            connection.setReadOnly(true);
            // Not autocommit, which reads oddly for a connection that never
            // writes. PostgreSQL only opens a server-side cursor — the thing
            // that makes the fetch size bound what is fetched rather than what
            // is returned — inside a transaction. In autocommit the driver
            // materializes the whole result set and the documented bound would
            // be a claim about nothing. The connection belongs to one
            // observation and is closed with it, so the transaction it opens
            // is rolled back with nothing in it.
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException unreachable) {
            throw new DestinationPolicyViolation(
                    "Cannot connect to " + url.redacted() + ": " + unreachable.getMessage());
        }
    }

    private static String text(ConnectorContext context, String key) {
        Object value = context == null || context.config() == null
                ? null : context.config().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * The password behind the operator's secret handle, or null when none.
     * <p>
     * An expired handle is refused rather than used. A resolver that rotates
     * credentials hands out a handle with a lifetime, and connecting with a
     * value past it produces an authentication failure that reads like the
     * database rejecting the run — the same reason the HTTP connector checks it.
     */
    private static char[] passwordOf(ConnectorContext context) {
        String secretId = text(context, SECRET_ID);
        if (secretId == null) {
            return null;
        }
        if (context.secretResolver() == null) {
            throw new IllegalStateException(
                    "No secret resolver configured for: " + secretId);
        }
        SecretHandle handle = context.secretResolver().apply(secretId);
        if (handle == null) {
            throw new IllegalStateException(
                    "Secret resolver returned null for: " + secretId);
        }
        if (handle.isExpired()) {
            throw new IllegalStateException(
                    "Secret handle expired for: " + secretId);
        }
        char[] password = handle.secretValue();
        if (password == null || password.length == 0) {
            throw new IllegalStateException(
                    "Secret value is empty for: " + secretId);
        }
        return password;
    }

    /**
     * The connection property a driver takes its connect timeout in, in
     * seconds, or null when this connector knows of none for it.
     * <p>
     * {@link DriverManager#setLoginTimeout} is portable and is process-wide
     * state: setting it here would change how every other driver in this JVM
     * connects, including one a host application owns, and the last connector
     * to call it would win. So the timeout travels as a connection property
     * instead — and only to a driver documented to read it under that name, in
     * those units, because a driver handed a property it does not know can
     * refuse the connection outright, as H2 does.
     */
    private static String connectTimeoutPropertyFor(JdbcUrl url) {
        return url.value().startsWith("jdbc:postgresql:") ? "connectTimeout" : null;
    }

    @Override
    public OperationResult execute(
            PreparedTarget preparedTarget,
            OperationDefinition operation,
            Map<String, Object> inputs,
            ConnectorContext context
    ) {
        JdbcPreparedTarget target = (JdbcPreparedTarget) preparedTarget;
        Object declared = operation.protocolMetadata() == null
                ? null : operation.protocolMetadata().get(SQL);
        try {
            ReadOnlyStatement statement =
                    ReadOnlyStatement.of(declared == null ? null : declared.toString());
            return Observation.run(
                    target, statement, inputs == null ? Map.of() : inputs, context);
        } catch (IllegalArgumentException refused) {
            return OperationResult.failure(new NormalizedError(
                    NormalizedError.ErrorCategory.VALIDATION,
                    "OBSERVATION_REFUSED", refused.getMessage(), false, Map.of()), 0);
        } catch (DestinationPolicyViolation unreachable) {
            return OperationResult.failure(new NormalizedError(
                    NormalizedError.ErrorCategory.NETWORK,
                    "DATABASE_UNAVAILABLE", unreachable.getMessage(), true, Map.of()), 0);
        } catch (IllegalStateException noCredentials) {
            // Not retryable: a missing or expired secret is configuration, and
            // trying again with it produces the same refusal from the database.
            return OperationResult.failure(new NormalizedError(
                    NormalizedError.ErrorCategory.VALIDATION,
                    "SECRET_UNAVAILABLE", noCredentials.getMessage(), false, Map.of()), 0);
        }
    }

    @Override
    public void release(PreparedTarget preparedTarget) {
        if (preparedTarget instanceof JdbcPreparedTarget target) {
            target.close();
        }
    }

    @Override
    public void close() {
        // Every connection belongs to an operation and was closed with it.
    }

    /** Protocol evidence for an observation, beside the rows themselves. */
    static Map<String, Object> evidenceOf(TableEvidence table, long fetchedRows) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put(TableEvidence.OBSERVED, table);
        evidence.put("rowCount", table.rowCount());
        evidence.put("fetchedRows", fetchedRows);
        evidence.put("truncated", table.truncated());
        return evidence;
    }
}
