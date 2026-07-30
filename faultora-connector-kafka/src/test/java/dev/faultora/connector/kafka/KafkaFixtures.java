package dev.faultora.connector.kafka;

import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.ConnectorContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Catalog pieces the Kafka tests run against, written once. */
final class KafkaFixtures {

    static final String TOPIC = "payment-events";

    private KafkaFixtures() {
    }

    static TargetDefinition target() {
        return new TargetDefinition(
                new TargetId("events"), "Events", "kafka://localhost:9092",
                List.of(new ProtocolId(KafkaConnector.PROTOCOL)), List.of(), Map.of());
    }

    static OperationDefinition publish(String topic) {
        return operation("publish-command", "publish", topic);
    }

    static OperationDefinition consume(String topic) {
        return operation("observe-events", "consume", topic);
    }

    static OperationDefinition operation(String id, String action, String topic) {
        return new OperationDefinition(
                new OperationId(id), new ProtocolId(KafkaConnector.PROTOCOL),
                new TargetId("events"),
                "publish".equals(action)
                        ? SafetyClassification.MUTATING : SafetyClassification.READ_ONLY,
                Map.of(), null, Map.of(),
                Map.of(KafkaOperation.ACTION, action, KafkaOperation.TOPIC, topic));
    }

    /** A policy that keeps evidence, which is what most assertions need. */
    static EvidencePolicy capturing() {
        return new EvidencePolicy(
                true, true, Set.of(), 0, 0, List.of(), Set.of(), "session");
    }

    static ConnectorContext context(EvidencePolicy policy) {
        return context(policy, Map.of());
    }

    /** A context carrying operator-supplied connector configuration. */
    static ConnectorContext context(EvidencePolicy policy, Map<String, Object> config) {
        return new ConnectorContext(policy, name -> null, 1000, 30_000, 60_000, config);
    }

    static ConnectorContext context() {
        return context(capturing());
    }
}
