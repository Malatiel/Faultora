package dev.faultora.connector.kafka;

import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.spi.contract.Connector;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One target's clients and observation floors, for the length of a run.
 * <p>
 * The floor is the point in a topic a run is willing to look back to, and it is
 * <em>when the run started</em>, resolved through the broker's own timestamps.
 * That is the only anchor that works for a channel the run does not write to:
 * the effect a scenario is waiting for is produced by the application under
 * test, and by the time the run first reads that channel the event it is
 * looking for has usually already been written. An anchor taken at that first
 * read would sit above the event and never see it.
 * <p>
 * Resolution stays lazy — a topic is looked up the first time it is used — but
 * what is resolved is a time, not a position, so the answer does not depend on
 * when the lookup happened.
 * <p>
 * Clients are created on first use, so a run that only publishes never opens a
 * consumer, and a target that is never used opens nothing at all.
 */
final class KafkaPreparedTarget implements Connector.PreparedTarget {

    /** How long metadata lookups wait before the operation gives up. */
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(15);

    private final TargetDefinition target;
    private final BootstrapServers bootstrap;
    private final KafkaClients clients;
    private final Map<String, Object> producerConfig;
    private final Map<String, Object> consumerConfig;
    private final Map<String, Map<TopicPartition, Long>> floors = new ConcurrentHashMap<>();
    private final long observeFromMs;

    private Producer<byte[], byte[]> producer;
    private Consumer<byte[], byte[]> consumer;

    KafkaPreparedTarget(
            TargetDefinition target,
            BootstrapServers bootstrap,
            KafkaClients clients,
            Map<String, Object> producerConfig,
            Map<String, Object> consumerConfig,
            long observeFromMs
    ) {
        this.observeFromMs = observeFromMs;
        this.target = target;
        this.bootstrap = bootstrap;
        this.clients = clients;
        this.producerConfig = Map.copyOf(producerConfig);
        this.consumerConfig = Map.copyOf(consumerConfig);
    }

    @Override
    public TargetDefinition targetDefinition() {
        return target;
    }

    BootstrapServers bootstrap() {
        return bootstrap;
    }

    synchronized Producer<byte[], byte[]> producer() {
        if (producer == null) {
            producer = clients.producer(producerConfig);
        }
        return producer;
    }

    synchronized Consumer<byte[], byte[]> consumer() {
        if (consumer == null) {
            consumer = clients.consumer(consumerConfig);
        }
        return consumer;
    }

    /**
     * The partitions of a topic, in a stable order.
     *
     * @throws IllegalStateException when the broker knows no such topic
     */
    List<TopicPartition> partitions(String topic) {
        List<PartitionInfo> known = consumer().partitionsFor(topic);
        if (known == null || known.isEmpty()) {
            throw new IllegalStateException("Kafka knows no topic named '" + topic + "'");
        }
        List<TopicPartition> partitions = new ArrayList<>(known.size());
        known.stream()
                .sorted(java.util.Comparator.comparingInt(PartitionInfo::partition))
                .forEach(info -> partitions.add(
                        new TopicPartition(info.topic(), info.partition())));
        return partitions;
    }

    /**
     * The floor for a topic: the first message written at or after the run
     * began.
     * <p>
     * A partition holding nothing that recent has no such offset, and its floor
     * is its current end — there is nothing to look back at, and everything the
     * run cares about is still to come.
     */
    Map<TopicPartition, Long> floor(String topic) {
        return floors.computeIfAbsent(topic, name -> {
            List<TopicPartition> partitions = partitions(name);
            Map<TopicPartition, Long> askedAt = new LinkedHashMap<>();
            partitions.forEach(partition -> askedAt.put(partition, observeFromMs));

            Map<TopicPartition, OffsetAndTimestamp> byTime =
                    consumer().offsetsForTimes(askedAt, METADATA_TIMEOUT);
            Map<TopicPartition, Long> ends = consumer().endOffsets(partitions, METADATA_TIMEOUT);

            Map<TopicPartition, Long> floor = new LinkedHashMap<>();
            for (TopicPartition partition : partitions) {
                OffsetAndTimestamp first = byTime == null ? null : byTime.get(partition);
                floor.put(partition, first != null
                        ? first.offset() : ends.getOrDefault(partition, 0L));
            }
            return Map.copyOf(floor);
        });
    }

    /** Release both clients. Idempotent: a run may be closed more than once. */
    synchronized void close() {
        if (producer != null) {
            try {
                producer.close(Duration.ofSeconds(5));
            } finally {
                producer = null;
            }
        }
        if (consumer != null) {
            try {
                consumer.close(Duration.ofSeconds(5));
            } finally {
                consumer = null;
            }
        }
    }
}
