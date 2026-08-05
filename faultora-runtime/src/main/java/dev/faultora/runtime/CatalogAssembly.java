package dev.faultora.runtime;

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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The catalog a run compiles against, from the documents it was given.
 * <p>
 * Neutral about where the documents came from: the CLI reads them from paths a
 * person typed, a runner takes them out of a dispatch. Both hand over the same
 * thing — an ordered list of family and content — because the order is part of
 * the answer, and both get the same catalog out.
 * <p>
 * A name claimed twice is refused rather than resolved. Two descriptions
 * disagreeing about what {@code create-payment} is cannot be settled by
 * whichever happened to be loaded second, and picking one silently would make
 * the run depend on the order the documents were listed in.
 */
public final class CatalogAssembly {

    /** Identifier of the target a scenario-derived catalog declares. */
    public static final String DEFAULT_TARGET_ID = "default";

    private CatalogAssembly() {
    }

    /** One document as it travels: which importer reads it, and its text. */
    public record Document(String family, String content) {
    }

    /** Raised when documents cannot become one catalog. */
    public static final class AssemblyException extends RuntimeException {
        public AssemblyException(String message) {
            super(message);
        }
    }

    /**
     * Import and merge, in the order given.
     *
     * @throws AssemblyException when an importer is missing, a document does
     *                           not import, or two documents claim one name
     */
    public static ApiCatalog assemble(List<Document> documents, ExtensionPolicy extensions) {
        List<ApiCatalog> imported = new ArrayList<>();
        for (Document document : documents) {
            imported.add(importOne(document, extensions));
        }
        return imported.size() == 1 ? imported.get(0) : merge(imported);
    }

    private static ApiCatalog importOne(Document document, ExtensionPolicy extensions) {
        SourceImporter importer = ExtensionRegistry.importerFor(document.family(), extensions);
        if (importer == null) {
            throw new AssemblyException(
                    "No importer for " + document.family() + " documents is installed");
        }
        ImportResult result = importer.importSource(document.content(), new ImportContext(
                document.family(), Path.of("."), Set.of(), 10, 1_000_000, Map.of()));
        if (!result.isSuccess()) {
            StringBuilder why = new StringBuilder(
                    "The " + document.family() + " document did not import:");
            result.errors().forEach(error -> why.append("\n  ").append(error.message()));
            throw new AssemblyException(why.toString());
        }
        return result.catalog();
    }

    /** One catalog from several, in the order the documents were named. */
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
     * OpenAPI document beside an AsyncAPI one failed before it started.
     * <p>
     * A single catalog keeps its own version, which is what a reader of a
     * single-source run journal expects to see. That is deliberately not the
     * rule a dispatch's catalog digest follows — see
     * {@code Dispatch.digestOfDocuments}, which is always the list form because
     * it answers a different question about different bytes.
     */
    private static CatalogVersion versionOf(List<ApiCatalog> catalogs) {
        if (catalogs.size() == 1) {
            return catalogs.get(0).version();
        }
        StringBuilder digests = new StringBuilder();
        catalogs.forEach(catalog -> digests.append(catalog.version().value()).append('\n'));
        return new CatalogVersion(ContentDigest.sha256Uri(digests.toString()));
    }

    private static <K, V> void claim(
            Map<K, V> claimed, K key, V definition, String kind, String name) {
        if (claimed.putIfAbsent(key, definition) != null) {
            throw new AssemblyException(
                    "Two imported documents both declare the " + kind + " '" + name
                            + "'. Rename one of them: a run cannot decide which was meant.");
        }
    }

    /**
     * The minimal catalog a scenario implies, for a run given no descriptions.
     * <p>
     * Every operation the scenario names becomes a GET on a path of its own
     * name, against one target. It is what lets a hand-written scenario run
     * without an OpenAPI document, and it is deliberately dull: a description
     * that was not provided cannot be invented, only stood in for.
     */
    public static ApiCatalog fromScenario(ScenarioDocument scenario, String targetUrl) {
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
        if (steps == null) {
            return;
        }
        for (ScenarioStep step : steps) {
            if (step.operationId() != null) {
                ids.add(step.operationId());
            }
            collectOperationIds(step.steps(), ids);
        }
    }
}
