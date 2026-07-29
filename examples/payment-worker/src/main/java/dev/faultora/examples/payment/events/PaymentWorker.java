package dev.faultora.examples.payment.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An example service that settles payments asked for over Kafka.
 * <p>
 * It exists so a scenario can prove a claim about a distributed system rather
 * than about a single request: a command arrives, an event follows, and the two
 * are connected by a correlation value. That is the shape almost every
 * event-driven workflow has, reduced to the smallest thing that still has it.
 * <p>
 * The interesting property is idempotency. Kafka delivers at least once, so a
 * command can arrive twice — because a producer retried, because a consumer
 * group rebalanced mid-batch, or because a test published it twice on purpose.
 * A correct consumer settles the payment once and emits one event. This worker
 * does that by remembering which payments it has settled.
 * <p>
 * It can also be told <em>not</em> to, with {@link #idempotent} false. That is
 * not a convenience: a reliability test that has never failed proves nothing,
 * and the broken variant is how the suite shows that its duplicate-delivery
 * scenario actually detects a duplicate rather than passing by construction.
 */
public final class PaymentWorker implements AutoCloseable {

    /** Channel commands arrive on. */
    public static final String COMMANDS_TOPIC = "payment-commands";
    /** Channel events are published to. */
    public static final String EVENTS_TOPIC = "payment-events";
    /** Header carrying the value that ties an event to its command. */
    public static final String CORRELATION_HEADER = "correlation-id";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration POLL = Duration.ofMillis(200);

    private final KafkaConsumer<String, String> consumer;
    private final Producer<String, String> producer;
    private final boolean idempotent;
    private final Set<String> settled = ConcurrentHashMap.newKeySet();
    private final CountDownLatch assigned = new CountDownLatch(1);
    private volatile boolean running;
    private Thread worker;

    /**
     * @param bootstrapServers the broker list
     * @param idempotent       whether a repeated command is ignored — false is
     *                         the deliberately broken variant
     */
    public PaymentWorker(String bootstrapServers, boolean idempotent) {
        this.idempotent = idempotent;
        this.consumer = new KafkaConsumer<>(consumerConfig(bootstrapServers));
        this.producer = new KafkaProducer<>(producerConfig(bootstrapServers));
    }

    /**
     * Start consuming, and return once the worker holds its partitions.
     * <p>
     * Waiting for the assignment matters: a caller that published before the
     * worker had joined its group would have the command sit unread until the
     * observation window had already closed, and the flake would look exactly
     * like the defect the scenario is meant to find. The wait is driven by a
     * rebalance callback rather than by polling the consumer, because a
     * consumer belongs to the one thread that polls it.
     */
    public void start() throws InterruptedException {
        running = true;
        consumer.subscribe(List.of(COMMANDS_TOPIC), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (!partitions.isEmpty()) {
                    assigned.countDown();
                }
            }

            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                // Nothing to hand over: this worker commits automatically.
            }
        });
        worker = new Thread(this::consumeUntilStopped, "payment-worker");
        worker.start();
        if (!assigned.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "The worker did not receive a partition assignment within 30s");
        }
    }

    private void consumeUntilStopped() {
        try {
            while (running) {
                ConsumerRecords<String, String> commands = consumer.poll(POLL);
                for (ConsumerRecord<String, String> command : commands) {
                    settle(command);
                }
            }
        } catch (WakeupException stopping) {
            // close() asked for this.
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    private void settle(ConsumerRecord<String, String> command) {
        JsonNode payload;
        try {
            payload = MAPPER.readTree(command.value());
        } catch (Exception malformed) {
            return;
        }
        JsonNode paymentIdNode = payload.get("paymentId");
        if (paymentIdNode == null || paymentIdNode.asText().isBlank()) {
            return;
        }
        String paymentId = paymentIdNode.asText();

        // The whole point: a command seen twice settles one payment.
        if (idempotent && !settled.add(paymentId)) {
            return;
        }

        ObjectNode event = MAPPER.createObjectNode();
        event.put("paymentId", paymentId);
        event.put("status", "settled");
        if (payload.hasNonNull("amount")) {
            event.set("amount", payload.get("amount"));
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(
                EVENTS_TOPIC, paymentId, event.toString());
        record.headers().add(new RecordHeader(
                CORRELATION_HEADER, paymentId.getBytes(StandardCharsets.UTF_8)));
        producer.send(record);
        producer.flush();
    }

    /**
     * How many distinct payments this worker has settled.
     * <p>
     * The broken variant keeps no such record — settling twice is exactly what
     * it does — so it reports zero however much work it did.
     */
    public int settledCount() {
        return settled.size();
    }

    @Override
    public void close() {
        running = false;
        if (worker != null) {
            consumer.wakeup();
            try {
                worker.join(Duration.ofSeconds(10).toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        producer.close(Duration.ofSeconds(5));
    }

    private static Properties consumerConfig(String bootstrapServers) {
        Properties config = new Properties();
        config.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "payment-worker",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer",
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer"));
        return config;
    }

    private static Properties producerConfig(String bootstrapServers) {
        Properties config = new Properties();
        config.putAll(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer",
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer"));
        return config;
    }
}
