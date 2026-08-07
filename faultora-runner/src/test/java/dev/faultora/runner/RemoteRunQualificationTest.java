package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.Session;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.testkit.QualificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-04: a run that outlives its first lease, and one that outlives its
 * dispatcher.
 * <p>
 * The two halves of the exit gate meet here. A run longer than the permission
 * it was granted keeps going only because something keeps asking for more —
 * and when there is nobody left to ask, the same mechanism stops it. Nothing
 * had to detect the disconnection: not renewing <em>is</em> the detection.
 * <p>
 * What the earlier transport tests showed was delivery surviving a
 * disconnection. This is the harder half: connectivity cut <b>while the run is
 * executing</b>, which until the agent existed could not have been noticed at
 * all, because nothing depended on the far side during a run.
 */
class RemoteRunQualificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final LocalLimits LIMITS = new LocalLimits(
            Set.of(), Set.of(SafetyClassification.READ_ONLY), Set.of(),
            Set.of(), 4, 600_000, 1000, 1_048_576);

    private static final ExtensionPolicy EXTENSIONS =
            new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());

    @TempDir
    Path directory;

    private record Pair(TlsMaterial runner, TlsMaterial dispatcher) {
    }

    private Pair trustingEachOther() throws Exception {
        Certificates.Identity runner = Certificates.issue(directory, "runner", 1);
        Certificates.Identity dispatcher = Certificates.issue(directory, "dispatcher", 1);
        return new Pair(
                new TlsMaterial(runner.keystore(),
                        Certificates.trusting(directory, "runner", dispatcher),
                        () -> Certificates.PASSWORD.toCharArray()),
                new TlsMaterial(dispatcher.keystore(),
                        Certificates.trusting(directory, "dispatcher", runner),
                        () -> Certificates.PASSWORD.toCharArray()));
    }

    /** Work that takes far longer than any one lease is worth. */
    private static String scenarioLasting(int waits, String each) {
        StringBuilder scenario = new StringBuilder("""
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata:
                  name: long-enough-to-need-renewing
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

    private static Dispatch dispatch(String runId, String scenario, long leaseTtlMs) {
        TargetPolicy policy = new TargetPolicy(
                Set.of(new TargetId("default")), Set.of(SafetyClassification.READ_ONLY),
                500, 2, 300_000, 1024, Set.of(), Set.of());
        try {
            return new Dispatch(
                    runId, System.currentTimeMillis(), "nonce", scenario, List.of(),
                    Map.of(), Map.of("", "http://localhost:1"),
                    Dispatch.Credentials.none(), 11L,
                    new SignedPolicy(MAPPER.writeValueAsString(policy), "trusted", "c2ln"),
                    new Lease(System.currentTimeMillis(), leaseTtlMs, leaseTtlMs / 4),
                    ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(List.of()));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private RunnerAgent agentFor(QualificationDispatcher dispatcher, Pair tls, Path work) {
        RunnerClient client = new RunnerClient(
                java.net.URI.create(dispatcher.address()), tls.runner(),
                "runner-1", "0.9.0-SNAPSHOT", Set.of("http"));
        DispatchedRun runs = new DispatchedRun(
                new DispatchVerifier(LIMITS, policy -> "trusted".equals(policy.keyId())),
                work, handleId -> null, true);
        return new RunnerAgent(client, runs, Map.of(), EXTENSIONS);
    }

    @Test
    void aRunLongerThanItsLeaseSurvivesOnlyBecauseSomethingKeepsAsking() throws Exception {
        // Three seconds of work under a lease worth one. It finishes, which it
        // can only do if the heartbeat is really renewing the permission.
        Pair tls = trustingEachOther();
        String scenario = scenarioLasting(30, "100ms");

        try (QualificationDispatcher dispatcher =
                     new QualificationDispatcher(tls.dispatcher().sslContext())) {
            dispatcher.grantLeasesOf(1_000);
            RunnerClient client = new RunnerClient(
                    java.net.URI.create(dispatcher.address()), tls.runner(),
                    "runner-1", "0.9.0-SNAPSHOT", Set.of("http"));
            Session session = client.register();
            dispatcher.offer(dispatch("run-qualified-1", scenario, 1_000));

            DispatchedRun.Outcome outcome = agentFor(dispatcher, tls, directory.resolve("w1"))
                    .takeOneDispatch(session.sessionId()).orElseThrow();

            assertThat(outcome.didRun()).isTrue();
            assertThat(outcome.leaseExpired())
                    .as("the run outlived a one-second lease by being granted more")
                    .isFalse();
            assertThat(outcome.result().status().toString()).isEqualTo("PASSED");
            assertThat(dispatcher.journalOf("run-qualified-1"))
                    .as("the journal arrived as the run went, not only at the end")
                    .isNotEmpty();
            assertThat(dispatcher.outcomeOf("run-qualified-1")).contains("PASSED");
        }
    }

    @Test
    void aDispatcherThatStopsAnsweringStopsTheRun() throws Exception {
        // The gate, and the test has to distinguish two ways of stopping. A
        // run under a two-second lease stops at two seconds whether or not
        // anybody disconnected, so a far side that went quiet immediately would
        // prove nothing. It goes quiet at four seconds instead: surviving that
        // long is only possible if the heartbeat was really renewing, and
        // stopping shortly after is only possible if it then stopped.
        Pair tls = trustingEachOther();
        String scenario = scenarioLasting(60, "500ms");
        Path work = directory.resolve("w2");

        try (QualificationDispatcher dispatcher =
                     new QualificationDispatcher(tls.dispatcher().sslContext())) {
            dispatcher.grantLeasesOf(2_000);
            RunnerClient client = new RunnerClient(
                    java.net.URI.create(dispatcher.address()), tls.runner(),
                    "runner-1", "0.9.0-SNAPSHOT", Set.of("http"));
            Session session = client.register();
            dispatcher.offer(dispatch("run-qualified-2", scenario, 2_000));

            new Thread(() -> {
                sleep(4_000);
                dispatcher.stopExtendingLeases();
            }, "the-far-side-goes-quiet").start();

            long startedAt = System.currentTimeMillis();
            DispatchedRun.Outcome outcome = agentFor(dispatcher, tls, work)
                    .takeOneDispatch(session.sessionId()).orElseThrow();
            long took = System.currentTimeMillis() - startedAt;

            assertThat(outcome.didRun()).isTrue();
            assertThat(outcome.leaseExpired())
                    .as("nothing told it to stop; its permission simply ran out")
                    .isTrue();
            assertThat(took)
                    .as("it was still going after four seconds, which a two-second "
                            + "lease allows only when something kept renewing it")
                    .isGreaterThan(4_000);
            assertThat(took)
                    .as("and it stopped within a lease of losing contact, with "
                            + "twenty-six seconds of work left to do")
                    .isLessThan(10_000);

            // Bounded, and not abandoned: the journal is on disk and what the
            // run learned before it stopped is in it.
            assertThat(Files.readAllLines(outcome.journalPath()))
                    .anyMatch(line -> line.contains("RUN_STARTED"));
        }
    }

    @Test
    void whatTheRunLearnedIsDeliveredWhenSomebodyCanBeReachedAgain() throws Exception {
        // Reconnection and result delivery, which M4-04 names beside bounded
        // autonomy: a run that stopped correctly and told nobody has failed the
        // gate from the other side.
        Pair tls = trustingEachOther();
        String scenario = scenarioLasting(40, "500ms");
        Path work = directory.resolve("w3");
        Path journalPath;

        try (QualificationDispatcher dispatcher =
                     new QualificationDispatcher(tls.dispatcher().sslContext())) {
            dispatcher.grantLeasesOf(1_500);
            RunnerClient client = new RunnerClient(
                    java.net.URI.create(dispatcher.address()), tls.runner(),
                    "runner-1", "0.9.0-SNAPSHOT", Set.of("http"));
            Session session = client.register();
            dispatcher.offer(dispatch("run-qualified-3", scenario, 1_500));

            new Thread(() -> {
                sleep(800);
                dispatcher.stopExtendingLeases();
            }, "the-far-side-goes-quiet").start();

            DispatchedRun.Outcome outcome = agentFor(dispatcher, tls, work)
                    .takeOneDispatch(session.sessionId()).orElseThrow();
            journalPath = outcome.journalPath();
            assertThat(outcome.leaseExpired()).isTrue();
        }

        List<String> journal = Files.readAllLines(journalPath);
        assertThat(journal).isNotEmpty();

        // Somebody comes back. The runner re-sends from the start of the
        // journal, and every line arrives once.
        try (QualificationDispatcher returned =
                     new QualificationDispatcher(tls.dispatcher().sslContext())) {
            RunnerClient client = new RunnerClient(
                    java.net.URI.create(returned.address()), tls.runner(),
                    "runner-1", "0.9.0-SNAPSHOT", Set.of("http"));
            client.register();
            client.sendProgress("run-qualified-3", 0, journal);

            assertThat(returned.journalOf("run-qualified-3"))
                    .as("what the run learned while nobody was listening")
                    .isEqualTo(journal);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
