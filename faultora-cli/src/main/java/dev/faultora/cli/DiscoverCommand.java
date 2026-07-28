package dev.faultora.cli;

import dev.faultora.spi.contract.SourceImporter;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.result.ImportResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Lists operations discovered from an OpenAPI document.
 *
 * Usage: faultora discover --from-openapi &lt;path&gt;
 */
public class DiscoverCommand implements Command {

    @Override
    public int execute(List<String> args) {
        Path openApiPath = null;

        Iterator<String> it = args.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "--from-openapi", "-o" -> openApiPath = Path.of(requireNext(it, "--from-openapi"));
                case "--help", "-h" -> {
                    printHelp();
                    return FaultoraCli.EXIT_PASS;
                }
                default -> {
                    System.err.println("Unknown option: " + arg);
                    return FaultoraCli.EXIT_INVALID_CONFIG;
                }
            }
        }

        if (openApiPath == null) {
            System.err.println("Error: --from-openapi is required");
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }

        try {
            String content = Files.readString(openApiPath, StandardCharsets.UTF_8);
            SourceImporter importer = ExtensionRegistry.importerFor("openapi", null);
            if (importer == null) {
                System.err.println("No importer for OpenAPI documents is installed");
                return FaultoraCli.EXIT_RUNNER_FAILURE;
            }
            ImportContext context = new ImportContext(
                    "openapi", Path.of("."), Set.of(), 10, 1_000_000, Map.of());
            ImportResult result = importer.importSource(content, context);

            if (!result.isSuccess()) {
                System.err.println("Failed to import OpenAPI document:");
                result.errors().forEach(e -> System.err.println("  " + e.code() + ": " + e.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }

            ApiCatalog catalog = result.catalog();
            System.out.println("Operations (" + catalog.operations().size() + "):");
            for (OperationDefinition op : catalog.operations()) {
                String safety = op.safety() != null ? op.safety().name() : "UNKNOWN";
                String method = String.valueOf(op.protocolMetadata().getOrDefault("method", "?"));
                String path = String.valueOf(op.protocolMetadata().getOrDefault("path", "?"));
                System.out.printf("  %-30s %-10s %s %s%n", op.id().value(), safety, method, path);
            }

            return FaultoraCli.EXIT_PASS;
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }
    }

    private String requireNext(Iterator<String> it, String flag) {
        if (!it.hasNext()) {
            throw new CliException("Option " + flag + " requires a value", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return it.next();
    }

    private void printHelp() {
        System.out.println("Usage: faultora discover --from-openapi <path>");
        System.out.println();
        System.out.println("Lists operations discovered from an OpenAPI document.");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -o, --from-openapi <path>  OpenAPI 3.x file (required)");
        System.out.println("  -h, --help                 Show this help");
    }
}
