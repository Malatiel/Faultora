package dev.faultora.cli;

import dev.faultora.assertions.core.DurationAssertionProvider;
import dev.faultora.assertions.core.HeaderAssertionProvider;
import dev.faultora.assertions.core.JsonPathAssertionProvider;
import dev.faultora.assertions.core.StatusAssertionProvider;
import dev.faultora.connector.http.DestinationPolicy;
import dev.faultora.connector.http.HttpConnector;
import dev.faultora.engine.LocalEngine;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.PlanCompilationResult;
import dev.faultora.engine.plan.PlanCompiler;
import dev.faultora.engine.plan.PlanDiagnostic;
import dev.faultora.engine.run.RunResult;
import dev.faultora.importer.openapi.OpenApiImporter;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.reporting.ConsoleRenderer;
import dev.faultora.reporting.HtmlRenderer;
import dev.faultora.reporting.JsonRenderer;
import dev.faultora.reporting.JUnitXmlRenderer;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.parser.ParseResult;
import dev.faultora.spec.parser.ScenarioParser;
import dev.faultora.spec.validator.ScenarioValidator;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.contract.ReportRenderer;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.result.ImportResult;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a scenario against a target and produces reports.
 *
 * Usage: faultora test --scenario &lt;path&gt; [--openapi &lt;path&gt;] [--target &lt;url&gt;]
 *                      [--format console,json,junit,html] [--output &lt;dir&gt;]
 *                      [--seed &lt;n&gt;]
 */
public class TestCommand implements Command {

