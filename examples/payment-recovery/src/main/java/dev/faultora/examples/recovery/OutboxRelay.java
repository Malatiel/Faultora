package dev.faultora.examples.recovery;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/**
 * Publishes what the outbox holds, and marks what it published.
 * <p>
 * The relay is the part of the outbox pattern that is allowed to be
 * at-least-once. It reads a committed row, publishes it, and marks it — and a
 * crash between the publish and the mark republishes the message on the next
 * pass. That duplicate is not a defect being tolerated; it is the trade the
 * pattern makes, and it is why the consumer downstream has to be idempotent.
 * The duplicate-delivery scenario exists to prove that it is.
 */
final class OutboxRelay implements AutoCloseable {

    /** Channel the relay publishes settle commands to. */
    static final String COMMANDS_TOPIC = "payment-commands";

    /** Header carrying the value that ties an event to its payment. */
    static final String CORRELATION_HEADER = "correlation-id";

    private static final Duration INTERVAL = Duration.ofMillis(100);

    private final Database database;
    private final Producer<String, String> producer;
    private volatile boolean running;
    private Thread relay;

    OutboxRelay(Database database, String bootstrapServers) {
        this.database = database;
        this.producer = new KafkaProducer<>(producerConfig(bootstrapServers));
    }

    void start() {
        running = true;
        relay = new Thread(this::publishUntilStopped, "outbox-relay");
        relay.setDaemon(true);
        relay.start();
    }

    private void publishUntilStopped() {
        while (running) {
            try {
                publishPending();
                Thread.sleep(INTERVAL.toMillis());
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            } catch (SQLException retryNextPass) {
                // The database is the source of truth and it is still there
                // next time. A relay that gave up here would turn a moment of
                // contention into a lost event.
            }
        }
    }

    private void publishPending() throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement pending = connection.prepareStatement(
                     "SELECT id, payment_id, payload FROM outbox "
                             + "WHERE published_at IS NULL ORDER BY id");
             ResultSet rows = pending.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                String paymentId = rows.getString("payment_id");
                publish(paymentId, rows.getString("payload"));
                markPublished(connection, id);
            }
        }
    }

    private void publish(String paymentId, String payload) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(COMMANDS_TOPIC, paymentId, payload);
        record.headers().add(new RecordHeader(
                CORRELATION_HEADER, paymentId.getBytes(StandardCharsets.UTF_8)));
        producer.send(record);
        producer.flush();
    }

    private void markPublished(Connection connection, long id) throws SQLException {
        try (PreparedStatement mark = connection.prepareStatement(
                "UPDATE outbox SET published_at = now() WHERE id = ?")) {
            mark.setLong(1, id);
            mark.executeUpdate();
        }
    }

    @Override
    public void close() {
        running = false;
        if (relay != null) {
            relay.interrupt();
            try {
                relay.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        producer.close(Duration.ofSeconds(5));
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
