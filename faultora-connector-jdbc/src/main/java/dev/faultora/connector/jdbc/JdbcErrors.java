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
 */
final class JdbcErrors {

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
                "The observation on " + database + " failed: " + failure.getMessage(),
                retryable, Map.of());
    }
}
