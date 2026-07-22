package dev.faultora.cli;

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

/**
 * Validates a scenario document for structural correctness.
 *
 * Usage: faultora validate --scenario &lt;path&gt;
 */
public class ValidateCommand implements Command {

    @Override
    public int execute(List<String> args) {
        Path scenarioPath = null;

        Iterator<String> it = args.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "--scenario", "-s" -> scenarioPath = Path.of(requireNext(it, "--scenario"));
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

        if (scenarioPath == null) {
            System.err.println("Error: --scenario is required");
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }

        try {
            String content = Files.readString(scenarioPath, StandardCharsets.UTF_8);
            ScenarioParser parser = new ScenarioParser();
            ParseResult<ScenarioDocument> parseResult = parser.parse(content);

            if (!parseResult.isSuccess()) {
                System.err.println("Scenario is INVALID:");
                parseResult.errors().forEach(d -> System.err.println("  ERROR: " + d.message()));
                parseResult.warnings().forEach(d -> System.err.println("  WARN:  " + d.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }

            // Structural validation
            ScenarioValidator validator = new ScenarioValidator();
            ParseResult<ScenarioDocument> validationResult = validator.validate(parseResult.document());

            if (!validationResult.isSuccess()) {
                System.err.println("Scenario is INVALID:");
                validationResult.errors().forEach(d -> System.err.println("  ERROR: " + d.message()));
                validationResult.warnings().forEach(d -> System.err.println("  WARN:  " + d.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }

            validationResult.warnings().forEach(d -> System.err.println("Warning: " + d.message()));

            ScenarioDocument doc = validationResult.document();
            System.out.println("Scenario is VALID: " + scenarioPath);
            System.out.println("  Name: " + doc.metadata().name());
            System.out.println("  Description: " + doc.metadata().description());
            System.out.println("  Steps: " + countSteps(doc));

            return FaultoraCli.EXIT_PASS;
        } catch (IOException e) {
            System.err.println("Cannot read scenario file: " + e.getMessage());
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }
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
        System.out.println("Usage: faultora validate --scenario <path>");
        System.out.println();
        System.out.println("Validates a scenario document for structural correctness.");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -s, --scenario <path>  Scenario YAML file (required)");
        System.out.println("  -h, --help             Show this help");
    }
}
