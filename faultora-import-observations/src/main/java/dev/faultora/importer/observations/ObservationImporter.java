package dev.faultora.importer.observations;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.importer.source.SourceDocument;
import dev.faultora.importer.source.SourceParseException;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.AuthSchemeDefinition;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.catalog.InputDefinition;
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
 * Imports the observations an operator permits a run to make.
 *
 * <h2>Why the queries are not in the scenario</h2>
 * A database observation is the first thing Faultora runs that it did not learn
 * from a published contract. The obvious place to put the query is the step
 * that makes it — and that is the wrong place. SEC-07 says a scenario carries
 * no arbitrary code, and a {@code SELECT} written in a scenario is arbitrary
 * code with a keyword in front of it: it can read any table the credentials
 * allow, and nobody reviewing the deployment has a list of what a run may see.
 * <p>
 * So the queries live in a document the operator writes and keeps beside the
 * deployment, exactly as OpenAPI and AsyncAPI documents do. A scenario names an
 * observation; what that observation reads is reviewable in one file.
 *
 * <h2>The document</h2>
 * <pre>
 * apiVersion: faultora.dev/v1alpha1
 * kind: Observations
 *
 * servers:
 *   ledger:
 *     url: jdbc:postgresql://localhost:5432/payments
 *
 * observations:
 *   ledger-entries-for:
 *     server: ledger
 *     description: The entries recorded against one payment
 *     sql: SELECT account, amount FROM ledger_entries WHERE payment_id = :paymentId
 *     parameters:
 *       paymentId:
 *         type: string
 *         required: true
 * </pre>
 * Servers become catalog targets, so {@code --target ledger=jdbc://…} redirects
 * an observation to a test database the same way it redirects an API to
 * staging. Parameters become the operation's declared inputs, and the connector
 * binds them — the SQL is never assembled from a scenario's values.
 */
public class ObservationImporter implements SourceImporter {

    /** The source family and version this importer handles. */
    private static final String SOURCE_TYPE = "observations-v1";

    /** Protocol identifier the JDBC connector answers to. */
    private static final String JDBC = "jdbc";

    /** Metadata key carrying the declared statement. */
    public static final String SQL = "sql";

    /** Metadata key carrying the declared parameter names, in declaration order. */
    public static final String PARAMETERS = "parameters";

    @Override
    public Set<String> supportedTypes() {
        return Set.of(SOURCE_TYPE);
    }

    @Override
    public ImportResult importSource(String sourceContent, ImportContext context) {
        if (context.maxDocSizeBytes() > 0
                && sourceContent != null
                && sourceContent.length() > context.maxDocSizeBytes()) {
            return failure("POLICY_VIOLATION", "DOCUMENT_TOO_LARGE",
                    "Document exceeds maximum size of "
                            + context.maxDocSizeBytes() + " bytes");
        }

        try {
            JsonNode root = SourceDocument.parse(sourceContent);
            String kind = SourceDocument.text(root, "kind");
            if (!"Observations".equals(kind)) {
                return failure("VALIDATION", "NOT_OBSERVATIONS",
                        "Document declares kind '" + kind
                                + "'; an observation catalog declares 'Observations'");
            }

            List<String> warnings = new ArrayList<>();
            List<TargetDefinition> targets = targets(root, warnings);
            if (targets.isEmpty()) {
                return failure("VALIDATION", "NO_SERVER",
                        "The document declares no server for its observations to run against");
            }
            List<OperationDefinition> observations =
                    observations(root, targets, warnings);

            ApiCatalog catalog = new ApiCatalog(
                    new CatalogVersion(SourceDocument.digest(sourceContent)),
                    targets, observations, Map.<SchemaId, DataSchema>of(),
                    Map.<AuthSchemeId, AuthSchemeDefinition>of(), List.of());
            return ImportResult.success(catalog, List.copyOf(warnings), Map.of());

        } catch (SourceParseException unreadable) {
            return failure("VALIDATION", "PARSE_ERROR", unreadable.getMessage());
        } catch (RuntimeException unexpected) {
            return failure("INTERNAL", "IMPORT_ERROR",
                    "Unexpected error during import: " + unexpected.getMessage());
        }
    }

    /**
     * The databases the observations run against.
     * <p>
     * A server's URL is a JDBC URL, and it becomes a target like any other, so
     * the operator redirects it for a run with {@code --target}. A description
     * committed to a repository therefore never names the database a run
     * actually reads unless the run is told to use it.
     */
    private List<TargetDefinition> targets(JsonNode root, List<String> warnings) {
        JsonNode servers = SourceDocument.object(root, "servers");
        if (servers == null) {
            return List.of();
        }
        List<TargetDefinition> targets = new ArrayList<>();
        servers.properties().forEach(entry -> {
            String name = entry.getKey();
            String url = SourceDocument.text(entry.getValue(), "url");
            if (url == null || url.isBlank()) {
                warnings.add("Server '" + name + "' declares no url and was skipped");
                return;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            String description = SourceDocument.text(entry.getValue(), "description");
            if (description != null) {
                metadata.put("description", description);
            }
            targets.add(new TargetDefinition(
                    new TargetId(name), name, url,
                    List.of(new ProtocolId(JDBC)), List.of(), Map.copyOf(metadata)));
        });
        return List.copyOf(targets);
    }

    /**
     * The observations themselves.
     * <p>
     * Every one is {@code READ_ONLY}, and that classification is only true
     * because the connector refuses anything that is not a single reading
     * statement. A catalog cannot be trusted to classify a query honestly when
     * the query is right there in the document.
     */
    private List<OperationDefinition> observations(
            JsonNode root, List<TargetDefinition> targets, List<String> warnings) {
        JsonNode declared = SourceDocument.object(root, "observations");
        if (declared == null) {
            warnings.add("The document declares no observations");
            return List.of();
        }
        TargetId onlyServer = targets.get(0).id();
        Set<String> knownServers = new java.util.LinkedHashSet<>();
        targets.forEach(target -> knownServers.add(target.id().value()));

        List<OperationDefinition> observations = new ArrayList<>();
        declared.properties().forEach(entry -> {
            String id = entry.getKey();
            JsonNode observation = entry.getValue();

            String sql = SourceDocument.text(observation, SQL);
            if (sql == null || sql.isBlank()) {
                warnings.add("Observation '" + id + "' declares no sql and was skipped");
                return;
            }
            String server = SourceDocument.text(observation, "server");
            if (server != null && !knownServers.contains(server)) {
                warnings.add("Observation '" + id + "' names server '" + server
                        + "', which the document does not declare; it was skipped");
                return;
            }
            TargetId target = server == null ? onlyServer : new TargetId(server);

            Map<String, InputDefinition> inputs = parameters(observation);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(SQL, sql);
            metadata.put(PARAMETERS, List.copyOf(inputs.keySet()));
            String description = SourceDocument.text(observation, "description");
            if (description != null) {
                metadata.put("description", description);
            }

            observations.add(new OperationDefinition(
                    new OperationId(id), new ProtocolId(JDBC), target,
                    SafetyClassification.READ_ONLY,
                    inputs, null, Map.of(), Map.copyOf(metadata)));
        });
        return List.copyOf(observations);
    }

    /** The parameters a scenario supplies, in the order the document declares. */
    private Map<String, InputDefinition> parameters(JsonNode observation) {
        JsonNode declared = SourceDocument.object(observation, "parameters");
        if (declared == null) {
            return Map.of();
        }
        Map<String, InputDefinition> parameters = new LinkedHashMap<>();
        declared.properties().forEach(entry -> {
            JsonNode parameter = entry.getValue();
            boolean required = parameter.path("required").asBoolean(true);
            parameters.put(entry.getKey(), new InputDefinition(
                    entry.getKey(), InputDefinition.InputLocation.QUERY, required,
                    null, null,
                    Map.of("type", String.valueOf(
                            SourceDocument.text(parameter, "type")))));
        });
        return parameters;
    }

    private ImportResult failure(String category, String code, String message) {
        return new ImportResult(null, List.of(new NormalizedError(
                NormalizedError.ErrorCategory.valueOf(category), code, message,
                false, Map.of())), List.of(), Map.of());
    }
}
