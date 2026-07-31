package dev.faultora.connector.jdbc;

import dev.faultora.model.catalog.NormalizedError;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.Map;

/**
 * Database failures as protocol-neutral errors.
 * <p>
 * Retryability comes from the driver's own classification: a transient
 * exception is one the driver says may succeed again, and guessing here would
 * either retry a syntax error forever or give up on a connection that came
 * back. The message names the database by host and driver, never by URL,
 * because a JDBC URL is a common place to find a password.
 * <p>
 * The driver's own message is kept rather than replaced. It is the only thing
 * that says which column does not exist or which type would not cast, and a
 * connector that swallowed it would leave an operator with "the observation
 * failed" and nothing to act on. What it can also carry is a value from the
 * query — several drivers quote the offending literal — so it is bounded here
 * and {@code docs/SECURITY.md} states the exposure rather than implying there
 * is none.
 */
final class JdbcErrors {

    /** How much of a driver's message is kept, in characters. */
    private static final int MAX_MESSAGE_CHARACTERS = 500;

    private JdbcErrors() {
    }

    static NormalizedError of(SQLException failure, String database) {
        if (failure instanceof SQLTimeoutException) {
            return new NormalizedError(
                    NormalizedError.ErrorCategory.TIMEOUT, "OBSERVATION_TIMEOUT",
                    "The observation on " + database + " outlived its query timeout",
                    true, Map.of());
        }
        boolean retryable = failure instanceof SQLTransientException;
        return new NormalizedError(
                retryable
                        ? NormalizedError.ErrorCategory.NETWORK
                        : NormalizedError.ErrorCategory.TARGET_ERROR,
                retryable ? "DATABASE_TRANSIENT_ERROR" : "OBSERVATION_FAILED",
                "The observation on " + database + " failed: " + bounded(failure.getMessage()),
                retryable, Map.of());
    }

    /** As much of the driver's message as a diagnostic needs. */
    private static String bounded(String message) {
        if (message == null) {
            return "the driver gave no reason";
        }
        return message.length() <= MAX_MESSAGE_CHARACTERS
                ? message
                : message.substring(0, MAX_MESSAGE_CHARACTERS) + "… (" + message.length()
                        + " characters)";
    }
}
