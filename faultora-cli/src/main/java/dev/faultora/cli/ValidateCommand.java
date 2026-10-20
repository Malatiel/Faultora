package dev.faultora.cli;

import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.runtime.CatalogAssembly;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.parser.Diagnostic;
import dev.faultora.spec.parser.ParseResult;
import dev.faultora.spec.parser.ScenarioParser;
import dev.faultora.spec.validator.ScenarioValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a scenario document for structural correctness, and imports any
 * catalogs given beside it.
 *
 * Usage: faultora validate --scenario &lt;path&gt; [--openapi &lt;path&gt;]
 *                          [--asyncapi &lt;path&gt;] [--observations &lt;path&gt;]
 */
public class ValidateCommand implements Command {
    private final CommandOutput output;

    public ValidateCommand() {
        this(CommandOutput.human());
    }

    ValidateCommand(CommandOutput output) {
        this.output = output;
    }

    /** Built-in importers only: validate runs no plugin code. */
    private static final ExtensionPolicy BUILT_INS =
            new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());

    @Override
    public int execute(List<String> args) {
        Path scenarioPath = null;
        Path openApiPath = null;
        Path asyncApiPath = null;
        Path observationsPath = null;

        Iterator<String> it = args.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "--scenario", "-s" -> scenarioPath = Path.of(requireNext(it, "--scenario"));
                case "--openapi", "-o" -> openApiPath = Path.of(requireNext(it, "--openapi"));
                case "--asyncapi", "-a" -> asyncApiPath = Path.of(requireNext(it, "--asyncapi"));
                case "--observations" -> observationsPath = Path.of(requireNext(it, "--observations"));
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

        if (scenarioPath == null) {
            output.error(CliDiagnostic.Code.INVALID_ARGUMENT, null, "scenario");
            output.err().println("Error: --scenario is required");
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }

        try {
            String content = Files.readString(scenarioPath, StandardCharsets.UTF_8);
            ScenarioParser parser = new ScenarioParser();
            ParseResult<ScenarioDocument> parseResult = parser.parse(content);
            output.scenario(parseResult.diagnostics());

            if (!parseResult.isSuccess()) {
                output.err().println("Scenario is INVALID:");
                parseResult.errors().forEach(d -> output.err().println("  ERROR: " + d.message()));
                parseResult.warnings().forEach(d -> output.err().println("  WARN:  " + d.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }

            // Structural validation
            ScenarioValidator validator = new ScenarioValidator();
            ParseResult<ScenarioDocument> validationResult = validator.validate(parseResult.document());
            output.scenario(validationResult.diagnostics());

            if (!validationResult.isSuccess()) {
                output.err().println("Scenario is INVALID:");
                validationResult.errors().forEach(d -> output.err().println("  ERROR: " + d.message()));
                validationResult.warnings().forEach(d -> output.err().println("  WARN:  " + d.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }

            validationResult.warnings().forEach(d -> output.err().println("Warning: " + d.message()));

            ScenarioDocument doc = validationResult.document();
            output.out().println("Scenario is VALID: " + scenarioPath);
            output.out().println("  Name: " + doc.metadata().name());
            output.out().println("  Description: " + doc.metadata().description());
            output.out().println("  Steps: " + countSteps(doc));
        } catch (IOException e) {
            output.error(CliDiagnostic.Code.FILE_READ_FAILED, null, null);
            output.err().println("Cannot read scenario file: " + e.getMessage());
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }

        return validateCatalogs(openApiPath, asyncApiPath, observationsPath);
    }

    /**
     * Import the catalogs exactly as {@code test} would, so a document a run
     * would refuse is refused here first — without starting anything.
     */
    private int validateCatalogs(Path openApi, Path asyncApi, Path observations) {
        List<CatalogAssembly.Document> documents;
        try {
            documents = CatalogLoader.documents(openApi, asyncApi, observations);
        } catch (IOException e) {
            output.error(CliDiagnostic.Code.FILE_READ_FAILED, null, null);
            output.err().println("Cannot read catalog file: " + e.getMessage());
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }
        if (documents.isEmpty()) {
            return FaultoraCli.EXIT_PASS;
        }
        CatalogAssembly.Assembled assembled;
        try {
            assembled = CatalogAssembly.assemble(documents, BUILT_INS);
        } catch (CatalogAssembly.AssemblyException refused) {
            output.error(CliDiagnostic.Code.CATALOG_IMPORT_FAILED, null, null);
            output.err().println("Catalog is INVALID:");
            output.err().println(refused.getMessage());
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }
        output.catalog(assembled.warnings());
        assembled.warnings().forEach(warning -> output.err().println(
                "WARNING: " + warning.family() + " catalog: " + warning.message()));
        output.out().println("Catalog is VALID: " + assembled.catalog().operations().size()
                + " operations from " + documents.stream().map(CatalogAssembly.Document::family)
                .collect(Collectors.joining(", ")));
        return FaultoraCli.EXIT_PASS;
    }

    private int countSteps(ScenarioDocument doc) {
        int count = 0;
        if (doc.setup() != null) count += doc.setup().size();
        if (doc.execute() != null) count += doc.execute().size();
        if (doc.faults() != null) count += doc.faults().size();
        if (doc.assertions() != null) count += doc.assertions().size();
        if (doc.cleanup() != null) count += doc.cleanup().size();
        return count;
    }

    private String requireNext(Iterator<String> it, String flag) {
        if (!it.hasNext()) {
            throw new CliException("Option " + flag + " requires a value", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return it.next();
    }

    private void printHelp() {
        output.out().println("Usage: faultora validate --scenario <path> [catalog options]");
        output.out().println();
        output.out().println("Validates a scenario document for structural correctness and imports");
        output.out().println("the catalogs it is given exactly as test would, without running anything.");
        output.out().println();
        output.out().println("Options:");
        output.out().println("  -s, --scenario <path>      Scenario YAML file (required)");
        output.out().println("  -o, --openapi <path>       OpenAPI document to import");
        output.out().println("  -a, --asyncapi <path>      AsyncAPI 3.0 document to import");
        output.out().println("      --observations <path>  Observation catalog to import");
        output.out().println("  -h, --help                 Show this help");
    }
}
