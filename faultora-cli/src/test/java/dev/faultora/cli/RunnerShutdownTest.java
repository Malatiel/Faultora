package dev.faultora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.TlsMaterial;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.testkit.Certificates;
import dev.faultora.testkit.QualificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a signal does to a runner with work in flight.
 * <p>
 * This one starts a real process and really signals it, because the thing under
 * test is a shutdown hook and a hook cannot be exercised from inside the JVM
 * that would have to survive it. A hook that only set a flag would let the JVM
 * exit mid-run — the run stops, nobody is told, and the journal sits in a
 * container that is about to be discarded. ADR-020 names that failure: a run
 * that ended tidily and lost what it learned has failed from the other side.
 * <p>
 * Both halves are here. With a grace period the outcome arrives; with one too
 * short to matter it does not, which is what shows the waiting is doing the
 * work rather than the run happening to be quick.
 */
class RunnerShutdownTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Long enough that a signal always lands while the run is going. */
    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1alpha1
            kind: Scenario
            metadata:
              name: still-running-when-the-signal-comes
            execute:
              - id: pause
                type: wait
                timeout: 3s
            """;

    @TempDir
    Path directory;

    private QualificationDispatcher dispatcher;

    /**
     * Start {@code faultora runner} as its own process.
     * <p>
     * The same class path this test runs on, so it is the code just compiled
     * rather than something installed.
     */
    private Process startRunner(String runId, String grace) throws Exception {
        Certificates.Identity runner = Certificates.issue(directory, "runner", 1);
        Certificates.Identity control = Certificates.issue(directory, "control", 1);
        Certificates.Identity signing = Certificates.issue(directory, "policy-signing", 1);

        dispatcher = new QualificationDispatcher(new TlsMaterial(
                control.keystore(), Certificates.trusting(directory, "control", runner),
                () -> Certificates.PASSWORD.toCharArray()).sslContext());
        dispatcher.offer(dispatchOf(runId, signing));

        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "dev.faultora.cli.FaultoraCli", "runner",
                "--dispatcher", dispatcher.address(),
                "--keystore", runner.keystore().toString(),
                "--truststore", Certificates.trusting(directory, "runner", control).toString(),
                "--tls-secret-id", "runner-tls",
                "--policy-key", "control-2026=" + signing.certificate(),
                "--work-dir", directory.resolve("work").toString(),
                "--runner-id", "runner-under-signal",
                "--shutdown-grace", grace,
                "--allow-private"));

        ProcessBuilder process = new ProcessBuilder(command).redirectErrorStream(true);
        process.environment().put("FAULTORA_SECRET_RUNNER_TLS", Certificates.PASSWORD);
        return process.start();
    }

    private static Dispatch dispatchOf(String runId, Certificates.Identity signing)
            throws Exception {
        String policy = MAPPER.writeValueAsString(EffectivePolicy.of(new TargetPolicy(
                Set.of(), Set.of(SafetyClassification.READ_ONLY),
                100, 2, 60_000, 1024, Set.of(), Set.of())));
        return new Dispatch(
                runId, System.currentTimeMillis(), runId + "-nonce", SCENARIO, List.of(),
                Map.of(), Map.of("", "http://localhost:1"), Dispatch.Credentials.none(),
                3L,
                new SignedPolicy(policy, "control-2026",
                        Certificates.sign(signing, "RSA", policy)),
                new Lease(System.currentTimeMillis(), 60_000, 10_000),
                ContentDigest.sha256Uri(SCENARIO), Dispatch.digestOfDocuments(List.of()));
    }

    /** Wait until the run is under way, so the signal lands in the middle of it. */
    private void awaitRunUnderWay(String runId) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (!dispatcher.journalOf(runId).isEmpty()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the runner never started the run");
    }

    @Test
    void aSignalLetsTheRunInFlightFinishAndReport() throws Exception {
        Process runner = startRunner("run-signalled", "30s");
        try {
            awaitRunUnderWay("run-signalled");

            runner.destroy();

            assertThat(runner.waitFor(60, TimeUnit.SECONDS))
                    .as("it stopped rather than ignoring the signal").isTrue();
            assertThat(dispatcher.outcomeOf("run-signalled"))
                    .as("the run finished and said so before the process went")
                    .contains("PASSED");
        } finally {
            runner.destroyForcibly();
            dispatcher.close();
        }
    }

    @Test
    void aGraceTooShortToMatterLosesWhatTheRunFound() throws Exception {
        // The half that shows the half above is measuring the waiting. Same
        // signal at the same moment, and the only difference is that nothing
        // waits — so the process goes while the run is still going, and what it
        // had learned never reaches anybody.
        Process runner = startRunner("run-cut-off", "1ms");
        try {
            awaitRunUnderWay("run-cut-off");

            runner.destroy();

            assertThat(runner.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.outcomeOf("run-cut-off"))
                    .as("nothing reported it, which is what the grace period prevents")
                    .isNull();
        } finally {
            runner.destroyForcibly();
            dispatcher.close();
        }
    }
}
