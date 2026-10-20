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
        return documents(options.openApiPath(), options.asyncApiPath(), options.observationsPath());
    }

    /** Each path may be null; the order is the one a run merges them in. */
    static List<CatalogAssembly.Document> documents(Path openApi, Path asyncApi, Path observations)
            throws IOException {
        List<CatalogAssembly.Document> documents = new ArrayList<>();
        read(documents, "openapi", openApi);
        read(documents, "asyncapi", asyncApi);
        read(documents, "observations", observations);
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
