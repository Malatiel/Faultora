package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.faults.local.LocalFaultProvider;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.SecretHandle;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.DispatchVerifier;
import dev.faultora.runner.DispatchedRun;
import dev.faultora.runner.LocalLimits;
import dev.faultora.runner.RunnerAgent;
import dev.faultora.runner.RunnerClient;
import dev.faultora.runner.TlsMaterial;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.testkit.Certificates;
import dev.faultora.testkit.QualificationDispatcher;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A runner in a private network, and the smallest thing that can dispatch to it.
 * <p>
 * Everything here is the shipped code: the runner's own client, verifier and
 * execution, the qualification dispatcher from the test kit, and mutual TLS
 * over a real socket. What this class is, is the operator — it issues the key
 * material, states the policy it signs, and hands over a scenario. It exists so
 * a suite can say <em>run this remotely</em> in one line and mean it.
 * <p>
 * The dispatcher is deliberately not a controller and this is deliberately not
 * a second way to configure a run: the policy below is the CLI's, so a suite
 * running both ways is comparing two executions of the same rules rather than
 * two sets of rules that happen to agree.
 */
final class RemoteRunner implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The key the dispatcher signs with, and the only one this runner trusts. */
    private static final String SIGNING_KEY = "qualification";

    private final QualificationDispatcher dispatcher;
    private final RunnerClient client;
    private final RunnerAgent agent;
    private final String sessionId;
    private final LocalFaultProvider faults = new LocalFaultProvider();

    /**
     * Start a runner and a dispatcher that trust each other and nobody else.
     *
     * @param directory where key material and the runner's journals are written
     * @param secrets   what the runner resolves credential handles from — its
     *                  own environment, never the dispatch
     */
    RemoteRunner(Path directory, Map<String, String> secrets) throws Exception {
        Files.createDirectories(directory);
        Certificates.Identity runnerIdentity =
                Certificates.issue(directory, "runner", 1);
        Certificates.Identity dispatcherIdentity =
                Certificates.issue(directory, "dispatcher", 1);
        TlsMaterial runnerTls = new TlsMaterial(
                runnerIdentity.keystore(),
                Certificates.trusting(directory, "runner", dispatcherIdentity),
                () -> Certificates.PASSWORD.toCharArray());
        TlsMaterial dispatcherTls = new TlsMaterial(
                dispatcherIdentity.keystore(),
                Certificates.trusting(directory, "dispatcher", runnerIdentity),
                () -> Certificates.PASSWORD.toCharArray());

        dispatcher = new QualificationDispatcher(dispatcherTls.sslContext());
        client = new RunnerClient(
                URI.create(dispatcher.address()), runnerTls,
                "qualification-runner", "0.9.0-SNAPSHOT",
                Set.of("http", "kafka", "jdbc"));
        agent = new RunnerAgent(
                client,
                new DispatchedRun(
                        new DispatchVerifier(limits(), signed ->
                                SIGNING_KEY.equals(signed.keyId())),
                        directory.resolve("journals"),
                        handleId -> handleOf(handleId, secrets.get(handleId)),
                        true),
                Map.of("local", faults),
                new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of()));
        sessionId = client.register().sessionId();
    }

    /**
     * What this deployment permits, stated once.
     * <p>
     * Wider than the policy any dispatch here asks for, because a runner that
     * refused the suite would produce a green build proving nothing. What it is
     * <em>not</em> is unbounded: fault types are named, and a dispatch asking
     * for one that is not here is refused rather than narrowed.
     */
    private static LocalLimits limits() {
        return new LocalLimits(
                Set.of(), Set.of(SafetyClassification.READ_ONLY, SafetyClassification.MUTATING),
                Set.of(), new LocalFaultProvider().capabilities(),
                10, 300_000, 1000, 1_048_576);
    }

    /** The policy the CLI would run under, as a dispatch carries it. */
    private static SignedPolicy signedPolicy() throws Exception {
        TargetPolicy policy = new TargetPolicy(
                Set.of(), Set.of(SafetyClassification.READ_ONLY, SafetyClassification.MUTATING),
                1000, 10, 300_000, 1_048_576,
                new LocalFaultProvider().capabilities(), Set.of());
        return new SignedPolicy(
                MAPPER.writeValueAsString(policy), SIGNING_KEY, "c2lnbmF0dXJl");
    }

    private static SecretHandle handleOf(String handleId, String value) {
        return value == null
                ? null
                : new SecretHandle(handleId, "***", "test", -1, value::toCharArray);
    }

    /**
     * One run, as an operator would describe it.
     *
     * @param runId       the run's identity on both sides
     * @param scenario    the scenario file
     * @param documents   descriptions by family, in the order the loader names
     *                    them — the catalog digest is taken over that order
     * @param redirects   target id to base URL, the empty key being the global
     *                    one {@code --target} sets
     * @param credentials handles the runner resolves against its own environment
     * @param seed        the run seed
     * @param leaseTtlMs  how long the run's permission is worth before renewal
     */
    record Request(
            String runId, Path scenario, Map<String, Path> documents,
            Map<String, String> redirects, Dispatch.Credentials credentials,
            long seed, long leaseTtlMs) {
    }

    /** Dispatch a run and see it through, exactly as the agent would in the field. */
    DispatchedRun.Outcome run(Request request) throws Exception {
        dispatcher.grantLeasesOf(request.leaseTtlMs());
        dispatcher.offer(dispatchOf(request));
        return agent.takeOneDispatch(sessionId).orElseThrow(
                () -> new IllegalStateException(
                        "the runner asked for work and was given none: " + request.runId()));
    }

    private static Dispatch dispatchOf(Request request) throws Exception {
        String scenario = Files.readString(request.scenario());
        List<DispatchedDocument> documents = new ArrayList<>();
        for (Map.Entry<String, Path> document : request.documents().entrySet()) {
            documents.add(new DispatchedDocument(
                    document.getKey(), Files.readString(document.getValue())));
        }
        return new Dispatch(
                request.runId(), System.currentTimeMillis(),
                request.runId() + "-nonce", scenario, documents,
                Map.of(), request.redirects(), request.credentials(), request.seed(),
                signedPolicy(),
                new Lease(System.currentTimeMillis(), request.leaseTtlMs(),
                        Math.max(50, request.leaseTtlMs() / 4)),
                ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(documents));
    }

    /**
     * Stop answering heartbeats, and go on listening for everything else.
     * <p>
     * The shape a lease actually expires in. A short lease alone does not end a
     * run — a dispatcher that is there renews it, which is what a lease is for
     * — so a suite that only shortened one would watch the run finish and learn
     * nothing. What ends a run is having nobody left to ask.
     */
    void stopAnsweringHeartbeats() {
        dispatcher.stopExtendingLeases();
    }

    /** The journal as the far side received it, rather than as the runner wrote it. */
    List<String> journalDelivered(String runId) {
        return dispatcher.journalOf(runId);
    }

    /** What the runner said became of the run. */
    String outcomeDelivered(String runId) {
        return dispatcher.outcomeOf(runId);
    }

    /**
     * Faults still registered on this runner.
     * <p>
     * Zero after every run, including a run its lease ended: a fault outliving
     * the run that injected it is the failure the lease exists to prevent, and
     * counting is the only way to see it — the journal says a rollback was
     * written, not that it happened.
     */
    int faultsStillActive() {
        return faults.activeCount();
    }

    /** Documents in the order the loader names them, for the M3 suite. */
    static Map<String, Path> catalog(Path openApi, Path asyncApi, Path observations) {
        Map<String, Path> documents = new LinkedHashMap<>();
        documents.put("openapi", openApi);
        documents.put("asyncapi", asyncApi);
        documents.put("observations", observations);
        return documents;
    }

    @Override
    public void close() {
        dispatcher.close();
    }
}
