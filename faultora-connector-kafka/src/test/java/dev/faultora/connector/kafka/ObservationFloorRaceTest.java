package dev.faultora.connector.kafka;

import dev.faultora.net.HostPolicy;
import dev.faultora.spi.evidence.MessageEvidence;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationFloorRaceTest {

    @ParameterizedTest
    @CsvSource({"0, false", "2, false", "2, true"})
    void aMessageArrivingDuringTheFloorLookupIsStillObserved(
            long history, boolean timestampLookupSeesArrival) {
        try (ArrivingConsumer consumer = new ArrivingConsumer(history, timestampLookupSeesArrival);
             KafkaConnector connector = new KafkaConnector(HostPolicy.permissive(), new KafkaClients() {
                 @Override
                 public Producer<byte[], byte[]> producer(Map<String, Object> config) {
                     throw new AssertionError("This observation must not publish");
                 }

                 @Override
                 public Consumer<byte[], byte[]> consumer(Map<String, Object> config) {
                     return consumer;
                 }
             })) {
            var target = connector.prepare(KafkaFixtures.target(), KafkaFixtures.context());
            var result = connector.execute(target, KafkaFixtures.consume(KafkaFixtures.TOPIC),
                    Map.of("waitMs", 0), KafkaFixtures.context());

            assertThat(result.isSuccess()).isTrue();
            assertThat(MessageEvidence.observedIn(result.protocolEvidence()))
                    .singleElement().satisfies(message -> {
                        assertThat(message.key()).isEqualTo("current-payment");
                        assertThat(message.offset()).isEqualTo(history);
                    });
        }
    }

    private static final class ArrivingConsumer extends MockConsumer<byte[], byte[]> {
        private final TopicPartition partition = new TopicPartition(KafkaFixtures.TOPIC, 0);
        private final long history;
        private final boolean timestampLookupSeesArrival;

        ArrivingConsumer(long history, boolean timestampLookupSeesArrival) {
            super(OffsetResetStrategy.NONE);
            this.history = history;
            this.timestampLookupSeesArrival = timestampLookupSeesArrival;
            Node node = new Node(1, "broker.example.com", 9092);
            updatePartitions(KafkaFixtures.TOPIC, List.of(new PartitionInfo(
                    KafkaFixtures.TOPIC, 0, node, new Node[]{node}, new Node[]{node})));
            assign(List.of(partition));
            updateBeginningOffsets(Map.of(partition, 0L));
            updateEndOffsets(Map.of(partition, history));
            for (long offset = 0; offset < history; offset++) {
                addRecord(record(offset, "old-payment"));
            }
        }

        @Override
        public synchronized Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
                Map<TopicPartition, Long> timestamps, Duration timeout) {
            // The broker answers from its snapshot, then a worker appends an event
            // before the next metadata call. No scheduling or sleeps are involved.
            Map<TopicPartition, OffsetAndTimestamp> snapshot = new HashMap<>();
            snapshot.put(partition, timestampLookupSeesArrival
                    ? new OffsetAndTimestamp(history, timestamps.get(partition)) : null);
            addRecord(record(history, "current-payment"));
            updateEndOffsets(Map.of(partition, history + 1));
            return snapshot;
        }

        private ConsumerRecord<byte[], byte[]> record(long offset, String key) {
            return new ConsumerRecord<>(KafkaFixtures.TOPIC, 0, offset,
                    key.getBytes(StandardCharsets.UTF_8), "{}".getBytes(StandardCharsets.UTF_8));
        }
    }
}
