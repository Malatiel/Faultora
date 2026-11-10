package dev.faultora.runner.protocol;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ContentDigest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Attempt-bound requests and acknowledgements for opt-in runner protocol 2. */
public final class DurableDelivery {
    public static final int MAX_BATCH_EVENTS = 64;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_ATTEMPT_EVENTS = 100_000;
    public static final int MAX_REQUEST_BYTES = 3 * MAX_BATCH_BYTES;
    public static final int MAX_ACK_BYTES = 4096;

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private DurableDelivery() { }

    public record Attempt(String runnerId, String runId, String attemptId, String dispatchDigest) {
        public Attempt {
            new RunId(runId);
            if (!canonicalUuid(runnerId) || !canonicalUuid(attemptId) || !isDigest(dispatchDigest)) {
                throw new IllegalArgumentException("Invalid delivery attempt");
            }
        }
    }

    public record Progress(Attempt attempt, long fromPosition, List<String> eventLines) {
        public Progress {
            if (attempt == null || !position(fromPosition) || eventLines == null || eventLines.isEmpty()
                    || eventLines.size() > MAX_BATCH_EVENTS
                    || eventLines.size() > MAX_ATTEMPT_EVENTS - fromPosition) {
                throw new IllegalArgumentException("Invalid delivery batch");
            }
            eventLines = List.copyOf(eventLines);
            long bytes = 0;
            for (String line : eventLines) {
                bytes += line.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_BATCH_BYTES || line.isBlank() || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("Invalid delivery event");
                }
            }
        }

        public long endPosition() { return fromPosition + eventLines.size(); }
    }

    public record Terminal(Attempt attempt, long finalPosition, String status, boolean cleanupConfirmed,
                           boolean leaseExpired, String reasonCode, String terminalDigest) {
        private static final Set<String> STATUSES = Set.of("PASSED", "FAILED", "CANCELLED", "ERROR");

        public Terminal {
            if (attempt == null || !position(finalPosition) || status == null || !STATUSES.contains(status)
                    || ("ERROR".equals(status) ? cleanupConfirmed || !"INFRASTRUCTURE_ERROR".equals(reasonCode)
                            : !"NONE".equals(reasonCode))
                    || !digestOf(finalPosition, status, cleanupConfirmed, leaseExpired, reasonCode).equals(terminalDigest)) {
                throw new IllegalArgumentException("Invalid delivery terminal");
            }
        }

        public static Terminal of(Attempt attempt, long position, String status, boolean cleanupConfirmed,
                                  boolean leaseExpired, String reasonCode) {
            return new Terminal(attempt, position, status, cleanupConfirmed, leaseExpired, reasonCode,
                    digestOf(position, status, cleanupConfirmed, leaseExpired, reasonCode));
        }

        private static String digestOf(long position, String status, boolean cleanupConfirmed,
                                       boolean leaseExpired, String reasonCode) {
            return ContentDigest.sha256Uri(status + "\n" + position + "\n" + cleanupConfirmed + "\n"
                    + leaseExpired + "\n" + reasonCode);
        }
    }

    public record Ack(Attempt attempt, long nextPosition, String terminalDigest) {
        public Ack {
            if (attempt == null || !position(nextPosition) || (terminalDigest != null && !isDigest(terminalDigest))) {
                throw new IllegalArgumentException("Invalid delivery acknowledgement");
            }
        }
    }

    public static <T> T decode(byte[] bytes, Class<T> type, int maximum) throws IOException {
        if (bytes.length > maximum) throw new IOException("DURABLE_DELIVERY_MESSAGE_TOO_LARGE");
        try {
            T result = JSON.readValue(bytes, type);
            if (result != null) return result;
        } catch (IOException | IllegalArgumentException malformed) {
            // Peer-controlled values and parser text are not diagnostic data.
        }
        throw new IOException("DURABLE_DELIVERY_INVALID_MESSAGE");
    }

    /** A receiver must check journal ownership before committing any of the batch. */
    public static boolean eventBelongsTo(String line, Attempt attempt) {
        try {
            var event = JSON.readTree(line);
            return event != null && event.path("eventType").isTextual()
                    && attempt.runId().equals(event.path("runId").textValue());
        } catch (IOException malformed) {
            return false;
        }
    }

    private static boolean position(long value) { return value >= 0 && value <= MAX_ATTEMPT_EVENTS; }

    public static boolean isDigest(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static boolean canonicalUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
