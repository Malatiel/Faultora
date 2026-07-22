package dev.faultora.cli;

import dev.faultora.importer.openapi.OpenApiImporter;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.result.ImportResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Imports an OpenAPI document and generates a starter scenario YAML.
 *
 * Usage: faultora init --from-openapi &lt;path&gt; [--output &lt;dir&gt;]
 */
public class InitCommand implements Command {

    @Override
    public int execute(List<String> args) {
        Path openApiPath = null;
        Path outputDir = Path.of(".");

        Iterator<String> it = args.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "--from-openapi", "-o" -> openApiPath = Path.of(requireNext(it, "--from-openapi"));
                case "--output" -> outputDir = Path.of(requireNext(it, "--output"));
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
            // Read and import OpenAPI
            String content = Files.readString(openApiPath, StandardCharsets.UTF_8);
            OpenApiImporter importer = new OpenApiImporter();
            ImportContext context = new ImportContext(
                    "openapi", Path.of("."), Set.of(), 10, 1_000_000, Map.of());
            ImportResult result = importer.importSource(content, context);

            if (!result.isSuccess()) {
                System.err.println("Failed to import OpenAPI document:");
                result.errors().forEach(e -> System.err.println("  " + e.code() + ": " + e.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }

            ApiCatalog catalog = result.catalog();
            System.out.println("Imported " + catalog.operations().size() + " operations from " + openApiPath);

            // Generate scenario
            String scenario = generateScenario(catalog);
            Path scenarioPath = outputDir.resolve("scenario.yaml");
            Files.createDirectories(scenarioPath.getParent());
            Files.writeString(scenarioPath, scenario);
            System.out.println("Generated scenario: " + scenarioPath);

            return FaultoraCli.EXIT_PASS;
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }
    }

    private String generateScenario(ApiCatalog catalog) {
        StringBuilder sb = new StringBuilder();
        sb.append("apiVersion: faultora.dev/v1alpha1\n");
        sb.append("kind: Scenario\n");
        sb.append("metadata:\n");
        sb.append("  name: generated-scenario\n");
        sb.append("  description: Auto-generated from OpenAPI import\n");
        sb.append("execute:\n");
        for (OperationDefinition op : catalog.operations()) {
            sb.append("  - id: ").append(op.id().value()).append("\n");
            sb.append("    type: operation\n");
            sb.append("    operationId: ").append(op.id().value()).append("\n");
        }
        sb.append("assertions:\n");
        if (!catalog.operations().isEmpty()) {
            String firstId = catalog.operations().get(0).id().value();
            sb.append("  - id: check-status\n");
            sb.append("    assertionType: status\n");
            sb.append("    params:\n");
            sb.append("      expected: 200\n");
            sb.append("    targetStep: ").append(firstId).append("\n");
        }
        sb.append("cleanup:\n  []\n");
        return sb.toString();
    }

    private String requireNext(Iterator<String> it, String flag) {
        if (!it.hasNext()) {
            throw new CliException("Option " + flag + " requires a value", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return it.next();
    }

    private void printHelp() {
        System.out.println("Usage: faultora init --from-openapi <path> [--output <dir>]");
        System.out.println();
        System.out.println("Imports an OpenAPI document and generates a starter scenario.");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -o, --from-openapi <path>  OpenAPI 3.x file (required)");
        System.out.println("  --output <dir>             Output directory (default: .)");
        System.out.println("  -h, --help                 Show this help");
    }
}
