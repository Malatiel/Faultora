package dev.faultora.connector.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A broker that exists only in this JVM.
 * <p>
 * It models a broker rather than a client: topics and messages are declared
 * once, and every call to {@link #consumer} builds a fresh consumer over the
 * same state. That matters beyond tidiness — the connector now opens a consumer
 * per operation because a Kafka consumer belongs to one thread, and a fake that
 * handed the same instance to every caller could not tell a fixed connector from
 * a broken one.
 * <p>
 * What this cannot prove is that a real broker behaves as assumed; that is what
 * the end-to-end suite against a disposable Kafka is for.
 */
final class FakeKafka implements KafkaClients {

    private static final Node NODE = new Node(1, "broker.example.com", 9092);

    /** Topics the broker knows, and how much history each holds. */
    private final Map<String, Long> history = new LinkedHashMap<>();
    private final List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
    private final AtomicInteger consumersOpened = new AtomicInteger();
    private final List<MockConsumer<byte[], byte[]>> opened = new ArrayList<>();

    private final MockProducer<byte[], byte[]> producer =
            new MockProducer<>(true, new ByteArraySerializer(), new ByteArraySerializer());

    @Override
    public Producer<byte[], byte[]> producer(Map<String, Object> config) {
        return producer;
    }

    @Override
    public synchronized Consumer<byte[], byte[]> consumer(Map<String, Object> config) {
        consumersOpened.incrementAndGet();
        TimeAwareConsumer consumer = new TimeAwareConsumer();
        List<TopicPartition> partitions = new ArrayList<>();
        history.forEach((topic, historyLength) -> {
            TopicPartition partition = new TopicPartition(topic, 0);
            partitions.add(partition);
            consumer.updatePartitions(topic, List.of(
                    new PartitionInfo(topic, 0, NODE, new Node[]{NODE}, new Node[]{NODE})));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
            consumer.updateEndOffsets(Map.of(partition, endOffsetOf(topic, historyLength)));
            consumer.floorAt(partition, historyLength);
        });
        // A mock consumer refuses records for partitions it has not been given,
        // and the connector assigns the same set a moment later.
        consumer.assign(partitions);
        records.forEach(consumer::addRecord);
        opened.add(consumer);
        return consumer;
    }

    private long endOffsetOf(String topic, long historyLength) {
        long highest = historyLength;
        for (ConsumerRecord<byte[], byte[]> record : records) {
            if (record.topic().equals(topic)) {
                highest = Math.max(highest, record.offset() + 1);
            }
        }
        return highest;
    }

    MockProducer<byte[], byte[]> published() {
        return producer;
    }

    /** How many consumers the connector has opened against this broker. */
    int consumersOpened() {
        return consumersOpened.get();
    }

    /** How many of the consumers it opened are still open. */
    long consumersStillOpen() {
        synchronized (this) {
            return opened.stream().filter(consumer -> !consumer.closed()).count();
        }
    }

    /**
     * Declare a single-partition topic that already holds {@code history}
     * messages, all of them written before this run began.
     * <p>
     * That count is the offset the broker answers with when asked where the
     * run's own window starts — which is how a real broker answers a timestamp
     * lookup for a topic whose older records predate the run.
     */
    FakeKafka topic(String name, long history) {
        this.history.put(name, history);
        return this;
    }

    /** Put a message on the topic at an explicit offset. */
    FakeKafka message(String topic, long offset, String key, String payload) {
        return message(topic, offset, key, payload, Map.of());
    }

    FakeKafka message(
            String topic, long offset, String key, String payload,
            Map<String, String> headers) {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                topic, 0, offset, System.currentTimeMillis(),
                TimestampType.CREATE_TIME,
                key == null ? 0 : key.length(), payload.length(),
                key == null ? null : key.getBytes(StandardCharsets.UTF_8),
                payload.getBytes(StandardCharsets.UTF_8),
                new org.apache.kafka.common.header.internals.RecordHeaders(),
                Optional.empty());
        headers.forEach((name, value) -> record.headers().add(
                name, value.getBytes(StandardCharsets.UTF_8)));
        records.add(record);
        return this;
    }

    /**
     * A mock consumer that can answer "where does the run's window start".
     * <p>
     * The connector resolves its floor through a timestamp lookup, which is the
     * only anchor that works for a channel the run does not write to. The stock
     * mock does not implement that call, so answering it here is what keeps
     * these tests exercising the path a real broker takes rather than a
     * fallback.
     */
    private static final class TimeAwareConsumer extends MockConsumer<byte[], byte[]> {

        private final Map<TopicPartition, Long> floors = new HashMap<>();

        TimeAwareConsumer() {
            super(OffsetResetStrategy.NONE);
        }

        void floorAt(TopicPartition partition, long offset) {
            floors.put(partition, offset);
        }

        @Override
        public synchronized Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
                Map<TopicPartition, Long> timestampsToSearch) {
            Map<TopicPartition, OffsetAndTimestamp> found = new HashMap<>();
            timestampsToSearch.forEach((partition, timestamp) -> {
                Long offset = floors.get(partition);
                found.put(partition, offset == null
                        ? null : new OffsetAndTimestamp(offset, timestamp));
            });
            return found;
        }

        @Override
        public synchronized Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
                Map<TopicPartition, Long> timestampsToSearch, Duration timeout) {
            return offsetsForTimes(timestampsToSearch);
        }
    }
}
