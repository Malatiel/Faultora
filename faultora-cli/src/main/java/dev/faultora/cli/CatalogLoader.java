package dev.faultora.cli;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces the canonical catalog a run compiles against.
 * <p>
 * Normally that is an imported API description. Without one, a minimal catalog
 * is derived from the scenario itself so that a hand-written scenario can still
 * run — every step becomes a read-only operation on one target.
 */
final class CatalogLoader {

    /** Identifier of the target a scenario-derived catalog declares. */
    static final String DEFAULT_TARGET_ID = "default";

    private CatalogLoader() {
    }

    static ApiCatalog load(TestOptions options, ScenarioDocument scenario) throws IOException {
        return options.openApiPath() != null
                ? importCatalog(options.openApiPath())
                : deriveFromScenario(scenario, options.targetUrl());
    }

    private static ApiCatalog importCatalog(Path openApiPath) throws IOException {
        SourceImporter importer = ExtensionRegistry.importerFor("openapi");
        if (importer == null) {
            throw new CliException(
                    "No importer for OpenAPI documents is installed",
                    FaultoraCli.EXIT_RUNNER_FAILURE);
        }
        String content = Files.readString(openApiPath, StandardCharsets.UTF_8);
        ImportResult result = importer.importSource(content, new ImportContext(
                "openapi", Path.of("."), Set.of(), 10, 1_000_000, Map.of()));

        if (!result.isSuccess()) {
            System.err.println("Failed to import OpenAPI document:");
            result.errors().forEach(error -> System.err.println("  " + error.message()));
            throw new CliException("OpenAPI import failed", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return result.catalog();
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
