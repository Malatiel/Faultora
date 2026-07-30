package dev.faultora.connector.kafka;

import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.spi.contract.Connector;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One operation's clients on one target.
 * <p>
 * A prepared target lasts a single invocation, and the two clients it needs
 * have opposite lifetimes because Kafka gives them opposite guarantees:
 * <ul>
 *   <li>a <b>producer</b> is thread-safe and meant to be shared, so it belongs
 *       to the run and is handed here rather than created here — publishing
 *       from a parallel group opens one connection, not one per step;</li>
 *   <li>a <b>consumer</b> belongs to the single thread that polls it. Sharing
 *       one between concurrent steps is not slow but wrong: the client refuses
 *       concurrent access outright, and releasing one step's target would close
 *       the client another step was reading from. So each prepared target
 *       creates its own, lazily, and closes only that.</li>
 * </ul>
 * The floors an observation reads from are the run's, not this target's — see
 * {@link ObservationFloors}.
 */
final class KafkaPreparedTarget implements Connector.PreparedTarget {

    private final TargetDefinition target;
    private final BootstrapServers bootstrap;
    private final Supplier<Producer<byte[], byte[]>> sharedProducer;
    private final Supplier<Consumer<byte[], byte[]>> newConsumer;
    private final ObservationFloors floors;

    private Consumer<byte[], byte[]> consumer;

    KafkaPreparedTarget(
            TargetDefinition target,
            BootstrapServers bootstrap,
            Supplier<Producer<byte[], byte[]>> sharedProducer,
            Supplier<Consumer<byte[], byte[]>> newConsumer,
            ObservationFloors floors
    ) {
        this.target = target;
        this.bootstrap = bootstrap;
        this.sharedProducer = sharedProducer;
        this.newConsumer = newConsumer;
        this.floors = floors;
    }

    @Override
    public TargetDefinition targetDefinition() {
        return target;
    }

    BootstrapServers bootstrap() {
        return bootstrap;
    }

    /** The run's producer for this target. */
    Producer<byte[], byte[]> producer() {
        return sharedProducer.get();
    }

    /** This operation's own consumer, created when it is first needed. */
    Consumer<byte[], byte[]> consumer() {
        if (consumer == null) {
            consumer = newConsumer.get();
        }
        return consumer;
    }

    /**
     * The partitions of a topic, in a stable order.
     * <p>
     * Asked of the broker every time rather than cached: partitions can be
     * added to a topic while a run is in progress, and a cache would go on
     * reading the partitions that existed when the run started, silently
     * missing everything written to a new one.
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
                .sorted(Comparator.comparingInt(PartitionInfo::partition))
                .forEach(info -> partitions.add(
                        new TopicPartition(info.topic(), info.partition())));
        return partitions;
    }

    /** Where an observation of this topic starts. */
    Map<TopicPartition, Long> floor(String topic) {
        return floors.of(target.id().value(), topic, consumer(), partitions(topic));
    }

    /**
     * Release what this operation owns, and only that. The producer outlives it
     * and is closed when the run is.
     */
    void close() {
        if (consumer != null) {
            try {
                consumer.close(Duration.ofSeconds(5));
            } finally {
                consumer = null;
            }
        }
    }
}
