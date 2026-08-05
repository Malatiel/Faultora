package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.Refusal;
import dev.faultora.runner.protocol.SignedPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
            Set.of("http-latency"), 4, 600_000, 100, 1_048_576);

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

    private static SignedPolicy signedPolicy() {
        TargetPolicy policy = new TargetPolicy(
                Set.of(new TargetId("default")), Set.of(SafetyClassification.READ_ONLY),
                50, 2, 300_000, 1024, Set.of(), Set.of());
        try {
            return new SignedPolicy(MAPPER.writeValueAsString(policy), "trusted", "c2ln");
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Dispatch dispatchOf(String runId, String scenario, Lease lease) {
        return new Dispatch(
                runId, System.currentTimeMillis(), "nonce", scenario, List.of(),
                Map.of("pause", "20ms"), Map.of("", "http://localhost:1"), 4242L,
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
    void aDispatchTheRunnerRefusesNeverReachesTheEngine() {
        String scenario = scenarioOfWaits(1, "10ms");
        Dispatch tampered = new Dispatch(
                "run-dispatched-4", System.currentTimeMillis(), "nonce",
                scenario + "\n# something nobody agreed to\n", List.of(),
                Map.of(), Map.of(), 1L, signedPolicy(),
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
