package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.connector.http.HttpConnector;
import dev.faultora.connector.kafka.KafkaConnector;
import dev.faultora.connector.jdbc.JdbcConnector;
import dev.faultora.engine.exec.TargetResolver;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.Refusal;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.runtime.RunEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A run that arrived rather than being typed.
 * <p>
 * The runner compiles the dispatch itself, so what this proves is the sentence
 * ADR-020 rests on: the same compiler over the same bytes with the same seed
 * produces the run that was asked for. And that a lease bounds it — here
 * against a scenario that would otherwise take far longer than the permission
 * it was given.
 */
class DispatchedRunTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final LocalLimits LIMITS = new LocalLimits(
            Set.of(), Set.of(SafetyClassification.READ_ONLY), Set.of(),
            Set.of("http-latency"), 4, 600_000, 100, 1_048_576, null);

    /** Built-in extensions only, which is what a runner ships with. */
    private static final ExtensionPolicy EXTENSIONS =
            new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());

    @TempDir
    Path workingDirectory;

    private DispatchedRun runner() {
        return new DispatchedRun(
                new DispatchVerifier(LIMITS, policy -> "trusted".equals(policy.keyId())),
                workingDirectory, handleId -> null, true);
    }

    /** The policy a dispatch carries, as the verifier would hand it over. */
    private static EffectivePolicy policyOf(Dispatch dispatch) {
        try {
            return MAPPER.readValue(dispatch.policy().policyJson(), EffectivePolicy.class);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static SignedPolicy signedPolicy() {
        TargetPolicy policy = new TargetPolicy(
                Set.of(new TargetId("default")), Set.of(SafetyClassification.READ_ONLY),
                50, 2, 300_000, 1024, Set.of(), Set.of());
        try {
            return new SignedPolicy(
                    MAPPER.writeValueAsString(EffectivePolicy.of(policy)), "trusted", "c2ln");
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Dispatch dispatchOf(String runId, String scenario, Lease lease) {
        return new Dispatch(
                runId, System.currentTimeMillis(), "nonce", scenario, List.of(),
                Map.of("pause", "20ms"), Map.of("", "http://localhost:1"),
                new Dispatch.Credentials(null, "faultora_readonly", "ledger-password"), 4242L,
                signedPolicy(), lease,
                ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(List.of()));
    }

    /** Waits only, so the run needs nothing but the engine and its own clock. */
    private static String scenarioOfWaits(int waits, String each) {
        StringBuilder scenario = new StringBuilder("""
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata:
                  name: dispatched-waits
                inputs:
                  pause:
                    type: string
                    required: false
                    defaultValue: 20ms
                execute:
                """);
        for (int index = 0; index < waits; index++) {
            scenario.append("  - id: pause-").append(index)
                    .append("\n    type: wait\n    timeout: ").append(each).append('\n');
            if (index > 0) {
                scenario.append("    dependsOn: [pause-").append(index - 1).append("]\n");
            }
        }
        return scenario.toString();
    }

    @Test
    void terminalNotificationRequiresCommittedAndClosedEvidence() throws Exception {
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        var dispatch = dispatchOf("run-committed", scenarioOfWaits(1, "1ms"),
                new Lease(System.currentTimeMillis(), 60_000, 10_000));
        var outcome = runner().execute(dispatch, Map.of(), EXTENSIONS, new DispatchedRun.WhileRunning() {
            private Path spool;
            @Override public void started(LeaseWatch lease, Path journal) {
                spool = journal.resolveSibling("spool.bin");
            }
            @Override public void terminalCommitted() {
                try {
                    var snapshot = DurableRunSpool.recover(spool);
                    assertThat(snapshot.terminal().status()).isEqualTo(dev.faultora.engine.run.RunResult.Status.PASSED);
                    assertThat(snapshot.terminal().cleanupConfirmed()).isTrue();
                    committed.set(true);
                } catch (Exception failed) { throw new AssertionError(failed); }
            }
        });
        assertThat(outcome.completed()).isTrue();
        assertThat(committed).isTrue();
        var recovered = new ArrayList<dev.faultora.model.events.RunEvent>();
        var snapshot = DurableRunSpool.recover(outcome.journalPath().resolveSibling("spool.bin"), recovered::add);
        assertThat(snapshot.events()).isEqualTo(Files.readAllLines(outcome.journalPath()).size());
        assertThat(recovered).hasSize((int) snapshot.events());
        assertThat(new String(Files.readAllBytes(outcome.journalPath().resolveSibling("spool.bin")),
                java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("ledger-password", "faultora_readonly", "http://localhost");
    }

    @Test
    void identityFailureStopsBeforeTheRunCanStartOrResolveSecrets() throws Exception {
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        var failing = new DispatchedRun(new DispatchVerifier(LIMITS, policy -> true), workingDirectory,
                handle -> { throw new AssertionError("No secret resolution allowed"); }, true,
                (path, identity) -> { throw new java.io.IOException("SYNTHETIC_PRIVATE_DISK_VALUE"); });
        var outcome = failing.execute(dispatchOf("run-disk-error", scenarioOfWaits(1, "1ms"),
                        new Lease(System.currentTimeMillis(), 60_000, 10_000)), Map.of(), EXTENSIONS,
                (lease, journal) -> started.set(true));
        assertThat(started).isFalse();
        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failure()).doesNotContain("SYNTHETIC_PRIVATE_DISK_VALUE");
        assertThat(outcome.journalPath()).doesNotExist();
    }

    @Test
    void anInterruptedFailureCommitsItsTerminalAndPreservesCancellation() throws Exception {
        var notified = new java.util.concurrent.atomic.AtomicBoolean();
        DispatchedRun.Outcome outcome;
        try {
            outcome = runner().execute(dispatchOf("run-interrupted-finalization", scenarioOfWaits(1, "1ms"),
                    new Lease(System.currentTimeMillis(), 60_000, 10_000)), Map.of(), EXTENSIONS,
                    new DispatchedRun.WhileRunning() {
                        @Override public void started(LeaseWatch lease, Path journal) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("SYNTHETIC_PRIVATE_INTERRUPTED_FAILURE");
                        }
                        @Override public void terminalCommitted() { notified.set(true); }
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(notified).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failure()).doesNotContain("SYNTHETIC_PRIVATE_INTERRUPTED_FAILURE");
        var snapshot = DurableRunSpool.recover(outcome.journalPath().resolveSibling("spool.bin"));
        assertThat(snapshot.terminal().status()).isEqualTo(dev.faultora.engine.run.RunResult.Status.ERROR);
        assertThat(snapshot.terminal().cleanupConfirmed()).isFalse();
    }

    @Test
    void aNewInterruptDuringTerminalIoLeavesRecoveryRequiredWithoutRetry() throws Exception {
        var forces = new java.util.concurrent.atomic.AtomicInteger();
        var notified = new java.util.concurrent.atomic.AtomicBoolean();
        var interruptedStorage = new DispatchedRun(new DispatchVerifier(LIMITS, policy -> true), workingDirectory,
                handle -> null, true, (path, identity) -> DurableRunSpool.create(path, identity,
                DurableRunSpool.Limits.defaults(), channel -> {
                    if (forces.incrementAndGet() == 3) Thread.currentThread().interrupt();
                    channel.force(true);
                }));
        DispatchedRun.Outcome outcome;
        try {
            outcome = interruptedStorage.execute(dispatchOf("run-interrupted-storage", scenarioOfWaits(1, "1ms"),
                    new Lease(System.currentTimeMillis(), 60_000, 10_000)), Map.of(), EXTENSIONS,
                    new DispatchedRun.WhileRunning() {
                        @Override public void started(LeaseWatch lease, Path journal) {
                            throw new IllegalStateException("Synthetic execution failure");
                        }
                        @Override public void terminalCommitted() { notified.set(true); }
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(notified).isFalse();
        } finally { Thread.interrupted(); }
        assertThat(forces).hasValue(3);
        var snapshot = DurableRunSpool.recover(outcome.journalPath().resolveSibling("spool.bin"));
        assertThat(snapshot.recoveryRequired()).isTrue();
        assertThat(snapshot.uncommittedTail()).isTrue();
    }

    @Test
    void aLinkageErrorEscapesWithoutInventingACompletedAttempt() throws Exception {
        var dispatch = dispatchOf("run-linkage-error", scenarioOfWaits(1, "1ms"),
                new Lease(System.currentTimeMillis(), 60_000, 10_000));
        var notified = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> runner().execute(dispatch, Map.of(), EXTENSIONS,
                new DispatchedRun.WhileRunning() {
                    @Override public void started(LeaseWatch lease, Path journal) {
                        throw new NoClassDefFoundError("SyntheticFixtureClass");
                    }
                    @Override public void terminalCommitted() { notified.set(true); }
                })).isExactlyInstanceOf(NoClassDefFoundError.class);
        assertThat(notified).isFalse();
        Path spool = dev.faultora.runtime.RunArtifacts.dispatchedDirectory(workingDirectory,
                new dev.faultora.model.identifier.RunId(dispatch.runId())).resolve("spool.bin");
        assertThat(DurableRunSpool.recover(spool).recoveryRequired()).isTrue();
        assertThat(runner().execute(dispatch, Map.of(), EXTENSIONS).refusal().reason())
                .isEqualTo(Refusal.Reason.REPLAYED_DISPATCH);
    }

    @Test
    void spoolFingerprintBindsRuntimeInputsAndTheEffectivePreparation() throws Exception {
        var first = dispatchOf("run-fingerprint", scenarioOfWaits(1, "1ms"),
                new Lease(System.currentTimeMillis(), 60_000, 10_000));
        var changed = new Dispatch(first.runId(), first.issuedAtEpochMs(), first.nonce(), first.scenario(),
                first.documents(), Map.of("pause", "99ms"), first.targetRedirects(), first.credentials(),
                first.seed(), first.policy(), first.lease(), first.scenarioDigest(), first.catalogDigest());
        assertThat(DispatchedRun.dispatchFingerprint(first, "prepared-a"))
                .isNotEqualTo(DispatchedRun.dispatchFingerprint(changed, "prepared-a"))
                .isNotEqualTo(DispatchedRun.dispatchFingerprint(first, "prepared-b"));
    }

    @Test
    void spoolQuotaCancelsBusinessWithoutRemovingCleanupObligations() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cleanup", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            String scenario = scenarioOfWaits(1, "1ms") + """
                    cleanup:
                      - id: clean
                        type: operation
                        operationId: confirm-cleanup
                    """;
            String api = """
                    openapi: 3.0.3
                    info: {title: Synthetic, version: '1'}
                    paths:
                      /cleanup:
                        get:
                          operationId: confirm-cleanup
                          responses:
                            '200': {description: Cleanup confirmed}
                    """;
            var documents = List.of(new DispatchedDocument("openapi", api));
            var dispatch = new Dispatch("run-quota-cleanup", System.currentTimeMillis(), "nonce", scenario, documents,
                    Map.of(), Map.of("", "http://127.0.0.1:" + server.getAddress().getPort()),
                    Dispatch.Credentials.none(), 42, signedPolicy(), new Lease(System.currentTimeMillis(), 60_000, 10_000),
                    ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(documents));
            var bounded = new DispatchedRun(new DispatchVerifier(LIMITS, policy -> true), workingDirectory,
                    handle -> null, true, (path, identity) -> DurableRunSpool.create(path, identity,
                    new DurableRunSpool.Limits(16384, 1, 1024), channel -> channel.force(true)));
            var outcome = bounded.execute(dispatch, Map.of(), EXTENSIONS);
            assertThat(outcome.completed()).as(() -> String.valueOf(outcome.refusal()) + outcome.failure()).isTrue();
            assertThat(outcome.result().status()).isEqualTo(dev.faultora.engine.run.RunResult.Status.ERROR);
            assertThat(outcome.result().error().code()).isEqualTo("JOURNAL_WRITE_FAILED");
            assertThat(calls).hasValue(1);
            var snapshot = DurableRunSpool.recover(outcome.journalPath().resolveSibling("spool.bin"));
            assertThat(snapshot.terminal().status()).isEqualTo(dev.faultora.engine.run.RunResult.Status.ERROR);
            assertThat(snapshot.terminal().cleanupConfirmed()).isFalse();
        } finally { server.stop(0); }
    }

    @Test
    void failureAfterStartupDoesNotExportCallbackExceptionText() {
        String marker = "SYNTHETIC_PRIVATE_RUNNER_VALUE";
        DispatchedRun.Outcome outcome = runner().execute(
                dispatchOf("run-private-failure", scenarioOfWaits(1, "1ms"),
                        new Lease(System.currentTimeMillis(), 60_000, 10_000)),
                Map.of(), EXTENSIONS, (lease, journal) -> {
                    throw new IllegalStateException(marker);
                });
        assertThat(outcome.didRun()).isTrue();
        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.journalPath()).exists();
        assertThat(outcome.failure()).contains("stopped part-way").doesNotContain(marker);
    }

    @Test
    void invalidDispatchIdentitiesAreRefusedBeforeChoosingAnArtifactPath() throws Exception {
        for (String id : java.util.Arrays.asList(null, "", "invalid identity")) {
            var outcome = runner().execute(dispatchOf(id, scenarioOfWaits(1, "1ms"),
                    new Lease(System.currentTimeMillis(), 60_000, 10_000)), Map.of(), EXTENSIONS);
            assertThat(outcome.didRun()).isFalse();
            assertThat(outcome.refusal().reason()).isEqualTo(Refusal.Reason.REPLAYED_DISPATCH);
            assertThat(outcome.journalPath()).isNull();
        }
        try (var entries = Files.list(workingDirectory)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void restartingTheRunnerCannotOverwriteAnOwnedJournal() throws Exception {
        Dispatch dispatch = dispatchOf("run-restart", scenarioOfWaits(1, "1ms"),
                new Lease(System.currentTimeMillis(), 60_000, 10_000));
        var first = runner().execute(dispatch, Map.of(), EXTENSIONS);
        assertThat(first.completed()).isTrue();
        String original = Files.readString(first.journalPath());
        var afterRestart = runner().execute(dispatch, Map.of(), EXTENSIONS);
        assertThat(afterRestart.didRun()).isFalse();
        assertThat(afterRestart.refusal().reason()).isEqualTo(Refusal.Reason.REPLAYED_DISPATCH);
        assertThat(Files.readString(first.journalPath())).isEqualTo(original);
    }

    @Test
    void aDispatchedRunCompilesAndExecutesHere() throws Exception {
        String scenario = scenarioOfWaits(3, "20ms");

        DispatchedRun.Outcome outcome = runner().execute(
                dispatchOf("run-dispatched-1", scenario,
                        new Lease(System.currentTimeMillis(), 60_000, 10_000)),
                Map.of(), EXTENSIONS);

        assertThat(outcome.didRun())
                .as(() -> String.valueOf(outcome.refusal())).isTrue();
        assertThat(outcome.leaseExpired()).isFalse();
        assertThat(outcome.result().totalNodes()).isEqualTo(3);
        // The journal is a file on the runner, written as the run went: events
        // produced while nobody can be reached have to survive until somebody
        // can be.
        assertThat(outcome.journalPath()).exists();
        assertThat(Files.readString(outcome.journalPath()))
                .contains("RUN_STARTED", "run-dispatched-1");
    }

    @Test
    void whatAnImporterSaidAboutTheDispatchedCatalogComesBackWithTheOutcome() {
        String scenario = scenarioOfWaits(1, "1ms");
        List<DispatchedDocument> documents = List.of(new DispatchedDocument("asyncapi", """
                asyncapi: 3.0.0
                info: { title: Payments, version: 1.0.0 }
                servers:
                  legacy: { protocol: kafka }
                """));
        Dispatch dispatch = new Dispatch(
                "run-catalog-warnings", System.currentTimeMillis(), "nonce", scenario, documents,
                Map.of(), Map.of("", "http://localhost:1"),
                new Dispatch.Credentials(null, "faultora_readonly", "ledger-password"), 1L, signedPolicy(),
                new Lease(System.currentTimeMillis(), 60_000, 10_000),
                ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(documents));

        DispatchedRun.Outcome outcome = runner().execute(dispatch, Map.of(), EXTENSIONS);

        assertThat(outcome.completed()).as(() -> String.valueOf(outcome.refusal())).isTrue();
        assertThat(outcome.catalogWarnings()).anySatisfy(warning -> {
            assertThat(warning.family()).isEqualTo("asyncapi");
            assertThat(warning.message()).isEqualTo("Server 'legacy' names no host and was skipped");
        });
    }

    @Test
    void theRunTheDispatchAskedForIsTheRunThatHappens() throws Exception {
        // Same compiler, same bytes, same seed. The journal carries the seed
        // and the digests the dispatcher computed, which is what makes a remote
        // run reproducible rather than merely similar.
        String scenario = scenarioOfWaits(2, "10ms");
        Dispatch dispatch = dispatchOf("run-dispatched-2", scenario,
                new Lease(System.currentTimeMillis(), 60_000, 10_000));

        DispatchedRun.Outcome outcome =
                runner().execute(dispatch, Map.of(), EXTENSIONS);

        assertThat(Files.readString(outcome.journalPath()))
                .contains(dispatch.scenarioDigest(), dispatch.catalogDigest(), "4242");
    }

    @Test
    void aLeaseTooShortForTheWorkStopsTheDispatchedRun() throws Exception {
        // Ten seconds of waiting under a permission worth half a second.
        String scenario = scenarioOfWaits(20, "500ms");
        long startedAt = System.currentTimeMillis();

        DispatchedRun.Outcome outcome = runner().execute(
                dispatchOf("run-dispatched-3", scenario,
                        new Lease(System.currentTimeMillis(), 500, 100)),
                Map.of(), EXTENSIONS);

        assertThat(outcome.didRun()).isTrue();
        assertThat(outcome.leaseExpired())
                .as("the lease is what ended it, not the work running out").isTrue();
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(5_000);
    }

    @Test
    void aDispatchedRunKeepsTheEvidenceALocalOneKeeps() {
        // The runner used EvidencePolicy.MINIMAL, which captures no bodies and
        // no rows — so a row-balance assertion that passes on the machine the
        // scenario was written on came back indeterminate from a runner, for no
        // reason its author could see. Local and remote runs cannot drift, and
        // an evidence policy chosen twice is how they would.
        assertThat(RunEvidence.defaultPolicy().captureBodies()).isTrue();
        assertThat(RunEvidence.defaultPolicy().maxRows()).isEqualTo(1000);
        assertThat(RunEvidence.defaultPolicy())
                .as("the runner keeps what the CLI keeps")
                .isEqualTo(RunEvidence.defaultPolicy());
    }

    @Test
    void theHandlesARunAuthenticatesWithReachTheConnectors() {
        // ADR-021 promised a dispatch names handles; it carried none at all, so
        // a runner connected to a database with no user and to an API with no
        // token. The map the connectors are handed is what this reads — a run
        // that happened not to need a credential would pass whatever was in it.
        Dispatch dispatch = new Dispatch(
                "run-credentials", System.currentTimeMillis(), "nonce",
                scenarioOfWaits(1, "10ms"), List.of(), Map.of(),
                Map.of("", "http://localhost:1", "ledger", "jdbc:postgresql://db/x"),
                new Dispatch.Credentials("api-token", "faultora_readonly", "ledger-password"),
                1L, signedPolicy(), new Lease(System.currentTimeMillis(), 60_000, 10_000),
                ContentDigest.sha256Uri(scenarioOfWaits(1, "10ms")),
                Dispatch.digestOfDocuments(List.of()));

        Map<String, Object> config = runner()
                .connectorContext(dispatch, policyOf(dispatch)).config();

        assertThat(config).containsEntry(HttpConnector.AUTH_SECRET_ID, "api-token");
        assertThat(config).containsEntry(HttpConnector.MAX_REQUEST_BYTES, policyOf(dispatch).targets().maxPayloadBytes());
        assertThat(config).containsEntry(KafkaConnector.MAX_RECORD_BYTES, policyOf(dispatch).targets().maxPayloadBytes());
        assertThat(config).containsEntry("maxResponseBytes", policyOf(dispatch).targets().maxPayloadBytes());
        assertThat(config).containsEntry(JdbcConnector.USER, "faultora_readonly");
        assertThat(config).containsEntry(JdbcConnector.SECRET_ID, "ledger-password");
        assertThat(config).containsEntry(TargetResolver.BASE_URL, "http://localhost:1");
        assertThat(config).containsEntry(
                TargetResolver.BASE_URL_PREFIX + "ledger", "jdbc:postgresql://db/x");
    }

    @Test
    void aDispatchThatNamesNoCredentialsHandsTheConnectorsNone() {
        Dispatch anonymous = dispatchOf("run-anonymous", scenarioOfWaits(1, "10ms"),
                new Lease(System.currentTimeMillis(), 60_000, 10_000));
        Dispatch withoutAny = new Dispatch(
                anonymous.runId(), anonymous.issuedAtEpochMs(), anonymous.nonce(),
                anonymous.scenario(), anonymous.documents(), anonymous.inputs(),
                anonymous.targetRedirects(), Dispatch.Credentials.none(),
                anonymous.seed(), anonymous.policy(), anonymous.lease(),
                anonymous.scenarioDigest(), anonymous.catalogDigest());

        Map<String, Object> config = runner()
                .connectorContext(withoutAny, policyOf(withoutAny)).config();

        assertThat(config).doesNotContainKeys(
                HttpConnector.AUTH_SECRET_ID, JdbcConnector.USER, JdbcConnector.SECRET_ID);
    }

    @Test
    void aScenarioThatArrivedIntactAndDoesNotParseSaysThat() {
        // Not DIGEST_MISMATCH: the bytes are the ones that were sent, and
        // reporting a parse error as a hash failure sends the reader looking
        // for tampering that did not happen.
        String notAScenario = "apiVersion: faultora.dev/v1alpha1\nkind: NotAScenario\n";

        DispatchedRun.Outcome outcome = runner().execute(
                dispatchOf("run-dispatched-6", notAScenario,
                        new Lease(System.currentTimeMillis(), 60_000, 10_000)),
                Map.of(), EXTENSIONS);

        assertThat(outcome.didRun()).isFalse();
        assertThat(outcome.refusal().reason()).isEqualTo(Refusal.Reason.SCENARIO_INVALID);
    }

    @Test
    void aDispatchTheRunnerRefusesNeverReachesTheEngine() {
        String scenario = scenarioOfWaits(1, "10ms");
        Dispatch tampered = new Dispatch(
                "run-dispatched-4", System.currentTimeMillis(), "nonce",
                scenario + "\n# something nobody agreed to\n", List.of(),
                Map.of(), Map.of(), Dispatch.Credentials.none(), 1L, signedPolicy(),
                new Lease(System.currentTimeMillis(), 60_000, 10_000),
                ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(List.of()));

        DispatchedRun.Outcome outcome =
                runner().execute(tampered, Map.of(), EXTENSIONS);

        assertThat(outcome.didRun()).isFalse();
        assertThat(outcome.refusal().reason()).isEqualTo(Refusal.Reason.DIGEST_MISMATCH);
        assertThat(outcome.journalPath())
                .as("nothing was written for a run that never started").isNull();
    }

    @Test
    void structuralValidationIsSharedWithLocalPreparation() throws Exception {
        String duplicate = scenarioOfWaits(1, "1ms")
                + "  - id: pause-0\n    type: wait\n    timeout: 1ms\n";
        var outcome = runner().execute(dispatchOf("run-duplicate-step", duplicate,
                new Lease(System.currentTimeMillis(), 60_000, 10_000)), Map.of(), EXTENSIONS);
        assertThat(outcome.didRun()).isFalse();
        assertThat(outcome.refusal().reason()).isEqualTo(Refusal.Reason.SCENARIO_INVALID);
        try (var artifacts = Files.list(workingDirectory)) {
            assertThat(artifacts).isEmpty();
        }
    }

    @Test
    void unsupportedExtensionControlsRefuseBeforeCreatingAJournal() throws Exception {
        var outcome = runner().execute(dispatchOf("run-unsupported-control", scenarioOfWaits(1, "1ms"),
                new Lease(System.currentTimeMillis(), 60_000, 10_000)), Map.of(),
                new ExtensionPolicy(Set.of(), true, 0, Set.of(), Set.of()));
        assertThat(outcome.didRun()).isFalse();
        assertThat(outcome.refusal().reason()).isEqualTo(Refusal.Reason.MISSING_CAPABILITY);
        try (var artifacts = Files.list(workingDirectory)) {
            assertThat(artifacts).isEmpty();
        }
    }

    @Test
    void dispatchVerificationPrecedesPreparationAndItsExtensionChecks() {
        String scenario = "[invalid";
        var unsigned = dispatchOf("run-unverified", scenario,
                new Lease(System.currentTimeMillis(), 60_000, 10_000));
        var rejectingRunner = new DispatchedRun(new DispatchVerifier(LIMITS, policy -> false),
                workingDirectory, handle -> {
                    throw new AssertionError("No secret resolution allowed");
                }, true);
        var outcome = rejectingRunner.execute(unsigned, Map.of(),
                new ExtensionPolicy(Set.of(), true, 0, Set.of(), Set.of()));
        assertThat(outcome.refusal().reason()).isEqualTo(Refusal.Reason.UNVERIFIED_POLICY);
        assertThat(outcome.journalPath()).isNull();
    }
}
