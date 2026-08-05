package dev.faultora.runner;

import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.PlanCompilationResult;
import dev.faultora.engine.plan.PlanCompiler;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.Refusal;
import dev.faultora.runtime.CatalogAssembly;
import dev.faultora.runtime.RunEnvironment;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.parser.ParseResult;
import dev.faultora.spec.parser.ScenarioParser;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.contract.FaultProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * One dispatched run, from the bytes that arrived to the result and the journal.
 * <p>
 * The runner compiles rather than being handed a plan (ADR-020), so this is
 * where "same compiler, same inputs, same seed" stops being a claim: the
 * scenario is parsed, the documents become a catalog through the same assembly
 * the CLI uses, and the plan is compiled by the same compiler. What differs
 * from a local run is only what a lease adds — a bound the run cannot outlive,
 * enforced here with nothing to ask.
 * <p>
 * The journal is written to the runner's own working directory as it goes.
 * That is not a convenience: events produced while the far side is unreachable
 * have to survive until it is reachable again, and a file is what survives.
 */
public final class DispatchedRun {

    private final DispatchVerifier verifier;
    private final Path workingDirectory;
    private final Function<String, dev.faultora.model.security.SecretHandle> secrets;
    private final boolean allowPrivate;

    /**
     * @param verifier         decides whether a dispatch may run at all
     * @param workingDirectory where journals are written; must be writable, and
     *                         the packaging has to provide it
     * @param secrets          resolves secret handles from this runner's own
     *                         environment — a dispatch names handles and never
     *                         values, so this is the only place values exist
     * @param allowPrivate     whether this deployment may reach private ranges
     */
    public DispatchedRun(
            DispatchVerifier verifier,
            Path workingDirectory,
            Function<String, dev.faultora.model.security.SecretHandle> secrets,
            boolean allowPrivate
    ) {
        this.verifier = verifier;
        this.workingDirectory = workingDirectory;
        this.secrets = secrets;
        this.allowPrivate = allowPrivate;
    }

    /**
     * Execute a dispatch, or say why it will not be.
     *
     * @param faultProviders the providers this deployment offers
     * @param extensions     which extensions are permitted here
     */
    public Outcome execute(
            Dispatch dispatch,
            Map<String, FaultProvider> faultProviders,
            ExtensionPolicy extensions
    ) {
        long receivedAt = System.currentTimeMillis();
        DispatchVerifier.Verdict verdict = verifier.verify(dispatch, receivedAt);
        if (!verdict.isAccepted()) {
            return Outcome.refused(verdict.refusal());
        }

        ParseResult<ScenarioDocument> parsed = new ScenarioParser().parse(dispatch.scenario());
        if (!parsed.isSuccess() || parsed.document() == null) {
            return Outcome.refused(Refusal.of(Refusal.Reason.DIGEST_MISMATCH,
                    "the scenario hashed correctly and did not parse: "
                            + firstProblem(parsed)));
        }
        ScenarioDocument scenario = parsed.document();

        try {
            return run(dispatch, scenario, verdict.policy(), faultProviders,
                    extensions, receivedAt);
        } catch (CatalogAssembly.AssemblyException cannotAssemble) {
            return Outcome.refused(Refusal.of(Refusal.Reason.MISSING_CAPABILITY,
                    cannotAssemble.getMessage()));
        } catch (Exception failed) {
            return Outcome.refused(Refusal.of(Refusal.Reason.MISSING_CAPABILITY,
                    "run '" + dispatch.runId() + "' could not be executed here: "
                            + failed.getMessage()));
        }
    }

