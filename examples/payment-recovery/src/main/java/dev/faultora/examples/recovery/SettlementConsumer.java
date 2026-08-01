package dev.faultora.examples.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Settles a payment once, however many times it is asked to.
 * <p>
 * Everything a settlement does happens in one transaction: the message key is
 * recorded, the ledger entries are written, the payment is marked settled. A
 * redelivery collides on the recorded key, the transaction rolls back, and the
 * ledger is untouched. That is what makes the consumer idempotent — not a set
 * held in memory, which a restart would forget, but a row committed with the
 * work it describes.
 * <p>
 * Two properties can be removed from it, each by one flag, because a gate
 * scenario that has never failed proves nothing:
 * <ul>
 *   <li>{@link SystemConfig#idempotentConsumer()} false skips the key
 *       altogether, so a redelivery books the payment a second time;</li>
 *   <li>{@link SystemConfig#doubleEntryLedger()} false writes the debit and
 *       not the credit, so the ledger no longer sums to zero — the invariant
 *       no single request can see.</li>
 * </ul>
 */
final class SettlementConsumer implements AutoCloseable {

    /** Channel settlement events are published to. */
    static final String EVENTS_TOPIC = "payment-events";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration POLL = Duration.ofMillis(200);

    private final Database database;
    private final SystemConfig config;
    private final Provider provider;
    private final KafkaConsumer<String, String> consumer;
    private final Producer<String, String> producer;
    private final CountDownLatch assigned = new CountDownLatch(1);
    private volatile boolean running;
    private Thread worker;

    SettlementConsumer(
            Database database, SystemConfig config, Provider provider,
            String bootstrapServers) {
        this.database = database;
        this.config = config;
        this.provider = provider;
        this.consumer = new KafkaConsumer<>(consumerConfig(bootstrapServers));
        this.producer = new KafkaProducer<>(producerConfig(bootstrapServers));
    }

    /**
     * Start consuming, and return once the consumer holds its partitions.
     * <p>
     * A caller that published before the assignment arrived would have the
     * command sit unread until the observation window had closed, and the flake
     * would look exactly like the defect a scenario is meant to find.
     */
    void start() throws InterruptedException {
        running = true;
        consumer.subscribe(List.of(OutboxRelay.COMMANDS_TOPIC),
                new ConsumerRebalanceListener() {
                    @Override
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        if (!partitions.isEmpty()) {
                            assigned.countDown();
                        }
                    }

                    @Override
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                        // Nothing to hand over: this consumer commits automatically.
                    }
                });
        worker = new Thread(this::consumeUntilStopped, "settlement-consumer");
        worker.start();
        if (!assigned.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "The settlement consumer received no partition assignment within 30s");
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
        if (!payload.hasNonNull("paymentId") || !payload.hasNonNull("amount")) {
            return;
        }
        String paymentId = payload.get("paymentId").asText();
        long amount = payload.get("amount").asLong();
        String messageKey = command.topic() + ":" + paymentId;

        // The charge leaves this system before anything is booked. A provider
        // that accepts it and loses the response leaves the outcome unknown,
        // which is the one thing this consumer cannot resolve on its own.
        Provider.Outcome outcome = provider.charge(paymentId, amount);
        if (outcome != Provider.Outcome.ACCEPTED) {
            // A refused charge is money that never moved, and an unknown one is
            // money that may have. Neither is booked here: the first because
            // there is nothing to book, the second because only the provider
            // can say, and reconciliation is what asks it.
            mark(paymentId, outcome == Provider.Outcome.REFUSED ? "refused" : "unknown");
            return;
        }

        try (Connection connection = database.transaction()) {
            try {
                if (config.idempotentConsumer() && !claim(connection, messageKey, paymentId)) {
                    connection.rollback();
                    countRedelivery(messageKey);
                    return;
                }
                book(connection, paymentId, amount);
                markSettled(connection, paymentId);
                connection.commit();
            } catch (SQLException failed) {
                connection.rollback();
                lost(messageKey, failed);
                return;
            }
        } catch (SQLException unavailable) {
            lost(messageKey, unavailable);
            return;
        }
        announce(paymentId, amount);
    }

    /**
     * A command this consumer dropped, and why.
     * <p>
     * Offsets are committed automatically here, so a command whose transaction
     * failed is gone: the offset moves whether or not anything was booked. That
     * is a real hole, and it is left open on purpose — a reference system small
     * enough to read should not carry a retry mechanism nobody is testing — but
     * it is not left silent. A production consumer commits the offset only
     * after the transaction that consumed it, which is the same trade the
     * outbox relay makes at the other end of this pipeline.
     */
    private static void lost(String messageKey, SQLException cause) {
        System.err.println("payment-recovery: dropped " + messageKey
                + " after a database failure: " + cause.getMessage());
    }

    /**
     * Record that this message is being handled, or report that it already was.
     * <p>
     * The insert is the claim. Two deliveries race it inside the database
     * rather than inside this process, so the answer is the same whether the
     * duplicate arrives on another partition, another instance, or a week later.
     * <p>
     * The claim is an insert and nothing else. Counting the repeat here was the
     * first attempt and it counted nothing: the transaction that discovers a
     * duplicate is the transaction that rolls back, and it took the increment
     * with it. So the count is written afterwards, on its own.
     */
    private boolean claim(Connection connection, String messageKey, String paymentId)
            throws SQLException {
        try (PreparedStatement claim = connection.prepareStatement(
                "INSERT INTO processed_messages (message_key, payment_id) VALUES (?, ?) "
                        + "ON CONFLICT DO NOTHING")) {
            claim.setString(1, messageKey);
            claim.setString(2, paymentId);
            return claim.executeUpdate() == 1;
        }
    }

    /**
     * Record that a command arrived again, after the work has been declined.
     * <p>
     * Bookkeeping rather than correctness: nothing depends on this count being
     * right, and the settlement it belongs to has already been rolled back. It
     * exists so a scenario can assert "processed once, seen twice" instead of
     * inferring the second half from the absence of a second effect — which
     * would be inferring the thing under test from the thing testing it.
     */
    private void countRedelivery(String messageKey) {
        try (Connection connection = database.connection();
             PreparedStatement count = connection.prepareStatement(
                     "UPDATE processed_messages SET deliveries = deliveries + 1 "
                             + "WHERE message_key = ?")) {
            count.setString(1, messageKey);
            count.executeUpdate();
        } catch (SQLException unavailable) {
            // A miscount changes nothing about what was booked.
        }
    }

    private void book(Connection connection, String paymentId, long amount)
            throws SQLException {
        try (PreparedStatement entry = connection.prepareStatement(
                "INSERT INTO ledger_entries (payment_id, account, amount) VALUES (?, ?, ?)")) {
            entry.setString(1, paymentId);
            entry.setString(2, "receivable");
            entry.setLong(3, amount);
            entry.executeUpdate();

            if (!config.doubleEntryLedger()) {
                // The broken system: one side of the booking, so the ledger
                // sums to the amount rather than to zero.
                return;
            }
            entry.setString(1, paymentId);
            entry.setString(2, "revenue");
            entry.setLong(3, -amount);
            entry.executeUpdate();
        }
    }

    private void markSettled(Connection connection, String paymentId) throws SQLException {
        try (PreparedStatement mark = connection.prepareStatement(
                "UPDATE payments SET status = 'settled' WHERE payment_id = ?")) {
            mark.setString(1, paymentId);
            mark.executeUpdate();
        }
    }

    /**
     * Record what this system currently believes, for whoever asks the API.
     * <p>
     * A failure here is survivable and is not silently ignored on the strength
     * of that: the reconciliation worker looks for payments with no booking
     * rather than for this status, so a status that never got written delays
     * nothing. What it costs is an honest answer to {@code GET /payments/{id}}
     * until the booking lands.
     */
    private void mark(String paymentId, String status) {
        try (Connection connection = database.connection();
             PreparedStatement mark = connection.prepareStatement(
                     "UPDATE payments SET status = ? WHERE payment_id = ?")) {
            mark.setString(1, status);
            mark.setString(2, paymentId);
            mark.executeUpdate();
        } catch (SQLException unavailable) {
            // Next pass, or never: the ledger is what anything depends on.
        }
    }

    /** Tell the world what was settled, after the transaction that settled it. */
    private void announce(String paymentId, long amount) {
        String event = MAPPER.createObjectNode()
                .put("paymentId", paymentId)
                .put("status", "settled")
                .put("amount", amount)
                .toString();
        ProducerRecord<String, String> record =
                new ProducerRecord<>(EVENTS_TOPIC, paymentId, event);
        record.headers().add(new RecordHeader(OutboxRelay.CORRELATION_HEADER,
                paymentId.getBytes(StandardCharsets.UTF_8)));
        producer.send(record);
        producer.flush();
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
                ConsumerConfig.GROUP_ID_CONFIG, "settlement-consumer",
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
