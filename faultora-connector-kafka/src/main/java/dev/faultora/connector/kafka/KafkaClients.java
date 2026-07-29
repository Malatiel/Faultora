package dev.faultora.connector.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;

import java.util.Map;

/**
 * Where the connector's Kafka clients come from.
 * <p>
 * The seam exists so the connector's own behaviour — the observation window,
 * the selector, the evidence policy, the bounds — can be tested without a
 * broker, on a build that stays offline. It is deliberately narrow: the
 * connector decides every configuration value and the factory only constructs,
 * so a test client behaves like the real one in everything the connector
 * depends on.
 */
public interface KafkaClients {

    Producer<byte[], byte[]> producer(Map<String, Object> config);

    Consumer<byte[], byte[]> consumer(Map<String, Object> config);

    /** Clients that talk to a real broker. */
    static KafkaClients real() {
        return new KafkaClients() {
            @Override
            public Producer<byte[], byte[]> producer(Map<String, Object> config) {
                return new KafkaProducer<>(config);
            }

            @Override
            public Consumer<byte[], byte[]> consumer(Map<String, Object> config) {
                return new KafkaConsumer<>(config);
            }
        };
    }
}
