package dev.faultora.importer.asyncapi;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.importer.source.SourceDocument;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.model.identifier.TargetId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns AsyncAPI operations into catalog operations.
 * <p>
 * The work is mostly resolution: an operation points at a channel, a channel
 * points at messages, a message points at a payload schema, and any of those
 * may be a reference. What is left after resolving is the small set of facts a
 * connector needs — which way, which topic, which key, where the correlation
 * value lives — and the schema a payload must satisfy.
 */
final class OperationCollector {

    /** Protocol identifier the Kafka connector answers to. */
    private static final String KAFKA = "kafka";

    private final JsonNode root;
    private final List<TargetDefinition> targets;
    private final Map<SchemaId, DataSchema> schemas;
    private final List<String> warnings;

    OperationCollector(
            JsonNode root,
            List<TargetDefinition> targets,
            Map<SchemaId, DataSchema> schemas,
            List<String> warnings
    ) {
        this.root = root;
        this.targets = targets;
        this.schemas = schemas;
        this.warnings = warnings;
    }

    List<OperationDefinition> collect() {
        JsonNode operations = SourceDocument.object(root, "operations");
        if (operations == null || targets.isEmpty()) {
            return List.of();
        }
        TargetId target = targets.get(0).id();

        List<OperationDefinition> collected = new ArrayList<>();
        operations.properties().forEach(entry -> {
            OperationDefinition definition = operationOf(entry.getKey(), entry.getValue(), target);
            if (definition != null) {
                collected.add(definition);
            }
        });
        return List.copyOf(collected);
    }

    private OperationDefinition operationOf(String id, JsonNode declared, TargetId target) {
        JsonNode operation = SourceDocument.resolve(root, declared);

        String action = SourceDocument.text(operation, "action");
        AsyncApiImporter.Direction direction = AsyncApiImporter.Direction.of(action);
        if (direction == null) {
            warnings.add("Operation '" + id + "' declares action '" + action
                    + "', which is neither send nor receive; it was skipped");
            return null;
        }

        JsonNode channelReference = operation.get("channel");
        if (channelReference == null) {
            warnings.add("Operation '" + id + "' names no channel and was skipped");
            return null;
        }
        JsonNode channel = SourceDocument.resolve(root, channelReference);
        String topic = topicOf(channel, channelReference);
        if (topic == null) {
            warnings.add("Operation '" + id + "' is on a channel with no address "
                    + "and was skipped");
            return null;
        }

        JsonNode message = messageOf(operation, channel);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", direction.wireName());
        metadata.put("topic", topic);
        String correlationId = correlationIdOf(message);
        if (correlationId != null) {
            metadata.put("correlationId", correlationId);
        }
        kafkaBindings(operation, channel, id).forEach(metadata::put);

        SchemaId payload = payloadSchemaOf(message, id);
        boolean publishes = direction == AsyncApiImporter.Direction.PUBLISH;

        return new OperationDefinition(
                new OperationId(id), new ProtocolId(KAFKA), target,
                AsyncApiImporter.safetyOf(direction),
                Map.of(),
                // A payload the run writes is a request, and generation reads
                // it there; one the run only reads is an outcome.
                publishes ? payload : null,
                payload != null && !publishes ? Map.of("message", payload) : Map.of(),
                Map.copyOf(metadata));
    }

    /**
     * The Kafka topic a channel stands for.
     * <p>
     * A Kafka binding may override the address, which is how a document keeps
     * a readable channel name while naming the real topic separately. Failing
     * both, the reference's own last segment is the channel's name and the best
     * remaining guess.
     */
    private String topicOf(JsonNode channel, JsonNode reference) {
        JsonNode bindings = SourceDocument.object(channel, "bindings");
        String bound = SourceDocument.text(SourceDocument.object(bindings, KAFKA), "topic");
        if (bound != null) {
            return bound;
        }
        String address = SourceDocument.text(channel, "address");
        if (address != null && !address.isBlank()) {
            return address;
        }
        return SourceDocument.referencedName(reference);
    }

