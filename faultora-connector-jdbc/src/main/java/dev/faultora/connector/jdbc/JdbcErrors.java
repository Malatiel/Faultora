package dev.faultora.connector.jdbc;

import dev.faultora.model.catalog.NormalizedError;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Driver classifications are exported; driver messages, URLs and query values are not. */
final class JdbcErrors {
    private JdbcErrors() { }

    static NormalizedError of(SQLException failure) {
        boolean timeout = failure instanceof SQLTimeoutException;
        boolean retryable = timeout || failure instanceof SQLTransientException;
        return new NormalizedError(timeout ? NormalizedError.ErrorCategory.TIMEOUT
                : retryable ? NormalizedError.ErrorCategory.NETWORK : NormalizedError.ErrorCategory.TARGET_ERROR,
                timeout ? "OBSERVATION_TIMEOUT" : retryable ? "DATABASE_TRANSIENT_ERROR" : "OBSERVATION_FAILED",
                timeout ? "Database observation exceeded its query timeout" : "Database observation failed",
                retryable, classification(failure));
    }

    static ConnectionFailure unavailable(SQLException failure, boolean closeFailed) {
        Map<String, Object> fields = new LinkedHashMap<>(classification(failure));
        if (closeFailed) {
            fields.put("connectionCloseFailed", true);
        }
        return new ConnectionFailure(new NormalizedError(NormalizedError.ErrorCategory.NETWORK,
                "DATABASE_UNAVAILABLE", "Database connection failed", true, Map.copyOf(fields)));
    }

    private static Map<String, Object> classification(SQLException failure) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("vendorCode", failure.getErrorCode());
        String state = failure.getSQLState();
        if (state != null && state.matches("[A-Z0-9]{5}")) {
            fields.put("sqlState", state);
        }
        return Map.copyOf(fields);
    }

    static final class ConnectionFailure extends RuntimeException {
        private final NormalizedError error;
        ConnectionFailure(NormalizedError error) {
            super(error.message());
            this.error = error;
        }
        NormalizedError error() {
            return error;
        }
    }
}
