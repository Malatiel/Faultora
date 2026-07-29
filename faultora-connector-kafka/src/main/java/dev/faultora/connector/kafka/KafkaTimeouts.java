package dev.faultora.connector.kafka;

import dev.faultora.spi.context.ConnectorContext;

import java.util.Map;

/**
 * How long a Kafka operation is allowed to take.
 * <p>
 * Every wait here is bounded by the run's own timeouts before it is bounded by
 * anything the scenario asks for. An observation that outlived the request
 * timeout would let a scenario extend a run past the budget the operator set,
 * which is the failure mode the execution policy exists to prevent.
 */
final class KafkaTimeouts {

    /** Input naming how long an observation waits for its messages. */
    static final String WAIT_MS = "waitMs";

    /** How long an observation waits when the step says nothing. */
    static final long DEFAULT_OBSERVE_MS = 5_000;

    /** How long a publish waits for its acknowledgement when nothing says. */
    private static final long DEFAULT_PUBLISH_MS = 10_000;

    private KafkaTimeouts() {
    }

    /** How long to wait for a publish acknowledgement. */
    static long publish(ConnectorContext context) {
        long requested = context == null ? 0 : context.requestTimeoutMs();
        return requested > 0 ? requested : DEFAULT_PUBLISH_MS;
    }

    /**
     * How long an observation may wait: what the step asked for, capped by the
     * run's request timeout.
     */
    static long observe(Map<String, Object> inputs, ConnectorContext context) {
        long requested = asMillis(inputs == null ? null : inputs.get(WAIT_MS));
        long wanted = requested >= 0 ? requested : DEFAULT_OBSERVE_MS;
        long ceiling = context == null ? 0 : context.requestTimeoutMs();
        return ceiling > 0 ? Math.min(wanted, ceiling) : wanted;
    }

    /** @return the value in milliseconds, or -1 when the step declared none */
    private static long asMillis(Object value) {
        if (value == null) {
            return -1;
        }
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        try {
            return Math.max(0, Long.parseLong(value.toString().trim()));
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    WAIT_MS + " must be a number of milliseconds, got: " + value);
        }
    }
}
