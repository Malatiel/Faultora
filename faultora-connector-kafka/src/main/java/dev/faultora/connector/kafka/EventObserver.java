package dev.faultora.connector.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.evidence.MessageEvidence;
import dev.faultora.spi.result.OperationResult;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the messages a step is about, within bounds it cannot exceed.
 * <p>
 * An observation is bounded three ways, and all three are needed:
 * <ul>
 *   <li><b>below</b>, by the topic's floor — where the channel stood when the
 *       run began — so an observation never reports history that predates the
 *       run. {@code from: beginning} says otherwise, explicitly;</li>
 *   <li><b>above</b>, by a wait that the run's request timeout caps, so no
 *       scenario can hold a run open by observing;</li>
 *   <li><b>in volume</b>, by a message count and by a byte budget for stored
 *       payloads, so a busy topic cannot exhaust the memory a run holds its
 *       evidence in.</li>
 * </ul>
 * Within those bounds, which messages the step is <em>about</em> is decided by
 * the {@link MessageSelector}, not by position. That is what makes an
 * observation repeatable: the same scenario run twice, or twice within one
 * repeat block, selects its own messages both times.
 */
final class EventObserver {

    /** Input naming how many matching messages are enough. */
    static final String MAX_MESSAGES = "maxMessages";
    /** Input naming where the observation starts. */
    static final String FROM = "from";
    /** Value of {@code from} that reaches back past the run's own floor. */
    static final String FROM_BEGINNING = "beginning";

    /** How many matching messages an observation collects when nothing says. */
    private static final int DEFAULT_MAX_MESSAGES = 10;
    /**
     * How much payload one observation may hold, past which digests stand
     * alone.
     * <p>
     * This bounds the aggregate. A single payload is bounded by the evidence
     * policy's own size limit, which is the right knob for one message — so the
     * first message of an observation is stored even if it alone is larger than
     * this, and the ones after it are not.
     */
    private static final long EVIDENCE_BUDGET_BYTES = 1024 * 1024;
    /** How long one poll blocks, short enough to notice the deadline. */
    private static final Duration POLL = Duration.ofMillis(250);

    private EventObserver() {
    }

    static OperationResult observe(
            KafkaPreparedTarget target,
            KafkaOperation operation,
            Map<String, Object> inputs,
            ConnectorContext context
    ) {
        long startedAt = System.nanoTime();
        MessageSelector selector = MessageSelector.from(inputs);
        int maxMessages = maxMessages(inputs);
        long waitMs = KafkaTimeouts.observe(inputs, context);
        long requestedWaitMs = KafkaTimeouts.requested(inputs);

        List<TopicPartition> partitions = target.partitions(operation.topic());
        Map<TopicPartition, Long> floor = target.floor(operation.topic());

        Consumer<byte[], byte[]> consumer = target.consumer();
        consumer.assign(partitions);
        seek(consumer, partitions, floor, fromBeginning(inputs));

        List<MessageEvidence> matched = new ArrayList<>();
        long observed = 0;
        long storedBytes = 0;
        long deadline = System.nanoTime() + waitMs * 1_000_000L;

        // The window closes at its deadline, and nothing extends it. Polling on
        // while messages keep arriving would sound reasonable and would mean a
        // busy channel could hold a run open indefinitely — which is the bound
        // this class claims to have. A zero wait still gets one poll, so it
        // reads the batch that is already there rather than nothing at all.
        boolean polledOnce = false;
        while (matched.size() < maxMessages
                && (!polledOnce || System.nanoTime() < deadline)) {
            ConsumerRecords<byte[], byte[]> polled = consumer.poll(POLL);
            polledOnce = true;
            for (ConsumerRecord<byte[], byte[]> record : polled) {
                observed++;
                RecordCandidate candidate = new RecordCandidate(record);
                if (!selector.matches(candidate)) {
                    continue;
                }
                byte[] payload = record.value();
                boolean withinBudget = storedBytes < EVIDENCE_BUDGET_BYTES;
                matched.add(Messages.evidence(
                        record.topic(), record.partition(), record.offset(),
                        record.timestamp(), candidate.key(),
                        Messages.headersOf(record.headers()), payload,
                        context.evidencePolicy(), withinBudget));
                if (withinBudget && payload != null) {
                    storedBytes += payload.length;
                }
                if (matched.size() >= maxMessages) {
                    break;
                }
            }
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put(MessageEvidence.OBSERVED, List.copyOf(matched));
        evidence.put("topic", operation.topic());
        evidence.put("observed", observed);
        evidence.put("matched", matched.size());
        evidence.put("selective", !selector.selectsEverything());
        evidence.put("waitedMs", waitMs);
        evidence.put("requestedWaitMs", requestedWaitMs);

        // An observation that found nothing is still an observation: absence is
        // what several of the assertions in this release are about, and turning
        // it into a failure here would put the verdict in the wrong place.
        return OperationResult.success(-1, Map.of(), null, elapsedMs(startedAt), evidence);
    }

    /** Position every partition at the start of the window. */
    private static void seek(
            Consumer<byte[], byte[]> consumer,
            List<TopicPartition> partitions,
            Map<TopicPartition, Long> floor,
            boolean fromBeginning
    ) {
        if (fromBeginning) {
            consumer.seekToBeginning(partitions);
            return;
        }
        Map<TopicPartition, Long> beginnings = null;
        for (TopicPartition partition : partitions) {
            Long start = floor.get(partition);
            if (start != null) {
                consumer.seek(partition, start);
                continue;
            }
            // A partition created after the floor was captured has no entry.
            // Its earliest message is still above the floor in time, so its
            // beginning is the right position.
            if (beginnings == null) {
                beginnings = consumer.beginningOffsets(partitions);
            }
            consumer.seek(partition, beginnings.getOrDefault(partition, 0L));
        }
    }

    private static boolean fromBeginning(Map<String, Object> inputs) {
        Object declared = inputs == null ? null : inputs.get(FROM);
        return declared != null && FROM_BEGINNING.equalsIgnoreCase(declared.toString().trim());
    }

    private static int maxMessages(Map<String, Object> inputs) {
        Object declared = inputs == null ? null : inputs.get(MAX_MESSAGES);
        if (declared == null) {
            return DEFAULT_MAX_MESSAGES;
        }
        int requested;
        if (declared instanceof Number number) {
            requested = number.intValue();
        } else {
            try {
                requested = Integer.parseInt(declared.toString().trim());
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException(
                        MAX_MESSAGES + " must be a number, got: " + declared);
            }
        }
        if (requested <= 0) {
            throw new IllegalArgumentException(
                    MAX_MESSAGES + " must be at least 1, got: " + requested);
        }
        return requested;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * A record as the selector sees it: what arrived, before the evidence
     * policy decides what may be stored.
     */
    private static final class RecordCandidate implements MessageSelector.Candidate {

        private final ConsumerRecord<byte[], byte[]> record;
        private JsonNode payload;
        private boolean parsed;

        RecordCandidate(ConsumerRecord<byte[], byte[]> record) {
            this.record = record;
        }

        @Override
        public String key() {
            return record.key() == null
                    ? null : new String(record.key(), StandardCharsets.UTF_8);
        }

        @Override
        public String header(String name) {
            var header = record.headers().lastHeader(name);
            return header == null || header.value() == null
                    ? null : new String(header.value(), StandardCharsets.UTF_8);
        }

        @Override
        public String payloadField(String path) {
            if (!parsed) {
                payload = Messages.forSelection(record.value());
                parsed = true;
            }
            return MessageSelector.field(payload, path);
        }
    }
}