    private Outcome run(
            Dispatch dispatch,
            ScenarioDocument scenario,
            TargetPolicy policy,
            Map<String, FaultProvider> faultProviders,
            ExtensionPolicy extensions,
            long receivedAt
    ) throws Exception {
        List<CatalogAssembly.Document> documents = new ArrayList<>();
        for (DispatchedDocument document : dispatch.documents()) {
            documents.add(new CatalogAssembly.Document(
                    document.family(), document.content()));
        }
        var catalog = documents.isEmpty()
                ? CatalogAssembly.fromScenario(
                        scenario, dispatch.targetRedirects().getOrDefault("", ""))
                : CatalogAssembly.assemble(documents, extensions);

        RunId runId = new RunId(dispatch.runId());
        PlanCompilationResult compilation = new PlanCompiler().compile(
                scenario, catalog, policy, runId, dispatch.seed(),
                dispatch.scenarioDigest(), dispatch.catalogDigest());
        if (compilation.plan() == null) {
            StringBuilder why = new StringBuilder("the scenario does not compile here:");
            compilation.diagnostics().forEach(
                    diagnostic -> why.append("\n  ").append(diagnostic.message()));
            return Outcome.refused(
                    Refusal.of(Refusal.Reason.MISSING_CAPABILITY, why.toString()));
        }

        Files.createDirectories(workingDirectory);
        Path journalPath = workingDirectory.resolve(dispatch.runId() + ".ndjson");
        Files.deleteIfExists(journalPath);

        AtomicBoolean cancellation = new AtomicBoolean(false);
        try (LeaseWatch lease = new LeaseWatch(dispatch.lease(), receivedAt, cancellation);
             RunEnvironment environment = RunEnvironment.open(
                     compilation.plan(), faultProviders, extensions, allowPrivate);
             RunJournal journal = new RunJournal(journalPath, true)) {
            lease.start();
            RunResult result = environment.engine().execute(
                    compilation.plan(), journal,
                    expressionContext(dispatch), connectorContext(dispatch, policy),
                    cancellation);
            return Outcome.ran(result, journalPath, lease.hasExpired());
        }
    }

    /**
     * What a scenario's templates resolve against.
     * <p>
     * The runtime inputs travel in the dispatch because no digest covers them:
     * two dispatches with identical documents and different inputs are
     * different runs.
     */
    private static ExpressionContext expressionContext(Dispatch dispatch) {
        return ExpressionContext.builder()
                .inputs(Map.copyOf(dispatch.inputs()))
                .runMetadata(Map.of("seed", dispatch.seed(),
                        "target", dispatch.targetRedirects().getOrDefault("", "")))
                .build();
    }

    /**
     * What the connectors are configured with.
     * <p>
     * Secret <em>handles</em> come from the dispatch and values from this
     * runner's own environment, which is the property ADR-021 states: nothing
     * that has ever been a credential crosses the wire.
     */
    private ConnectorContext connectorContext(Dispatch dispatch, TargetPolicy policy) {
        Map<String, Object> config = new LinkedHashMap<>();
        dispatch.targetRedirects().forEach((targetId, url) -> {
            if (targetId.isEmpty()) {
                config.put("baseUrl", url);
            } else {
                config.put("baseUrl." + targetId, url);
            }
        });
        config.put("maxResponseBytes", policy.maxPayloadBytes());
        return new ConnectorContext(
                EvidencePolicy.MINIMAL, secrets::apply, 5000, 30000, 60000, config);
    }

    private static String firstProblem(ParseResult<ScenarioDocument> parsed) {
        return parsed.diagnostics().isEmpty()
                ? "no diagnostic" : parsed.diagnostics().get(0).message();
    }

    /**
     * What became of a dispatch.
     *
     * @param result       the run's result, null when it never ran
     * @param journalPath  where the events were written, null when it never ran
     * @param leaseExpired whether the lease is what ended it
     * @param refusal      why it did not run, null when it did
     */
    public record Outcome(
            RunResult result, Path journalPath, boolean leaseExpired, Refusal refusal) {

        static Outcome ran(RunResult result, Path journalPath, boolean leaseExpired) {
            return new Outcome(result, journalPath, leaseExpired, null);
        }

        static Outcome refused(Refusal refusal) {
            return new Outcome(null, null, false, refusal);
        }

        public boolean didRun() {
            return refusal == null;
        }
    }
}
