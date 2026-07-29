package dev.faultora.connector.kafka;

import dev.faultora.model.catalog.OperationDefinition;

import java.util.Locale;
import java.util.Map;

/**
 * What a catalog operation says about the Kafka work it stands for.
 * <p>
 * Two actions exist, and they are named from the point of view of the run, not
 * of the application under test: Faultora <em>publishes</em> to a channel the
 * application receives on, and <em>consumes</em> from a channel it sends on.
 * Importers are responsible for that inversion; by the time an operation
 * reaches this connector the direction is already Faultora's own.
 *
 * @param action        publish or consume
 * @param topic         the Kafka topic
 * @param correlationId where a message carries its correlation value, in
 *                      AsyncAPI's runtime-expression form
 *                      ({@code $message.header#/id} or
 *                      {@code $message.payload#/orderId}), or null when the
 *                      contract declares none
 */
record KafkaOperation(Action action, String topic, String correlationId) {

    /** What the run does with the channel. */
    enum Action {
        /** The run writes a message the application under test will receive. */
        PUBLISH,
        /** The run reads messages the application under test has sent. */
        CONSUME
    }

    static final String ACTION = "action";
    static final String TOPIC = "topic";
    static final String CORRELATION_ID = "correlationId";

    /**
     * Read the Kafka metadata of a catalog operation.
     *
     * @throws IllegalArgumentException when the operation does not describe a
     *                                  Kafka action this connector can perform
     */
    static KafkaOperation of(OperationDefinition operation) {
        Map<String, Object> metadata = operation.protocolMetadata() == null
                ? Map.of() : operation.protocolMetadata();

        String declared = string(metadata.get(ACTION));
        Action action = switch (declared == null ? "" : declared.toLowerCase(Locale.ROOT)) {
            case "publish" -> Action.PUBLISH;
            case "consume" -> Action.CONSUME;
            default -> throw new IllegalArgumentException(
                    "Operation " + operation.id().value() + " declares action '" + declared
                            + "'; a Kafka operation is either 'publish' or 'consume'");
        };

        String topic = string(metadata.get(TOPIC));
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "Operation " + operation.id().value() + " names no Kafka topic");
        }
        return new KafkaOperation(action, topic, string(metadata.get(CORRELATION_ID)));
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
