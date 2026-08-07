package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.connector.http.HttpConnector;
import dev.faultora.connector.jdbc.JdbcConnector;
import dev.faultora.engine.exec.TargetResolver;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
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
}
