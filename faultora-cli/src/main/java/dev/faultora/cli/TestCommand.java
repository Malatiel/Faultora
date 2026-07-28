package dev.faultora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.connector.http.DestinationPolicy;
import dev.faultora.connector.http.HttpConnector;
import dev.faultora.engine.LocalEngine;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.PlanCompilationResult;
import dev.faultora.engine.plan.PlanCompiler;
import dev.faultora.engine.plan.PlanDiagnostic;
import dev.faultora.engine.run.RunResult;
import dev.faultora.faults.local.FaultInjectingConnector;
import dev.faultora.faults.local.LocalFaultProvider;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.model.InputDeclaration;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.parser.ParseResult;
import dev.faultora.spec.parser.ScenarioParser;
import dev.faultora.spec.validator.ScenarioValidator;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.contract.ReportRenderer;
import dev.faultora.spi.context.ConnectorContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a scenario against a target and produces reports.
 * <p>
 * This is the composition root of a local run: it wires the parsed options,
 * the discovered extensions, and the policies together, then hands control to
 * the engine. Argument syntax lives in {@link TestOptions}, the bounds of a run
 * in {@link RunPolicies}, catalog loading in {@link CatalogLoader}, and report
 * rendering in {@link ReportWriter}.
 *
 * Usage: faultora test --scenario &lt;path&gt; [--openapi &lt;path&gt;] [--target &lt;url&gt;]
 *                      [--format console,json,junit,html] [--output &lt;dir&gt;]
 *                      [--seed &lt;n&gt;]
 */
public class TestCommand implements Command {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public int execute(List<String> args) {
        TestOptions options;
        try {
            options = TestOptions.parse(args);
        } catch (CliException e) {
            System.err.println(e.getMessage());
            return e.exitCode();
        }
        if (options.helpRequested()) {
            printHelp();
            return FaultoraCli.EXIT_PASS;
        }

        ExtensionPolicy extensionPolicy = RunPolicies.extensionPolicy(options);
        Map<String, ReportRenderer> renderers = ExtensionRegistry.renderers(extensionPolicy);
        if (!renderers.keySet().containsAll(options.formats())) {
            System.err.println("Unknown format. Supported formats: "
                    + String.join(",", renderers.keySet()));
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }

