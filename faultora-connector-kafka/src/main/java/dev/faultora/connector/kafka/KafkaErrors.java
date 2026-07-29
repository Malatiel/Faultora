package dev.faultora.connector.kafka;

import dev.faultora.model.catalog.NormalizedError;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TimeoutException;

import java.util.Map;

/**
 * Kafka failures as protocol-neutral errors.
 * <p>
 * Retryability is taken from the client library rather than guessed: Kafka
 * already classifies its own exceptions, and a scenario's retry policy acts on
 * that classification. Guessing here would either retry an authorization
 * failure forever or give up on a leader election that resolves itself.
 */
final class KafkaErrors {

    private KafkaErrors() {
    }

    static NormalizedError of(Throwable failure, String doing) {
        if (failure instanceof TimeoutException) {
            return timeout("Timed out " + doing + ": " + failure.getMessage());
        }
        if (failure instanceof AuthenticationException
                || failure instanceof AuthorizationException) {
            return new NormalizedError(
                    NormalizedError.ErrorCategory.POLICY_VIOLATION,
                    "KAFKA_ACCESS_DENIED",
                    "Refused while " + doing + ": " + failure.getMessage(),
                    false, Map.of());
        }
        boolean retryable = failure instanceof RetriableException;
        return new NormalizedError(
                retryable
                        ? NormalizedError.ErrorCategory.NETWORK
                        : NormalizedError.ErrorCategory.TARGET_ERROR,
                retryable ? "KAFKA_RETRIABLE_ERROR" : "KAFKA_ERROR",
                "Failed while " + doing + ": " + failure,
                retryable, Map.of());
    }

    static NormalizedError timeout(String message) {
        return new NormalizedError(
                NormalizedError.ErrorCategory.TIMEOUT,
                "KAFKA_TIMEOUT", message, true, Map.of());
    }

    static NormalizedError cancelled(String message) {
        return new NormalizedError(
                NormalizedError.ErrorCategory.CANCELLED,
                "KAFKA_CANCELLED", message, false, Map.of());
    }

    static NormalizedError configuration(String message) {
        return new NormalizedError(
                NormalizedError.ErrorCategory.VALIDATION,
                "KAFKA_INVALID_OPERATION", message, false, Map.of());
    }

    static NormalizedError unreachable(String message) {
        return new NormalizedError(
                NormalizedError.ErrorCategory.NETWORK,
                "KAFKA_UNAVAILABLE", message, true, Map.of());
    }
}
