package dev.faultora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.engine.exec.JournalWriter;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.PlanCompilationResult;
import dev.faultora.engine.plan.PlanDiagnostic;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.runtime.RunInputs;
import dev.faultora.runtime.ExtensionRegistry;
import dev.faultora.runtime.RunPreparation;
import dev.faultora.runtime.PreparedRun;
import dev.faultora.runtime.RunArtifacts;
import dev.faultora.runtime.RuntimeCapabilities;
import dev.faultora.runtime.PlanPreview;
import dev.faultora.runtime.CatalogAssembly;
import dev.faultora.spec.model.ScenarioDocument;

import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.contract.ReportRenderer;
import dev.faultora.spi.context.ConnectorContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private final CommandOutput output;

    public TestCommand() {
        this(CommandOutput.human());
    }

    TestCommand(CommandOutput output) {
        this.output = output;
    }

    /** How long an interrupted run is given to roll back and report. */
    private static final long SHUTDOWN_GRACE_SECONDS = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public int execute(List<String> args) {
        TestOptions options;
        try {
            options = TestOptions.parse(args);
        } catch (CliException e) {
            output.error(CliDiagnostic.Code.INVALID_ARGUMENT, null, null);
            output.err().println(e.getMessage());
            return e.exitCode();
        }
        if (options.helpRequested()) {
            printHelp();
            return FaultoraCli.EXIT_PASS;
        }

        ExtensionPolicy extensionPolicy = RunPolicies.extensionPolicy(options);
        Map<String, ReportRenderer> renderers = ExtensionRegistry.renderers(extensionPolicy);
        if (!renderers.keySet().containsAll(options.formats())) {
            output.error(CliDiagnostic.Code.CAPABILITY_UNAVAILABLE, null, null);
            output.err().println("Unknown format. Supported formats: "
                    + String.join(",", renderers.keySet()));
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }

        CliDiagnostic.Code failureStage = CliDiagnostic.Code.FILE_READ_FAILED;
        try {
            String scenarioContent = Files.readString(
                    options.scenarioPath(), StandardCharsets.UTF_8);
            var documents = CatalogLoader.documents(options);
            failureStage = CliDiagnostic.Code.PREPARATION_FAILED;
            TargetPolicy targetPolicy = RunPolicies.targetPolicy(options);
            RunId runId = RunArtifacts.newRunId();
            RunPreparation.Request request = new RunPreparation.Request(
                    scenarioContent, documents, options.targetUrl(),
                    extensionPolicy, targetPolicy, runId, options.seed(), null);
            RunPreparation.Result prepared = RunPreparation.prepare(request);
            output.scenario(prepared.diagnostics());
            if (prepared.compilation() != null) {
                output.plan(prepared.compilation().diagnostics());
            }
            if (options.preview()) {
                if (prepared.isSuccess()) {
                    resolveDeclaredInputs(prepared.scenario(), options.inputs());
                }
                PlanPreview preview = PlanPreview.describe(prepared,
                        RuntimeCapabilities.describe(extensionPolicy, targetPolicy, RunPolicies.faultTypes(options)),
                        targetPolicy);
                output.preview(preview);
                output.out().println(MAPPER.writeValueAsString(preview));
                return prepared.isSuccess() ? FaultoraCli.EXIT_PASS : FaultoraCli.EXIT_INVALID_CONFIG;
            }
            prepared.diagnostics().forEach(diagnostic -> output.err().println(
                    diagnostic.severity() + ": " + diagnostic.message()));
            if (prepared.compilation() == null) {
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }
            ScenarioDocument scenario = prepared.scenario();
            PlanCompilationResult compilation = prepared.compilation();
            if (compilation.plan() == null) {
                output.err().println("Plan compilation failed:");
                compilation.diagnostics().forEach(d -> output.err().println("  " + d.message()));
                return FaultoraCli.EXIT_INVALID_CONFIG;
            }
            compilation.diagnostics().stream()
                    .filter(d -> d.severity() == PlanDiagnostic.Severity.WARNING)
                    .forEach(d -> output.err().println("Warning: " + d.message()));

            ExpressionContext expressionContext = ExpressionContext.builder()
                    .inputs(resolveDeclaredInputs(scenario, options.inputs()))
                    .runMetadata(Map.of("seed", options.seed(), "target", options.targetUrl()))
                    .build();
            failureStage = CliDiagnostic.Code.RUN_ERROR;
            Map<String, FaultProvider> faultProviders = RunPolicies.faultProviders(options);
            ConnectorContext connectorContext = RunPolicies.connectorContext(
                    options, targetPolicy, new EnvironmentSecretResolver());
            PreparedRun.Settings settings = new PreparedRun.Settings(expressionContext, connectorContext,
                    options.allowPrivate(), RunPolicies.faultTypes(options));
            PreparedRun sealed = PreparedRun.prepare(request, settings, java.time.Duration.ofMinutes(5));

            failureStage = CliDiagnostic.Code.ARTIFACT_WRITE_FAILED;
            Path outputDir = options.outputDir() == null
                    ? Path.of("faultora-results").resolve(runId.value()) : options.outputDir();
            RunArtifacts artifacts = RunArtifacts.claim(outputDir, runId);
            Path journalPath = artifacts.journalPath();

            failureStage = CliDiagnostic.Code.RUN_ERROR;
            ExecutedRun executed = run(
                    options, sealed, request, settings, faultProviders, journalPath, scenario);
            RunResult result = executed.result();
            output.run(result);

            failureStage = CliDiagnostic.Code.REPORT_WRITE_FAILED;
            ReportWriter.writeAll(
                    options.formats(), renderers, executed.events(), outputDir, output.out());

            output.out().printf("%nResult: %s — %d nodes, %d passed, %d failed (%dms)%n",
                    result.status(), result.totalNodes(),
                    result.passedAssertions(), result.failedAssertions(),
                    result.durationMs());

            return switch (result.status()) {
                case PASSED -> FaultoraCli.EXIT_PASS;
                case FAILED -> FaultoraCli.EXIT_TEST_FAILURE;
                // SKIPPED is a node outcome; a run reporting it has a defect.
                case ERROR, CANCELLED, SKIPPED -> FaultoraCli.EXIT_RUNNER_FAILURE;
            };

        } catch (RunArtifacts.AlreadyOwned owned) {
            output.error(CliDiagnostic.Code.ARTIFACT_WRITE_FAILED, null, null);
            output.err().println("Output already contains run artifacts; choose a new --output directory");
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        } catch (CatalogAssembly.AssemblyException refused) {
            output.error(CliDiagnostic.Code.CATALOG_IMPORT_FAILED, null, null);
            throw new CliException(refused.getMessage(), FaultoraCli.EXIT_INVALID_CONFIG);
        } catch (CliException e) {
            if (!output.hasErrors()) {
                output.error(CliDiagnostic.Code.INVALID_ARGUMENT, null, null);
            }
            throw e;
        } catch (Exception e) {
            output.error(failureStage, null, null);
            output.err().println("Runner error; provider details withheld by export policy");
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }
    }

    private record ExecutedRun(RunResult result, List<RunEvent> events) { }

    /**
     * Execute the compiled plan.
     * <p>
     * The connector stack is assembled here rather than discovered: the
     * destination policy and the fault-injecting wrapper decide what this run
     * may reach and what may be broken, so they are the operator's choice, not
     * the classpath's.
     */
    private ExecutedRun run(
            TestOptions options,
            PreparedRun prepared,
            RunPreparation.Request request,
            PreparedRun.Settings settings,
            Map<String, FaultProvider> faultProviders,
            Path journalPath,
            ScenarioDocument scenario
    ) throws IOException {
        // Which clients a plan needs, and the engine over them, is the same
        // question however a run was started — so it is answered in one place
        // that the runner asks too, rather than here where only the CLI can see
        // it. What stays here is what belongs to a command line: what to print.
        try (RunJournal journal = new RunJournal(journalPath, true)) {
            output.out().println("Running scenario: " + scenario.metadata().name());
            output.out().println("Target: configured for this run");
            output.out().println("Seed: " + options.seed());
            output.out().println("Run: " + request.runId().value());
            output.out().println();

            RunResult result = executeCancellably(
                    prepared, request, settings, faultProviders, journal);
            result = JournalWriter.closeJournal(journal, result);
            return new ExecutedRun(result, journal.events());
        }
    }




    /**
     * Execute the plan, letting an interrupted process end its own run.
     * <p>
     * Without this, Ctrl+C kills the JVM mid-run: injected faults are never
     * rolled back, cleanup obligations are dropped, and the journal has no
     * terminal event. The handler asks the run to stop and waits for it, so a
     * cancelled run still rolls back what it injected and reports why it
     * ended. A signal that cannot be caught remains outside anyone's reach.
     */
    private RunResult executeCancellably(
            PreparedRun prepared,
            RunPreparation.Request request,
            PreparedRun.Settings settings,
            Map<String, FaultProvider> faultProviders,
            RunJournal journal
    ) {
        AtomicBoolean cancellation = new AtomicBoolean(false);
        CountDownLatch finished = new CountDownLatch(1);
        Thread interruption = new Thread(() -> {
            output.err().println("Interrupted: cancelling the run and rolling back faults");
            cancellation.set(true);
            try {
                if (!finished.await(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    output.err().println("Run did not stop within "
                            + SHUTDOWN_GRACE_SECONDS + "s; faults may remain active");
                }
            } catch (InterruptedException stopWaiting) {
                Thread.currentThread().interrupt();
            }
        }, "faultora-interrupt");

        Runtime.getRuntime().addShutdownHook(interruption);
        try {
            return prepared.execute(request, settings, faultProviders, journal, cancellation);
        } finally {
            finished.countDown();
            // The hook has done its job; leaving it registered would pile up
            // across the runs of a single process.
            try {
                Runtime.getRuntime().removeShutdownHook(interruption);
            } catch (IllegalStateException alreadyShuttingDown) {
                // Shutdown is in progress and the hook is running: nothing to remove.
            }
        }
    }

    /**
     * Merge CLI-provided inputs with the scenario's declared defaults.
     * Unknown input names and missing required inputs are configuration errors.
     */
    private Map<String, Object> resolveDeclaredInputs(
            ScenarioDocument scenario, Map<String, Object> cliInputs) {
        try {
            return RunInputs.resolve(scenario, cliInputs);
        } catch (RunInputs.InvalidInput invalid) {
            String name = invalid.name();
            if (invalid.missing()) {
                output.error(CliDiagnostic.Code.INPUT_REQUIRED, null, "inputs." + name);
                throw new CliException(
                        "Required input '" + name + "' is missing. Provide it with --input "
                                + name + "=<value>",
                        FaultoraCli.EXIT_INVALID_CONFIG);
            }
            output.error(CliDiagnostic.Code.INPUT_UNKNOWN, null, "inputs." + name);
            Map<String, ?> declared = scenario.inputs() == null ? Map.of() : scenario.inputs();
            throw new CliException(
                    "Unknown input '" + name + "'. Declared inputs: "
                            + (declared.isEmpty() ? "(none)" : String.join(", ", declared.keySet())),
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    private void printHelp() {
        output.out().println("Usage: faultora test --scenario <path> [options]");
        output.out().println();
        output.out().println("Options:");
        output.out().println("  -s, --scenario <path>      Scenario YAML file (required)");
        output.out().println("  -o, --openapi <path>       OpenAPI document for catalog");
        output.out().println("  -a, --asyncapi <path>      AsyncAPI 3.0 document for catalog");
        output.out().println("      --observations <path>  Observation catalog: the read-only");
        output.out().println("                             queries a run is permitted to make");
        output.out().println("      --db-user <name>       User the observations connect as");
        output.out().println("      --db-secret-id <id>    Secret handle supplying its password");
        output.out().println("  -t, --target <url>         Base URL for every catalog target");
        output.out().println("                             (default: " + TestOptions.DEFAULT_TARGET_URL + ")");
        output.out().println("  -t, --target <id>=<url>    Base URL for one catalog target (repeatable)");
        output.out().println("  -f, --format <formats>     Output formats: console,json,junit,html (default: console)");
        output.out().println("      --output <dir>         Unused artifact directory (default: faultora-results/<run-id>)");
        output.out().println("      --seed <n>             Random seed (default: current time)");
        output.out().println("      --preview              Print structural JSON without executing or writing reports");
        output.out().println("      --allow-private        Allow connections to private/local networks");
        output.out().println("      --allow-destructive    Allow operations classified as destructive");
        output.out().println("      --auth-secret-id <id>  Secret handle ID for Authorization header (resolved from env)");
        output.out().println("      --toxiproxy-url <url>  Toxiproxy admin endpoint; enables network-* fault types");
        output.out().println("      --allow-extension <class>  Permit a non-built-in extension (repeatable)");
        output.out().println("  -i, --input <key=value>    Value for a declared scenario input (repeatable)");
        output.out().println("  -h, --help                 Show this help");
        output.out().println();
        output.out().println("Exit codes:");
        output.out().println("  0  All tests passed");
        output.out().println("  1  Test failure (assertion failed)");
        output.out().println("  2  Invalid configuration");
        output.out().println("  3  Runner failure");
    }
}
