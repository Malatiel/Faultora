package dev.faultora.connector.kafka;

import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.evidence.MessageEvidence;
import dev.faultora.spi.result.OperationResult;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Writes one message to a topic and reports where it landed.
 * <p>
 * A publish waits for the broker's acknowledgement rather than returning as
 * soon as the record is queued. A scenario that published without waiting could
 * observe nothing and be unable to tell an unacknowledged write from a target
 * that never reacted — and telling those apart is the whole point.
 */
final class EventPublisher {

    /** Input carrying the message payload. */
    static final String BODY = "body";
    /** Input carrying the message key. */
    static final String KEY = "key";
    /** Input carrying message headers. */
    static final String HEADERS = "headers";

    private EventPublisher() {
    }

    static OperationResult publish(
            KafkaPreparedTarget target,
            KafkaOperation operation,
            Map<String, Object> inputs,
            ConnectorContext context
    ) {
        long startedAt = System.nanoTime();
        byte[] payload = Messages.payloadOf(inputs.get(BODY));
        Object declaredKey = inputs.get(KEY);
        String key = declaredKey == null ? null : declaredKey.toString();
        Map<String, String> headers = Messages.declaredHeaders(inputs.get(HEADERS));

        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                operation.topic(), null, key == null ? null
                        : key.getBytes(StandardCharsets.UTF_8), payload);
        headers.forEach((name, value) -> record.headers().add(new RecordHeader(
                name, value == null ? null : value.getBytes(StandardCharsets.UTF_8))));

        long timeoutMs = KafkaTimeouts.publish(context);
        try {
            RecordMetadata metadata =
                    target.producer().send(record).get(timeoutMs, TimeUnit.MILLISECONDS);

            MessageEvidence published = Messages.evidence(
                    metadata.topic(), metadata.partition(), metadata.offset(),
                    metadata.hasTimestamp() ? metadata.timestamp() : -1,
                    key, headers, payload, context.evidencePolicy(), true);

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put(MessageEvidence.PUBLISHED, published);
            evidence.put("topic", metadata.topic());
            evidence.put("partition", metadata.partition());
            evidence.put("offset", metadata.offset());

            return OperationResult.success(
                    -1, Map.of(), null, elapsedMs(startedAt), evidence);
        } catch (ExecutionException rejected) {
            return OperationResult.failure(
                    KafkaErrors.of(rejected.getCause(), "publishing to " + operation.topic()),
                    elapsedMs(startedAt));
        } catch (java.util.concurrent.TimeoutException unacknowledged) {
            return OperationResult.failure(
                    KafkaErrors.timeout(
                            "The broker did not acknowledge the message to "
                                    + operation.topic() + " within " + timeoutMs + "ms"),
                    elapsedMs(startedAt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return OperationResult.failure(
                    KafkaErrors.cancelled("Interrupted while publishing to "
                            + operation.topic()),
                    elapsedMs(startedAt));
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
