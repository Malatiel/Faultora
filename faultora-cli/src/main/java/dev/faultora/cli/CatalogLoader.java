package dev.faultora.cli;

import dev.faultora.runtime.CatalogAssembly;

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

    private CatalogLoader() {
    }

    static List<CatalogAssembly.Document> documents(TestOptions options) throws IOException {
        List<CatalogAssembly.Document> documents = new ArrayList<>();
        read(documents, "openapi", options.openApiPath());
        read(documents, "asyncapi", options.asyncApiPath());
        read(documents, "observations", options.observationsPath());
        return List.copyOf(documents);
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
