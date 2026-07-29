package dev.faultora.importer.asyncapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.importer.source.SourceDocument;
import dev.faultora.importer.source.SourceParseException;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.AuthSchemeId;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.contract.SourceImporter;
import dev.faultora.spi.result.ImportResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports AsyncAPI 3.0 descriptions into the canonical catalog.
 *
 * <h2>The direction rule</h2>
 * AsyncAPI 3.0 states an operation's {@code action} from the point of view of
 * the application the document describes. An application that
 * <em>receives</em> on a channel is one a test must <em>publish</em> to; an
 * application that <em>sends</em> on a channel is one a test must
 * <em>consume</em> from. Every operation is inverted on the way in, so that by
 * the time it reaches a connector the direction is the run's own.
 * <p>
 * Safety follows from the same inversion, and is the reason to get it right:
 * the operation the run publishes to changes the application's state and is
 * classified {@code MUTATING}, while the one it consumes only watches and is
 * {@code READ_ONLY}. Reading the direction backwards would classify a write as
 * a read, and the execution policy would let it through unasked.
 *
 * <h2>What is imported, and what is refused</h2>
 * Kafka servers become targets; a server speaking another protocol is reported
 * and skipped, leaving the rest of the document usable — a description often
 * covers more than the part a test needs. AsyncAPI 2.x is refused by name
 * rather than half-read: 2.x states direction from the <em>client's</em> point
 * of view, so importing it under 3.0's rule would invert every operation
 * silently.
 */
public class AsyncApiImporter implements SourceImporter {

    /** The source family and version this importer handles. */
    private static final String SOURCE_TYPE = "asyncapi-3.0";

    /** Protocols a target may speak for its operations to be executable. */
    private static final Set<String> KAFKA_PROTOCOLS = Set.of("kafka", "kafka-secure");

    /** Protocol identifier the Kafka connector answers to. */
    private static final String KAFKA = "kafka";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Set<String> supportedTypes() {
        return Set.of(SOURCE_TYPE);
    }

    @Override
    public ImportResult importSource(String sourceContent, ImportContext context) {
        try {
            JsonNode root = SourceDocument.parse(sourceContent);

            String version = SourceDocument.text(root, "asyncapi");
            if (version == null) {
                return failure("NOT_ASYNCAPI",
                        "Document does not contain an 'asyncapi' field");
            }
            if (!version.startsWith("3.")) {
                return failure("UNSUPPORTED_VERSION",
                        "Unsupported AsyncAPI version: " + version
                                + ". Only 3.x is supported, because 2.x states an "
                                + "operation's direction from the opposite point of view "
                                + "and importing it under 3.x's rule would reverse every "
                                + "operation.");
            }
            if (context.maxDocSizeBytes() > 0
                    && sourceContent.length() > context.maxDocSizeBytes()) {
                return new ImportResult(null, List.of(error("POLICY_VIOLATION",
                        "DOCUMENT_TOO_LARGE",
                        "Document exceeds maximum size of "
                                + context.maxDocSizeBytes() + " bytes")),
                        List.of(), Map.of());
            }

            List<String> warnings = new ArrayList<>();
            Map<SchemaId, DataSchema> schemas = new SchemaCollector(root).collect();
            List<TargetDefinition> targets = targets(root, warnings);
            List<OperationDefinition> operations = new OperationCollector(
                    root, targets, schemas, warnings).collect();

            if (operations.isEmpty()) {
                warnings.add("No executable operation was imported: the document declares "
                        + "no Kafka channel this release can reach");
            }

            ApiCatalog catalog = new ApiCatalog(
                    new CatalogVersion(SourceDocument.digest(sourceContent)),
                    targets, operations, schemas,
                    Map.<AuthSchemeId, dev.faultora.model.catalog.AuthSchemeDefinition>of(),
                    List.of());
            return ImportResult.success(catalog, List.copyOf(warnings), Map.of());

        } catch (SourceParseException unreadable) {
            return failure("PARSE_ERROR", unreadable.getMessage());
        } catch (RuntimeException unexpected) {
            return new ImportResult(null, List.of(error("INTERNAL", "IMPORT_ERROR",
                    "Unexpected error during import: " + unexpected.getMessage())),
                    List.of(), Map.of());
        }
    }

    /**
     * Servers that can host operations.
     * <p>
     * A server's {@code host} already carries its port, and its {@code
     * protocol} decides whether this release can reach it at all. Servers of
     * other protocols are named in a warning rather than dropped in silence:
     * an author who wrote an MQTT server should learn that it was not imported,
     * not discover it through an operation that mysteriously does not exist.
     */
    private List<TargetDefinition> targets(JsonNode root, List<String> warnings) {
        JsonNode servers = SourceDocument.object(root, "servers");
        if (servers == null) {
            return List.of();
        }
        List<TargetDefinition> targets = new ArrayList<>();
        servers.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode server = SourceDocument.resolve(root, entry.getValue());
            String protocol = SourceDocument.text(server, "protocol");
            String host = SourceDocument.text(server, "host");

            if (protocol == null || !KAFKA_PROTOCOLS.contains(protocol)) {
                warnings.add("Server '" + name + "' speaks " + protocol
                        + ", which this release does not support; its channels were skipped");
                return;
            }
            if (host == null || host.isBlank()) {
                warnings.add("Server '" + name + "' names no host and was skipped");
                return;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("protocol", protocol);
            String description = SourceDocument.text(server, "description");
            if (description != null) {
                metadata.put("description", description);
            }
            targets.add(new TargetDefinition(
                    new TargetId(name), name, KAFKA + "://" + host,
                    List.of(new ProtocolId(KAFKA)), List.of(), Map.copyOf(metadata)));
        });
        return List.copyOf(targets);
    }

    private ImportResult failure(String code, String message) {
        return ImportResult.failure(List.of(error("VALIDATION", code, message)));
    }

    private NormalizedError error(String category, String code, String message) {
        return new NormalizedError(
                NormalizedError.ErrorCategory.valueOf(category), code, message,
                false, Map.of());
    }

    /** Safety of an operation, given which way the run drives the channel. */
    static SafetyClassification safetyOf(Direction direction) {
        return direction == Direction.PUBLISH
                ? SafetyClassification.MUTATING
                : SafetyClassification.READ_ONLY;
    }

    /** Which way the run drives a channel, after the inversion. */
    enum Direction {
        /** The application receives here, so the run writes. */
        PUBLISH("publish"),
        /** The application sends here, so the run watches. */
        CONSUME("consume");

        private final String wireName;

        Direction(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }

        /**
         * Invert an AsyncAPI action into the run's own direction.
         *
         * @return the direction, or null when the action is not one of the two
         */
        static Direction of(String action) {
            if ("receive".equals(action)) {
                return PUBLISH;
            }
            return "send".equals(action) ? CONSUME : null;
        }
    }

    /** Convert a schema node into the catalog's storage form. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> definitionOf(JsonNode schema) {
        return MAPPER.convertValue(schema, Map.class);
    }
}