        try {
            String scenarioContent = Files.readString(
                    options.scenarioPath(), StandardCharsets.UTF_8);
            ScenarioDocument scenario = loadScenario(scenarioContent);
            if (scenario == null) {
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }

            ApiCatalog catalog = CatalogLoader.load(options, scenario, extensionPolicy);
            Map<String, FaultProvider> faultProviders = RunPolicies.faultProviders(options);
            TargetPolicy targetPolicy = RunPolicies.targetPolicy(options, faultProviders);
            ConnectorContext connectorContext = RunPolicies.connectorContext(
                    options, targetPolicy, new EnvironmentSecretResolver());

            ExpressionContext expressionContext = ExpressionContext.builder()
                    .inputs(resolveDeclaredInputs(scenario, options.inputs()))
                    .runMetadata(Map.of("seed", options.seed(), "target", options.targetUrl()))
                    .build();

            RunId runId = new RunId("run-" + options.seed());
            PlanCompilationResult compilation = new PlanCompiler().compile(
                    scenario, catalog, targetPolicy, runId, options.seed(),
                    ContentDigest.sha256Uri(scenarioContent),
                    ContentDigest.sha256Uri(MAPPER.writeValueAsString(catalog)));

            if (compilation.plan() == null) {
                System.err.println("Plan compilation failed:");
                compilation.diagnostics().forEach(d -> System.err.println("  " + d.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }
            compilation.diagnostics().stream()
                    .filter(d -> d.severity() == PlanDiagnostic.Severity.WARNING)
                    .forEach(d -> System.err.println("Warning: " + d.message()));

            Files.createDirectories(options.outputDir());
            Path journalPath = options.outputDir().resolve("events.ndjson");
            Files.deleteIfExists(journalPath);

            RunResult result = run(
                    options, compilation, faultProviders, connectorContext,
                    expressionContext, journalPath, scenario, extensionPolicy);

            ReportWriter.writeAll(
                    options.formats(), renderers, loadEvents(journalPath), options.outputDir());

            System.out.printf("%nResult: %s — %d nodes, %d passed, %d failed (%dms)%n",
                    result.status(), result.totalNodes(),
                    result.passedAssertions(), result.failedAssertions(),
                    result.durationMs());

            return switch (result.status()) {
                case PASSED -> FaultoraCli.EXIT_PASS;
                case FAILED -> FaultoraCli.EXIT_TEST_FAILURE;
                case ERROR, CANCELLED -> FaultoraCli.EXIT_RUNNER_FAILURE;
            };

        } catch (CliException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("Runner error: " + e.getMessage());
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }
    }

    /**
     * Execute the compiled plan.
     * <p>
     * The connector stack is assembled here rather than discovered: the
     * destination policy and the fault-injecting wrapper decide what this run
     * may reach and what may be broken, so they are the operator's choice, not
     * the classpath's.
     */
    private RunResult run(
            TestOptions options,
            PlanCompilationResult compilation,
            Map<String, FaultProvider> faultProviders,
            ConnectorContext connectorContext,
            ExpressionContext expressionContext,
            Path journalPath,
            ScenarioDocument scenario,
            ExtensionPolicy extensionPolicy
    ) throws IOException {
        Map<String, AssertionProvider> assertionProviders =
                ExtensionRegistry.assertionProviders(extensionPolicy);
        LocalFaultProvider localFaults = (LocalFaultProvider) faultProviders.get("local");

        try (HttpConnector httpConnector = options.allowPrivate()
                ? new HttpConnector(DestinationPolicy.permissive())
                : new HttpConnector()) {
            Connector faultAwareConnector =
                    new FaultInjectingConnector(httpConnector, localFaults);
            LocalEngine engine = new LocalEngine(
                    Map.of("http", faultAwareConnector), assertionProviders, faultProviders);

            try (RunJournal journal = new RunJournal(journalPath, true)) {
                System.out.println("Running scenario: " + scenario.metadata().name());
                System.out.println("Target: " + options.targetUrl());
                System.out.println("Seed: " + options.seed());
                System.out.println();

                return engine.execute(
                        compilation.plan(), journal, expressionContext,
                        connectorContext, new AtomicBoolean(false));
            }
        }
    }

    /** Parse and validate the scenario, or null when it is not runnable. */
    private ScenarioDocument loadScenario(String scenarioContent) {
        ParseResult<ScenarioDocument> parsed = new ScenarioParser().parse(scenarioContent);
        if (!parsed.isSuccess()) {
            System.err.println("Scenario validation failed:");
            parsed.errors().forEach(d -> System.err.println("  " + d.message()));
            return null;
        }
        parsed.warnings().forEach(d -> System.err.println("Warning: " + d.message()));

        ParseResult<ScenarioDocument> validated =
                new ScenarioValidator().validate(parsed.document());
        if (!validated.isSuccess()) {
            System.err.println("Scenario validation failed:");
            validated.errors().forEach(d -> System.err.println("  " + d.message()));
            return null;
        }
        validated.warnings().forEach(d -> System.err.println("Warning: " + d.message()));
        return validated.document();
    }

    /**
     * Merge CLI-provided inputs with the scenario's declared defaults.
     * Unknown input names and missing required inputs are configuration errors.
     */
    private Map<String, Object> resolveDeclaredInputs(
            ScenarioDocument scenario, Map<String, Object> cliInputs) {
        Map<String, InputDeclaration> declared =
                scenario.inputs() != null ? scenario.inputs() : Map.of();

        for (String name : cliInputs.keySet()) {
            if (!declared.containsKey(name)) {
                throw new CliException(
                        "Unknown input '" + name + "'. Declared inputs: "
                                + (declared.isEmpty() ? "(none)"
                                        : String.join(", ", declared.keySet())),
                        FaultoraCli.EXIT_INVALID_CONFIG);
            }
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : declared.entrySet()) {
            String name = entry.getKey();
            InputDeclaration declaration = entry.getValue();
            if (cliInputs.containsKey(name)) {
                resolved.put(name, cliInputs.get(name));
            } else if (declaration.defaultValue() != null) {
                resolved.put(name, declaration.defaultValue());
            } else if (declaration.required()) {
                throw new CliException(
                        "Required input '" + name + "' is missing. Provide it with --input "
                                + name + "=<value>",
                        FaultoraCli.EXIT_INVALID_CONFIG);
            }
        }
        return resolved;
    }

    private List<RunEvent> loadEvents(Path journalPath) throws IOException {
        List<RunEvent> events = new ArrayList<>();
        try (var reader = Files.newBufferedReader(journalPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    events.add(MAPPER.readValue(line, RunEvent.class));
                }
            }
        }
        return events;
    }

    private void printHelp() {
        System.out.println("Usage: faultora test --scenario <path> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -s, --scenario <path>      Scenario YAML file (required)");
        System.out.println("  -o, --openapi <path>       OpenAPI document for catalog");
        System.out.println("  -t, --target <url>         Base URL for every catalog target");
        System.out.println("                             (default: " + TestOptions.DEFAULT_TARGET_URL + ")");
        System.out.println("  -t, --target <id>=<url>    Base URL for one catalog target (repeatable)");
        System.out.println("  -f, --format <formats>     Output formats: console,json,junit,html (default: console)");
        System.out.println("      --output <dir>         Output directory (default: faultora-results)");
        System.out.println("      --seed <n>             Random seed (default: current time)");
        System.out.println("      --allow-private        Allow connections to private/local networks");
        System.out.println("      --allow-destructive    Allow operations classified as destructive");
        System.out.println("      --auth-secret-id <id>  Secret handle ID for Authorization header (resolved from env)");
        System.out.println("      --toxiproxy-url <url>  Toxiproxy admin endpoint; enables network-* fault types");
        System.out.println("      --allow-extension <class>  Permit a non-built-in extension (repeatable)");
        System.out.println("  -i, --input <key=value>    Value for a declared scenario input (repeatable)");
        System.out.println("  -h, --help                 Show this help");
        System.out.println();
        System.out.println("Exit codes:");
        System.out.println("  0  All tests passed");
        System.out.println("  1  Test failure (assertion failed)");
        System.out.println("  2  Invalid configuration");
        System.out.println("  3  Runner failure");
    }
}
