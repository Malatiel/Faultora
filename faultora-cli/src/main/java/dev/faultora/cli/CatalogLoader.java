package dev.faultora.cli;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.runtime.CatalogAssembly;
import dev.faultora.spec.model.ScenarioDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The documents a command line named, as the catalog a run compiles against.
 * <p>
 * Reading files and turning a refusal into an exit code is what belongs to the
 * CLI; what a set of documents means is not, because a runner is handed the
 * same documents over a wire and has to arrive at the same catalog. That part
 * lives in {@link CatalogAssembly}, and this reads paths.
 */
final class CatalogLoader {

    /** Identifier of the target a scenario-derived catalog declares. */
    static final String DEFAULT_TARGET_ID = CatalogAssembly.DEFAULT_TARGET_ID;

    private CatalogLoader() {
    }

    static ApiCatalog load(
            TestOptions options, ScenarioDocument scenario, ExtensionPolicy extensionPolicy)
            throws IOException {
        List<CatalogAssembly.Document> documents = new ArrayList<>();
        read(documents, "openapi", options.openApiPath());
        read(documents, "asyncapi", options.asyncApiPath());
        read(documents, "observations", options.observationsPath());

        if (documents.isEmpty()) {
            return CatalogAssembly.fromScenario(scenario, options.targetUrl());
        }
        try {
            return CatalogAssembly.assemble(documents, extensionPolicy);
        } catch (CatalogAssembly.AssemblyException refused) {
            throw new CliException(refused.getMessage(), FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    private static void read(
            List<CatalogAssembly.Document> documents, String family, Path path)
            throws IOException {
        if (path != null) {
            documents.add(new CatalogAssembly.Document(
                    family, Files.readString(path, StandardCharsets.UTF_8)));
        }
    }
}
