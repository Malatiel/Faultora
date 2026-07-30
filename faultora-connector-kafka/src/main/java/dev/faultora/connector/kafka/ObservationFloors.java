package dev.faultora.connector.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Where each channel's observations start, for the length of a run.
 * <p>
 * The floor is a time — the moment the run began — so every observation of a
 * channel resolves to the same position however often it is taken. That is why
 * this can be a cache: it saves two metadata round trips per poll, and a
 * polling block takes many, but it changes no answer.
 * <p>
 * It lives here rather than on a prepared target because it is a property of
 * the run, not of a client. A prepared target lasts one operation — the engine
 * prepares and releases around each invocation — so a cache held there would be
 * rebuilt on every poll and would tie the run's idea of "where we started" to
 * the lifetime of a socket.
 */
final class ObservationFloors {

    /** How long a metadata lookup waits before the operation gives up. */
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(15);

    /** A channel of one target: two targets may name the same topic. */
    private record Channel(String targetId, String topic) {}

    private final long observeFromMs;
    private final ConcurrentMap<Channel, Map<TopicPartition, Long>> floors =
            new ConcurrentHashMap<>();

    ObservationFloors(long observeFromMs) {
        this.observeFromMs = observeFromMs;
    }

    /**
     * The floor for a channel: the first message written at or after the run
     * began.
     * <p>
     * A partition holding nothing that recent has no such offset, and its floor
     * is its current end — there is nothing to look back at, and everything the
     * run cares about is still to come.
     * <p>
     * Two concurrent steps may both resolve the same channel. Each does so with
     * its own client and from the same timestamp, so they compute the same
     * answer and whichever stores it first is as good as the other; resolving
     * inside the map's own locking would make one step's broker call block a
     * step reading a different channel.
     */
    Map<TopicPartition, Long> of(
            String targetId,
            String topic,
            Consumer<byte[], byte[]> consumer,
            List<TopicPartition> partitions
    ) {
        Channel channel = new Channel(targetId, topic);
        Map<TopicPartition, Long> known = floors.get(channel);
        if (known != null) {
            return known;
        }
        Map<TopicPartition, Long> resolved = resolve(consumer, partitions);
        Map<TopicPartition, Long> stored = floors.putIfAbsent(channel, resolved);
        return stored != null ? stored : resolved;
    }

    private Map<TopicPartition, Long> resolve(
            Consumer<byte[], byte[]> consumer, List<TopicPartition> partitions) {
        Map<TopicPartition, Long> askedAt = new LinkedHashMap<>();
        partitions.forEach(partition -> askedAt.put(partition, observeFromMs));

        Map<TopicPartition, OffsetAndTimestamp> byTime =
                consumer.offsetsForTimes(askedAt, METADATA_TIMEOUT);
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, METADATA_TIMEOUT);

        Map<TopicPartition, Long> floor = new LinkedHashMap<>();
        for (TopicPartition partition : partitions) {
            OffsetAndTimestamp first = byTime == null ? null : byTime.get(partition);
            floor.put(partition, first != null
                    ? first.offset() : ends.getOrDefault(partition, 0L));
        }
        return Map.copyOf(floor);
    }
}
