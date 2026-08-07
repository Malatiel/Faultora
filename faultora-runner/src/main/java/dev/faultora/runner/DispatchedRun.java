package dev.faultora.runner;

import dev.faultora.connector.http.HttpConnector;
import dev.faultora.connector.jdbc.JdbcConnector;
import dev.faultora.engine.exec.TargetResolver;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.PlanCompilationResult;
import dev.faultora.engine.plan.PlanCompiler;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.Refusal;
import dev.faultora.runtime.CatalogAssembly;
import dev.faultora.runtime.RunEnvironment;
import dev.faultora.runtime.RunEvidence;
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
     * Told once, when a run has begun and before it has finished.
     * <p>
     * Everything that has to happen <em>while</em> a run runs hangs off this:
     * renewing the lease, and sending the journal as it grows. Neither belongs
     * in here — this class executes a run, and what talks to the far side is
     * the far side's business — but both need the two things this hands over.
     */
    public interface WhileRunning {

        /**
         * @param lease       the run's permission, which something has to keep
         *                    renewing or the run will stop
         * @param journalPath the file the run is writing as it goes
         */
        void started(LeaseWatch lease, Path journalPath);
    }

    /** Execute a dispatch with nothing watching it. */
    public Outcome execute(
            Dispatch dispatch,
            Map<String, FaultProvider> faultProviders,
            ExtensionPolicy extensions
    ) {
        return execute(dispatch, faultProviders, extensions, (lease, journal) -> { });
    }

    /**
     * Execute a dispatch, or say why it will not be.
     *
     * @param faultProviders the providers this deployment offers
     * @param extensions     which extensions are permitted here
     * @param whileRunning   told once the run has begun, so a lease can be
     *                       renewed and a journal streamed
     */
    public Outcome execute(
            Dispatch dispatch,
            Map<String, FaultProvider> faultProviders,
            ExtensionPolicy extensions,
            WhileRunning whileRunning
    ) {
        long receivedAt = System.currentTimeMillis();
        DispatchVerifier.Verdict verdict = verifier.verify(dispatch, receivedAt);
        if (!verdict.isAccepted()) {
            return Outcome.refused(verdict.refusal());
        }

        ParseResult<ScenarioDocument> parsed = new ScenarioParser().parse(dispatch.scenario());
        if (!parsed.isSuccess() || parsed.document() == null) {
            return Outcome.refused(Refusal.of(Refusal.Reason.SCENARIO_INVALID,
                    "the scenario arrived intact and does not parse: "
                            + firstProblem(parsed)));
        }
        ScenarioDocument scenario = parsed.document();

        try {
            return run(dispatch, scenario, verdict.policy(), faultProviders,
                    extensions, receivedAt, whileRunning);
        } catch (CatalogAssembly.AssemblyException cannotAssemble) {
            return Outcome.refused(Refusal.of(Refusal.Reason.MISSING_CAPABILITY,
                    cannotAssemble.getMessage()));
        } catch (StartupFailure notStarted) {
            return Outcome.refused(Refusal.of(Refusal.Reason.MISSING_CAPABILITY,
                    "run '" + dispatch.runId() + "' could not be started here: "
                            + notStarted.getMessage()));
        } catch (Exception broke) {
            // The run had begun, so there is a journal, and whoever is waiting
            // for this run needs to be told where it is. Reporting this as a
            // refusal would say nothing happened while a partial journal sat on
            // disk — the events a run produced before it broke are the most
            // useful thing it produced.
            return Outcome.broke(
                    workingDirectory.resolve(dispatch.runId() + ".ndjson"),
                    "run '" + dispatch.runId() + "' stopped part-way: "
                            + broke.getMessage());
        }
    }

    /** Thrown when a run could not be started, as opposed to breaking once it had. */
    private static final class StartupFailure extends RuntimeException {
        private StartupFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private Outcome run(
            Dispatch dispatch,
            ScenarioDocument scenario,
            TargetPolicy policy,
            Map<String, FaultProvider> faultProviders,
            ExtensionPolicy extensions,
            long receivedAt,
            WhileRunning whileRunning
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
                    Refusal.of(Refusal.Reason.SCENARIO_INVALID, why.toString()));
        }

        Path journalPath;
        try {
            Files.createDirectories(workingDirectory);
            journalPath = workingDirectory.resolve(dispatch.runId() + ".ndjson");
            Files.deleteIfExists(journalPath);
        } catch (Exception noWhereToWrite) {
            throw new StartupFailure(
                    "the working directory is not writable: " + workingDirectory,
                    noWhereToWrite);
        }

        AtomicBoolean cancellation = new AtomicBoolean(false);
        try (LeaseWatch lease = new LeaseWatch(dispatch.lease(), receivedAt, cancellation);
             RunEnvironment environment = RunEnvironment.open(
                     compilation.plan(), faultProviders, extensions, allowPrivate);
             RunJournal journal = new RunJournal(journalPath, true)) {
            lease.start();
            whileRunning.started(lease, journalPath);
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
    // Package-private so a test can read the map rather than infer it from a
    // run that happened not to need a credential. What keys the connectors
    // are handed is the thing that drifts, so it is the thing asserted.
    ConnectorContext connectorContext(Dispatch dispatch, TargetPolicy policy) {
        Map<String, Object> config = new LinkedHashMap<>();
        dispatch.targetRedirects().forEach((targetId, url) -> config.put(
                targetId.isEmpty()
                        ? TargetResolver.BASE_URL : TargetResolver.BASE_URL_PREFIX + targetId,
                url));
        config.put("maxResponseBytes", policy.maxPayloadBytes());

        Dispatch.Credentials credentials = dispatch.credentials();
        if (credentials.authSecretId() != null) {
            config.put(HttpConnector.AUTH_SECRET_ID, credentials.authSecretId());
        }
        if (credentials.databaseUser() != null) {
            config.put(JdbcConnector.USER, credentials.databaseUser());
        }
        if (credentials.databaseSecretId() != null) {
            config.put(JdbcConnector.SECRET_ID, credentials.databaseSecretId());
        }

        // The same evidence a local run keeps. It was MINIMAL here, which
        // captures no bodies and no rows — so a row-balance assertion that
        // passes on the machine the scenario was written on came back
        // indeterminate from a runner, for no reason the author could see.
        return new ConnectorContext(
                RunEvidence.defaultPolicy(), secrets::apply, 5000, 30000, 60000, config);
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
            RunResult result, Path journalPath, boolean leaseExpired,
            Refusal refusal, String failure) {

        static Outcome ran(RunResult result, Path journalPath, boolean leaseExpired) {
            return new Outcome(result, journalPath, leaseExpired, null, null);
        }

        /** Never started: no journal exists, and the reason is a rule. */
        static Outcome refused(Refusal refusal) {
            return new Outcome(null, null, false, refusal, null);
        }

        /** Started and stopped part-way: the journal holds what it managed. */
        static Outcome broke(Path journalPath, String failure) {
            return new Outcome(null, journalPath, false, null, failure);
        }

        /** Whether the engine was reached at all. */
        public boolean didRun() {
            return refusal == null;
        }

        /** Whether a result came back, as opposed to the run breaking part-way. */
        public boolean completed() {
            return result != null;
        }
    }
}