    /**
     * The message an operation carries.
     * <p>
     * An operation may narrow the channel's messages to a subset; this release
     * uses the first, and says so when there were more, because a catalog
     * operation carries one payload schema and choosing silently would make the
     * generated payload depend on document order.
     */
    private JsonNode messageOf(JsonNode operation, JsonNode channel) {
        JsonNode declared = operation.get("messages");
        if (declared != null && declared.isArray() && !declared.isEmpty()) {
            return SourceDocument.resolve(root, declared.get(0));
        }
        JsonNode channelMessages = SourceDocument.object(channel, "messages");
        if (channelMessages == null || channelMessages.isEmpty()) {
            return null;
        }
        return SourceDocument.resolve(root, channelMessages.properties().iterator().next().getValue());
    }

    /**
     * Where a message carries its correlation value, as AsyncAPI writes it:
     * {@code $message.header#/correlationId} or
     * {@code $message.payload#/orderId}.
     */
    private String correlationIdOf(JsonNode message) {
        if (message == null) {
            return null;
        }
        JsonNode correlationId = message.get("correlationId");
        if (correlationId == null) {
            return null;
        }
        return SourceDocument.text(SourceDocument.resolve(root, correlationId), "location");
    }

    /**
     * The schema of a message's payload, registered in the catalog when it was
     * written inline.
     *
     * @return the schema's id, or null when the message declares none this
     *         release can use
     */
    private SchemaId payloadSchemaOf(JsonNode message, String operationId) {
        if (message == null) {
            return null;
        }
        String schemaFormat = SourceDocument.text(message, "schemaFormat");
        if (schemaFormat != null && !schemaFormat.contains("jsonSchema")
                && !schemaFormat.contains("json-schema")) {
            warnings.add("Message of operation '" + operationId + "' declares schema format "
                    + schemaFormat + ", which this release cannot read; its payload has "
                    + "no schema in the catalog");
            return null;
        }
        JsonNode payload = message.get("payload");
        if (payload == null) {
            return null;
        }
        String referenced = SourceDocument.referencedName(payload);
        if (referenced != null) {
            SchemaId id = new SchemaId(referenced);
            if (schemas.containsKey(id)) {
                return id;
            }
        }
        // Written inline: give it a name of its own so the catalog is complete
        // without the document it came from.
        JsonNode resolved = SourceDocument.resolve(root, payload);
        SchemaId id = new SchemaId(operationId + "-payload");
        schemas.put(id, new DataSchema(
                id, SchemaCollector.typeOf(resolved),
                "#/operations/" + operationId + "/messages/0/payload",
                AsyncApiImporter.definitionOf(resolved)));
        return id;
    }

    /**
     * Kafka bindings worth carrying into the catalog, and a warning for the
     * bindings of protocols this release cannot act on.
     */
    private Map<String, Object> kafkaBindings(
            JsonNode operation, JsonNode channel, String operationId) {
        Map<String, Object> carried = new LinkedHashMap<>();
        for (JsonNode owner : new JsonNode[]{channel, operation}) {
            JsonNode bindings = SourceDocument.object(owner, "bindings");
            if (bindings == null) {
                continue;
            }
            bindings.properties().forEach(binding -> {
                if (!KAFKA.equals(binding.getKey())) {
                    warnings.add("Operation '" + operationId + "' declares "
                            + binding.getKey() + " bindings, which this release ignores");
                }
            });
            JsonNode kafka = SourceDocument.object(bindings, KAFKA);
            if (kafka == null) {
                continue;
            }
            JsonNode groupId = kafka.get("groupId");
            if (groupId != null) {
                // Recorded, never used: the connector assigns partitions rather
                // than joining a group, so a run leaves no group state behind.
                carried.put("declaredGroupId", groupId.toString());
            }
        }
        return carried;
    }
}