    @Override
    public int execute(List<String> args) {
        Path scenarioPath = null;
        Path openApiPath = null;
        String targetUrl = "http://localhost:8080";
        List<String> formats = List.of("console");
        Path outputDir = Path.of("faultora-results");
        long seed = System.currentTimeMillis();
        boolean allowPrivate = false;
        String authSecretId = null;

        Iterator<String> it = args.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "--scenario", "-s" -> scenarioPath = Path.of(requireNext(it, "--scenario"));
                case "--openapi", "-o" -> openApiPath = Path.of(requireNext(it, "--openapi"));
                case "--target", "-t" -> targetUrl = requireNext(it, "--target");
                case "--format", "-f" -> formats = parseFormats(requireNext(it, "--format"));
                case "--output" -> outputDir = Path.of(requireNext(it, "--output"));
                case "--seed" -> seed = parseSeed(requireNext(it, "--seed"));
                case "--allow-private" -> allowPrivate = true;
                case "--auth-secret-id" -> authSecretId = requireNext(it, "--auth-secret-id");
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
        Set<String> supportedFormats = Set.of("console", "json", "junit", "html");
        if (formats.isEmpty() || !supportedFormats.containsAll(formats)) {
            System.err.println("Unknown format. Supported formats: console,json,junit,html");
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }

        try {
            String scenarioContent = Files.readString(scenarioPath, StandardCharsets.UTF_8);
            ScenarioParser parser = new ScenarioParser();
            ParseResult<ScenarioDocument> parseResult = parser.parse(scenarioContent);

            if (!parseResult.isSuccess()) {
                System.err.println("Scenario validation failed:");
                parseResult.errors().forEach(d -> System.err.println("  " + d.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }

            parseResult.warnings().forEach(d -> System.err.println("Warning: " + d.message()));
            ParseResult<ScenarioDocument> validation =
                    new ScenarioValidator().validate(parseResult.document());
            if (!validation.isSuccess()) {
                System.err.println("Scenario validation failed:");
                validation.errors().forEach(d -> System.err.println("  " + d.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }
            validation.warnings().forEach(d -> System.err.println("Warning: " + d.message()));
            ScenarioDocument scenario = validation.document();

            ApiCatalog catalog;
            if (openApiPath != null) {
                catalog = importCatalog(openApiPath);
            } else {
                catalog = buildCatalogFromScenario(scenario, targetUrl);
            }

            TargetPolicy targetPolicy = new TargetPolicy(
                    Set.of(),
                    Set.of(SafetyClassification.READ_ONLY, SafetyClassification.MUTATING),
                    1000, 10, 300_000, 1_048_576,
                    Set.of(), Set.of());

            ExpressionContext exprContext = ExpressionContext.builder().build();

            EnvironmentSecretResolver secretResolver = new EnvironmentSecretResolver();
            EvidencePolicy evidencePolicy = new EvidencePolicy(
                    true, true,
                    Set.of("authorization", "cookie", "set-cookie", "proxy-authorization"),
                    10 * 1024 * 1024, 1000, List.of(), Set.of(), "session");
            Map<String, Object> connectorConfig = new LinkedHashMap<>();
            connectorConfig.put("baseUrl", targetUrl);
            connectorConfig.put("maxResponseBytes", targetPolicy.maxPayloadBytes());
            if (authSecretId != null) {
                connectorConfig.put("authSecretId", authSecretId);
            }
            ConnectorContext connectorContext = new ConnectorContext(
                    evidencePolicy,
                    secretResolver::resolve,
                    5000, 30000, 60000,
                    connectorConfig);

            String scenarioDigest = "sha256:" + sha256Hex(scenarioContent);
            String catalogDigest = "sha256:" + sha256Hex(MAPPER.writeValueAsString(catalog));
            RunId runId = new RunId("run-" + seed);

            PlanCompiler compiler = new PlanCompiler();
            PlanCompilationResult compilation = compiler.compile(
                    scenario, catalog, targetPolicy, runId, seed,
                    scenarioDigest, catalogDigest);

            if (compilation.plan() == null) {
                System.err.println("Plan compilation failed:");
                compilation.diagnostics().forEach(d -> System.err.println("  " + d.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }

            compilation.diagnostics().stream()
                    .filter(d -> d.severity() == PlanDiagnostic.Severity.WARNING)
                    .forEach(d -> System.err.println("Warning: " + d.message()));

            Map<String, AssertionProvider> assertionProviders = Map.of(
                    "status", new StatusAssertionProvider(),
                    "duration", new DurationAssertionProvider(),
                    "header", new HeaderAssertionProvider(),
                    "jsonpath", new JsonPathAssertionProvider());

            Files.createDirectories(outputDir);
            Path journalPath = outputDir.resolve("events.ndjson");

            RunResult result;
            try (HttpConnector httpConnector = allowPrivate
                    ? new HttpConnector(DestinationPolicy.permissive())
                    : new HttpConnector()) {
                LocalEngine engine = new LocalEngine(
                        Map.of("http", httpConnector), assertionProviders);
                try (RunJournal journal = new RunJournal(journalPath, true)) {
                    System.out.println("Running scenario: " + scenario.metadata().name());
                    System.out.println("Target: " + targetUrl);
                    System.out.println("Seed: " + seed);
                    System.out.println();

                    result = engine.execute(
                            compilation.plan(), journal, exprContext,
                            connectorContext, new AtomicBoolean(false));
                }
            }

            List<RunEvent> events = loadEvents(journalPath);
            for (String format : formats) {
                ReportRenderer renderer = buildRenderer(format);

                if ("console".equals(format)) {
                    renderer.render(events, new PrintWriter(System.out, true));
                } else {
                    String ext = switch (format.toLowerCase(Locale.ROOT)) {
                        case "json" -> ".json";
                        case "junit" -> ".xml";
                        case "html" -> ".html";
                        default -> "." + format;
                    };
                    Path reportPath = outputDir.resolve("report" + ext);
                    try (Writer w = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
                        renderer.render(events, w);
                    }
                    System.out.println("Report written: " + reportPath);
                }
            }

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

    private ApiCatalog importCatalog(Path openApiPath) throws IOException {
        String content = Files.readString(openApiPath, StandardCharsets.UTF_8);
        OpenApiImporter importer = new OpenApiImporter();
        ImportContext context = new ImportContext(
                "openapi", Path.of("."), Set.of(), 10, 1_000_000, Map.of());
        ImportResult result = importer.importSource(content, context);

        if (!result.isSuccess()) {
            System.err.println("Failed to import OpenAPI document:");
            result.errors().forEach(e -> System.err.println("  " + e.message()));
            throw new CliException("OpenAPI import failed", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return result.catalog();
    }

    private ApiCatalog buildCatalogFromScenario(ScenarioDocument scenario, String targetUrl) {
        var targetId = new TargetId("default");
        var target = new TargetDefinition(
                targetId, "Default", targetUrl,
                List.of(new ProtocolId("http")), List.of(), Map.of());

        Set<String> operationIds = new LinkedHashSet<>();
        collectOperationIds(scenario.setup(), operationIds);
        collectOperationIds(scenario.execute(), operationIds);
        collectOperationIds(scenario.cleanup(), operationIds);

        List<OperationDefinition> operations = new ArrayList<>();
        for (String opId : operationIds) {
            operations.add(new OperationDefinition(
                    new OperationId(opId), new ProtocolId("http"), targetId,
                    SafetyClassification.READ_ONLY,
                    Map.of(), null, Map.of(),
                    Map.of("method", "GET", "path", "/" + opId)));
        }

        return new ApiCatalog(
                new CatalogVersion("v1alpha1"),
                List.of(target), operations,
                Map.of(), Map.of(), List.of());
    }

    private void collectOperationIds(List<dev.faultora.spec.model.ScenarioStep> steps, Set<String> ids) {
        if (steps == null) return;
        for (var step : steps) {
            if (step.operationId() != null) ids.add(step.operationId());
        }
    }

    private List<RunEvent> loadEvents(Path journalPath) throws IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<RunEvent> events = new ArrayList<>();
        try (var reader = Files.newBufferedReader(journalPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    events.add(mapper.readValue(line, RunEvent.class));
                }
            }
        }
        return events;
    }

    private ReportRenderer buildRenderer(String format) {
        return switch (format) {
            case "console" -> new ConsoleRenderer();
            case "json" -> new JsonRenderer();
            case "junit" -> new JUnitXmlRenderer();
            case "html" -> new HtmlRenderer();
            default -> null;
        };
    }

    private List<String> parseFormats(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(format -> !format.isEmpty())
                .map(format -> format.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private long parseSeed(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new CliException(
                    "Option --seed requires an integer", FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    private String requireNext(Iterator<String> it, String flag) {
        if (!it.hasNext()) {
            throw new CliException("Option " + flag + " requires a value", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return it.next();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void printHelp() {
        System.out.println("Usage: faultora test --scenario <path> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -s, --scenario <path>      Scenario YAML file (required)");
        System.out.println("  -o, --openapi <path>       OpenAPI document for catalog");
        System.out.println("  -t, --target <url>         Target base URL (default: http://localhost:8080)");
        System.out.println("  -f, --format <formats>     Output formats: console,json,junit,html (default: console)");
        System.out.println("  --output <dir>             Output directory (default: faultora-results)");
        System.out.println("  --seed <n>                 Random seed (default: current time)");
        System.out.println("  --allow-private            Allow connections to private/local networks");
        System.out.println("  --auth-secret-id <id>      Secret handle ID for Authorization header (resolved from env)");
        System.out.println("  -h, --help                 Show this help");
        System.out.println();
        System.out.println("Exit codes:");
        System.out.println("  0  All tests passed");
        System.out.println("  1  Test failure (assertion failed)");
        System.out.println("  2  Invalid configuration");
        System.out.println("  3  Runner failure");
    }
}
