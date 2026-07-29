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
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A broker that exists only in this JVM.
 * <p>
 * The connector's own behaviour — the observation window, the selector, the
 * evidence policy, the bounds — is decided before any byte reaches a broker, so
 * it is tested here, on a build that needs no Docker and no network. What this
 * cannot prove is that a real broker behaves as assumed; that is what the
 * end-to-end suite against a disposable Kafka is for.
 */
final class FakeKafka implements KafkaClients {

    private static final Node NODE = new Node(1, "broker.example.com", 9092);

    private final TimeAwareConsumer consumer = new TimeAwareConsumer();
    private final MockProducer<byte[], byte[]> producer =
            new MockProducer<>(true, new ByteArraySerializer(), new ByteArraySerializer());

    @Override
    public Producer<byte[], byte[]> producer(Map<String, Object> config) {
        return producer;
    }

    @Override
    public Consumer<byte[], byte[]> consumer(Map<String, Object> config) {
        return consumer;
    }

    MockProducer<byte[], byte[]> published() {
        return producer;
    }

    /**
     * Declare a single-partition topic that already holds {@code history}
     * messages, all of them written before this run began.
     * <p>
     * That count is both the partition's end offset and the offset the broker
     * answers with when asked where the run's own window starts — which is how
     * a real broker answers a timestamp lookup for a topic whose older records
     * predate the run.
     */
    FakeKafka topic(String name, long history) {
        TopicPartition partition = new TopicPartition(name, 0);
        consumer.updatePartitions(name, List.of(
                new PartitionInfo(name, 0, NODE, new Node[]{NODE}, new Node[]{NODE})));
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, history));
        consumer.floorAt(partition, history);
        // MockConsumer refuses records for partitions it has not been given, and
        // the connector assigns the same set a moment later.
        consumer.assign(List.of(partition));
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
                org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                key == null ? 0 : key.length(), payload.length(),
                key == null ? null : key.getBytes(StandardCharsets.UTF_8),
                payload.getBytes(StandardCharsets.UTF_8),
                new org.apache.kafka.common.header.internals.RecordHeaders(),
                java.util.Optional.empty());
        headers.forEach((name, value) -> record.headers().add(
                name, value.getBytes(StandardCharsets.UTF_8)));
        consumer.addRecord(record);
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

        private final Map<TopicPartition, Long> floors = new java.util.HashMap<>();

        TimeAwareConsumer() {
            super(OffsetResetStrategy.NONE);
        }

        void floorAt(TopicPartition partition, long offset) {
            floors.put(partition, offset);
        }

        @Override
        public synchronized Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
                Map<TopicPartition, Long> timestampsToSearch) {
            Map<TopicPartition, OffsetAndTimestamp> found = new java.util.HashMap<>();
            timestampsToSearch.forEach((partition, timestamp) -> {
                Long offset = floors.get(partition);
                found.put(partition, offset == null
                        ? null : new OffsetAndTimestamp(offset, timestamp));
            });
            return found;
        }

        @Override
        public synchronized Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
                Map<TopicPartition, Long> timestampsToSearch, java.time.Duration timeout) {
            return offsetsForTimes(timestampsToSearch);
        }
    }
}
