package dev.faultora.cli;

import dev.faultora.spec.model.ApiVersions;

import dev.faultora.runtime.ExtensionRegistry;

import dev.faultora.spi.contract.SourceImporter;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.InputDefinition;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
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
    private final CommandOutput output;

    public InitCommand() {
        this(CommandOutput.human());
    }

    InitCommand(CommandOutput output) {
        this.output = output;
    }

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
                    output.error(CliDiagnostic.Code.INVALID_ARGUMENT, null, null);
                    output.err().println("Unknown option: " + arg);
                    return FaultoraCli.EXIT_INVALID_CONFIG;
                }
            }
        }

        if (openApiPath == null) {
            output.error(CliDiagnostic.Code.INVALID_ARGUMENT, null, null);
            output.err().println("Error: --from-openapi is required");
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }

        CliDiagnostic.Code ioStage = CliDiagnostic.Code.FILE_READ_FAILED;
        try {
            // Read and import OpenAPI
            String content = Files.readString(openApiPath, StandardCharsets.UTF_8);
            SourceImporter importer = ExtensionRegistry.importerFor("openapi", null);
            if (importer == null) {
                output.error(CliDiagnostic.Code.CAPABILITY_UNAVAILABLE, null, null);
                output.err().println("No importer for OpenAPI documents is installed");
                return FaultoraCli.EXIT_RUNNER_FAILURE;
            }
            ImportContext context = new ImportContext(
                    "openapi", Path.of("."), Set.of(), 10, 1_000_000, Map.of());
            ImportResult result = importer.importSource(content, context);

            if (!result.isSuccess()) {
                output.error(CliDiagnostic.Code.CATALOG_IMPORT_FAILED, null, null);
                output.err().println("Failed to import OpenAPI document:");
                result.errors().forEach(e -> output.err().println("  " + e.code() + ": " + e.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }

            ApiCatalog catalog = result.catalog();
            output.out().println("Imported " + catalog.operations().size() + " operations from " + openApiPath);

            Path scenarioPath = outputDir.resolve("scenario.yaml");
            if (Files.exists(scenarioPath)) {
                // A starter file is cheap to make again; the scenario someone
                // has since edited in its place is not.
                output.error(CliDiagnostic.Code.INVALID_ARGUMENT, null, "output");
                output.err().println(scenarioPath + " already exists. Choose another --output directory"
                        + " or move the file away; init does not overwrite it.");
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }
            String scenario = generateScenario(catalog);
            ioStage = CliDiagnostic.Code.ARTIFACT_WRITE_FAILED;
            Files.createDirectories(scenarioPath.toAbsolutePath().getParent());
            Files.writeString(scenarioPath, scenario);
            output.out().println("Generated scenario: " + scenarioPath);
            output.out().println();
            output.out().println("Next:");
            String files = "--scenario " + quoted(scenarioPath) + " --openapi " + quoted(openApiPath);
            output.out().println("  faultora validate " + files);
            output.out().println("  faultora test " + files + " --target <base-url> --preview");
            output.out().println("  faultora test " + files + " --target <base-url> --format console,html");
            output.out().println("Add --allow-private when the target is localhost or another private address.");

            return FaultoraCli.EXIT_PASS;
        } catch (IOException e) {
            output.error(ioStage, null, null);
            output.err().println("I/O error: " + e.getMessage());
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }
    }

    /**
     * A starter scenario that runs as generated.
     * <p>
     * It used to list every operation, so the first test of a typical API was
     * refused outright for its DELETE and then failed on every GET that needs
     * an ID. Steps are now generated only for operations that can run with
     * nothing filled in; the others are listed with what they are missing, as
     * a comment the author can turn into a step.
     */
    static String generateScenario(ApiCatalog catalog) {
        List<OperationDefinition> runnable = new ArrayList<>();
        List<String> heldBack = new ArrayList<>();
        for (OperationDefinition op : catalog.operations()) {
            String reason = whyNotRunnableAsGenerated(op);
            if (reason == null) {
                runnable.add(op);
            } else {
                heldBack.add(op.id().value() + ": " + reason);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("apiVersion: ").append(ApiVersions.CURRENT).append('\n');
        sb.append("kind: Scenario\n");
        sb.append("metadata:\n");
        sb.append("  name: generated-scenario\n");
        sb.append("  description: Auto-generated from OpenAPI import\n");
        if (!heldBack.isEmpty()) {
            sb.append("# Not generated as steps, because they cannot run as generated:\n");
            heldBack.forEach(line -> sb.append("#   ").append(line).append('\n'));
        }
        sb.append("execute:\n");
        if (runnable.isEmpty()) {
            sb.append("  - id: placeholder\n");
            sb.append("    type: wait\n");
            sb.append("    timeout: 1ms\n");
        }
        for (OperationDefinition op : runnable) {
            sb.append("  - id: ").append(op.id().value()).append("\n");
            sb.append("    type: operation\n");
            sb.append("    operationId: ").append(op.id().value()).append("\n");
        }
        if (!runnable.isEmpty()) {
            OperationDefinition first = runnable.get(0);
            sb.append("assertions:\n");
            sb.append("  - id: check-status\n");
            sb.append("    assertionType: status\n");
            sb.append("    params:\n");
            sb.append("      expected: ").append(expectedStatus(first)).append("\n");
            sb.append("    targetStep: ").append(first.id().value()).append("\n");
        }
        return sb.toString();
    }

    /**
     * A path as one argument in a command someone will paste: quoted unless it
     * plainly needs no quoting. Double quotes are read the same way by cmd,
     * PowerShell and POSIX shells for the paths this prints.
     */
    static String quoted(Path path) {
        String text = path.toString();
        return text.matches("[A-Za-z0-9_./:\\\\-]+") ? text : "\"" + text + "\"";
    }

    /** Null when the operation runs with nothing filled in; otherwise why not. */
    private static String whyNotRunnableAsGenerated(OperationDefinition op) {
        if (op.safety() == SafetyClassification.DESTRUCTIVE) {
            return "destructive; runs only with --allow-destructive";
        }
        // A required body counts too: nothing generates one unless the step
        // asks for it, and an empty POST is refused by any API that checks.
        List<String> needed = new ArrayList<>();
        if (op.inputs() != null) {
            op.inputs().values().forEach(input -> {
                if (input.required()) {
                    needed.add(input.location() == InputDefinition.InputLocation.BODY
                            ? "a request body" : input.name());
                }
            });
        }
        return needed.isEmpty() ? null : "needs " + String.join(", ", needed) + " under inputs";
    }

    /** The lowest success status the description declares, else 200. */
    private static int expectedStatus(OperationDefinition op) {
        if (op.outcomes() == null) {
            return 200;
        }
        return op.outcomes().keySet().stream()
                .filter(code -> code.matches("2[0-9]{2}"))
                .mapToInt(Integer::parseInt)
                .min().orElse(200);
    }

    private String requireNext(Iterator<String> it, String flag) {
        if (!it.hasNext()) {
            throw new CliException("Option " + flag + " requires a value", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return it.next();
    }

    private void printHelp() {
        output.out().println("Usage: faultora init --from-openapi <path> [--output <dir>]");
        output.out().println();
        output.out().println("Imports an OpenAPI document and generates a starter scenario.");
        output.out().println();
        output.out().println("Options:");
        output.out().println("  -o, --from-openapi <path>  OpenAPI 3.x file (required)");
        output.out().println("  --output <dir>             Output directory (default: .)");
        output.out().println("  -h, --help                 Show this help");
    }
}
