package dev.faultora.cli;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.AuthSchemeDefinition;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.catalog.WorkflowDefinition;
import dev.faultora.model.identifier.AuthSchemeId;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioStep;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.contract.SourceImporter;
import dev.faultora.spi.result.ImportResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces the canonical catalog a run compiles against.
 * <p>
 * A scenario that proves a business invariant crosses protocols — a command
 * over HTTP, the event it causes on a broker — so a run takes as many
 * descriptions as it needs and compiles against their union. Without any, a
 * minimal catalog is derived from the scenario itself, so that a hand-written
 * scenario can still run.
 * <p>
 * The union is ordered, and a name claimed twice is refused rather than
 * resolved. Order matters because the catalog is digested into the run journal
 * for reproducibility; refusal matters because two descriptions disagreeing
 * about what {@code create-payment} is cannot be settled by whichever happened
 * to be loaded second.
 */
final class CatalogLoader {

    /** Identifier of the target a scenario-derived catalog declares. */
    static final String DEFAULT_TARGET_ID = "default";

    private CatalogLoader() {
    }

    static ApiCatalog load(
            TestOptions options, ScenarioDocument scenario, ExtensionPolicy extensionPolicy)
            throws IOException {
        List<ApiCatalog> imported = new ArrayList<>();
        if (options.openApiPath() != null) {
            imported.add(importCatalog("openapi", options.openApiPath(), extensionPolicy));
        }
        if (options.asyncApiPath() != null) {
            imported.add(importCatalog("asyncapi", options.asyncApiPath(), extensionPolicy));
        }
        if (options.observationsPath() != null) {
            imported.add(importCatalog(
                    "observations", options.observationsPath(), extensionPolicy));
        }
        if (imported.isEmpty()) {
            return deriveFromScenario(scenario, options.targetUrl());
        }
        return imported.size() == 1 ? imported.get(0) : merge(imported);
    }

    private static ApiCatalog importCatalog(
            String family, Path documentPath, ExtensionPolicy extensionPolicy)
            throws IOException {
        SourceImporter importer = ExtensionRegistry.importerFor(family, extensionPolicy);
        if (importer == null) {
            throw new CliException(
                    "No importer for " + family + " documents is installed",
                    FaultoraCli.EXIT_RUNNER_FAILURE);
        }
        String content = Files.readString(documentPath, StandardCharsets.UTF_8);
        ImportResult result = importer.importSource(content, new ImportContext(
                family, Path.of("."), Set.of(), 10, 1_000_000, Map.of()));

        if (!result.isSuccess()) {
            System.err.println("Failed to import " + family + " document:");
            result.errors().forEach(error -> System.err.println("  " + error.message()));
            throw new CliException(
                    family + " import failed", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        result.warnings().forEach(warning -> System.err.println("Warning: " + warning));
        return result.catalog();
    }

    /**
     * One catalog from several, in the order the documents were named.
     *
     * @throws CliException when two documents claim the same name
     */
    private static ApiCatalog merge(List<ApiCatalog> catalogs) {
        Map<TargetId, TargetDefinition> targets = new LinkedHashMap<>();
        Map<OperationId, OperationDefinition> operations = new LinkedHashMap<>();
        Map<SchemaId, DataSchema> schemas = new LinkedHashMap<>();
        Map<AuthSchemeId, AuthSchemeDefinition> authentication = new LinkedHashMap<>();
        List<WorkflowDefinition> workflows = new ArrayList<>();

        for (ApiCatalog catalog : catalogs) {
            catalog.targets().forEach(target ->
                    claim(targets, target.id(), target, "target", target.id().value()));
            catalog.operations().forEach(operation ->
                    claim(operations, operation.id(), operation,
                            "operation", operation.id().value()));
            catalog.schemas().forEach((id, schema) ->
                    claim(schemas, id, schema, "schema", id.value()));
            if (catalog.authentication() != null) {
                catalog.authentication().forEach((id, scheme) ->
                        claim(authentication, id, scheme, "authentication scheme", id.value()));
            }
            if (catalog.workflows() != null) {
                workflows.addAll(catalog.workflows());
            }
        }

        return new ApiCatalog(
                versionOf(catalogs),
                List.copyOf(targets.values()),
                List.copyOf(operations.values()),
                Map.copyOf(schemas),
                Map.copyOf(authentication),
                List.copyOf(workflows));
    }

    /**
     * One version identifying the union, whatever it was built from.
     * <p>
     * The versions being merged are content digests, and joining them was the
     * obvious thing and the wrong one: a catalog version is an identifier, an
     * identifier is bounded and admits no {@code +}, and a run that imported an
     * OpenAPI document beside an AsyncAPI one failed before it started. It went
     * unnoticed because every suite passed exactly one document — which is
     * precisely the case the cross-component gate does not.
     * <p>
     * So several digests become one digest over them, in the order the
     * documents were named. It is the same length for any number of documents,
     * it changes when any of them changes, and it stays the document's own
     * digest when there is only one — which is what a reader of a single-source
     * run journal expects to see.
     */
    private static CatalogVersion versionOf(List<ApiCatalog> catalogs) {
        if (catalogs.size() == 1) {
            return catalogs.get(0).version();
        }
        StringBuilder digests = new StringBuilder();
        catalogs.forEach(catalog -> digests.append(catalog.version().value()).append('\n'));
        return new CatalogVersion(ContentDigest.sha256Uri(digests.toString()));
    }

    /**
     * Put a definition under its name, refusing a name already taken.
     * <p>
     * Keeping one of the two silently would make the run depend on the order
     * the documents happened to be listed in, and the scenario would call an
     * operation other than the one its author read.
     */
    private static <K, V> void claim(
            Map<K, V> claimed, K key, V definition, String kind, String name) {
        if (claimed.putIfAbsent(key, definition) != null) {
            throw new CliException(
                    "Two imported documents both declare the " + kind + " '" + name
                            + "'. Rename one of them: a run cannot decide which was meant.",
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    private static ApiCatalog deriveFromScenario(ScenarioDocument scenario, String targetUrl) {
        TargetId targetId = new TargetId(DEFAULT_TARGET_ID);
        TargetDefinition target = new TargetDefinition(
                targetId, "Default", targetUrl,
                List.of(new ProtocolId("http")), List.of(), Map.of());

        Set<String> operationIds = new LinkedHashSet<>();
        collectOperationIds(scenario.setup(), operationIds);
        collectOperationIds(scenario.execute(), operationIds);
        collectOperationIds(scenario.cleanup(), operationIds);

        List<OperationDefinition> operations = new ArrayList<>();
        for (String operationId : operationIds) {
            operations.add(new OperationDefinition(
                    new OperationId(operationId), new ProtocolId("http"), targetId,
                    SafetyClassification.READ_ONLY,
                    Map.of(), null, Map.of(),
                    Map.of("method", "GET", "path", "/" + operationId)));
        }

        return new ApiCatalog(
                new CatalogVersion("v1alpha1"),
                List.of(target), operations, Map.of(), Map.of(), List.of());
    }

    /** Operation IDs of a section, including those inside group steps. */
    private static void collectOperationIds(List<ScenarioStep> steps, Set<String> ids) {
        if (steps == null) return;
        for (ScenarioStep step : steps) {
            if (step.operationId() != null) {
                ids.add(step.operationId());
            }
            collectOperationIds(step.steps(), ids);
        }
    }
}
