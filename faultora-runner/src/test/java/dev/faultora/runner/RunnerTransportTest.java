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
import dev.faultora.runner.protocol.Registration;
import dev.faultora.runner.protocol.Session;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.testkit.Certificates;
import dev.faultora.testkit.QualificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run that crossed a wire.
 * <p>
 * Everything here goes over a real socket, under mutual TLS, between a runner
 * that dials out and a dispatcher that listens. Nothing listens inside the
 * private network — that is the release gate's first half, and it is a property
 * of the shape rather than of anything asserted.
 * <p>
 * The disconnection test is the gate's other half seen from the far side: what
 * a run learned while nobody could be reached has to arrive when somebody can.
 */
class RunnerTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final LocalLimits LIMITS = new LocalLimits(
            Set.of(), Set.of(SafetyClassification.READ_ONLY), Set.of(),
            Set.of(), 4, 600_000, 100, 1_048_576);

    private static final ExtensionPolicy EXTENSIONS =
            new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());

    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1alpha1
            kind: Scenario
            metadata:
              name: dispatched-over-a-wire
            execute:
              - id: pause
                type: wait
                timeout: 20ms
            """;

    @TempDir
    Path directory;

    /** A runner and a dispatcher that trust each other and nobody else. */
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

    private static Dispatch dispatch(String runId) {
        TargetPolicy policy = new TargetPolicy(
                Set.of(new TargetId("default")), Set.of(SafetyClassification.READ_ONLY),
                50, 2, 300_000, 1024, Set.of(), Set.of());
        try {
            return new Dispatch(
                    runId, System.currentTimeMillis(), "nonce", SCENARIO, List.of(),
                    Map.of(), Map.of("", "http://localhost:1"),
                    Dispatch.Credentials.none(), 7L,
                    new SignedPolicy(MAPPER.writeValueAsString(policy), "trusted", "c2ln"),
                    new Lease(System.currentTimeMillis(), 60_000, 10_000),
                    ContentDigest.sha256Uri(SCENARIO), Dispatch.digestOfDocuments(List.of()));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private RunnerClient clientFor(QualificationDispatcher dispatcher, TlsMaterial tls) {
        return new RunnerClient(URI.create(dispatcher.address()), tls,
                "runner-1", "0.9.0-SNAPSHOT", Set.of("http", "kafka", "jdbc"));
    }

    private DispatchedRun runnerWriting(Path workingDirectory) {
        return new DispatchedRun(
                new DispatchVerifier(LIMITS, policy -> "trusted".equals(policy.keyId())),
                workingDirectory, handleId -> null, true);
    }

    @Test
    void aRunnerDialsOutRegistersAndRunsWhatItIsGiven() throws Exception {
        Pair tls = trustingEachOther();

        try (QualificationDispatcher dispatcher =
                     new QualificationDispatcher(tls.dispatcher().sslContext())) {
            RunnerClient client = clientFor(dispatcher, tls.runner());

            Session session = client.register();
            assertThat(session.isAccepted()).isTrue();
            assertThat(dispatcher.registrations()).singleElement().satisfies(registration ->
                    assertThat(registration.capabilities()).contains("jdbc"));

            // Nothing to do yet, which is an ordinary answer rather than an
            // error: the runner asks again.
            assertThat(client.pollForWork(session.sessionId())).isEmpty();

            dispatcher.offer(dispatch("run-wire-1"));
            Optional<Dispatch> work = client.pollForWork(session.sessionId());
            assertThat(work).isPresent();

            DispatchedRun.Outcome outcome = runnerWriting(directory.resolve("work"))
                    .execute(work.get(), Map.of(), EXTENSIONS);
            assertThat(outcome.didRun()).isTrue();

            long delivered = client.sendProgress("run-wire-1", 0,
                    Files.readAllLines(outcome.journalPath()));
            client.sendOutcome("run-wire-1",
                    "{\"status\":\"" + outcome.result().status() + "\"}");

            assertThat(delivered).isGreaterThan(0);
            assertThat(dispatcher.journalOf("run-wire-1"))
                    .as("the far side holds the run's journal, line for line")
                    .isEqualTo(Files.readAllLines(outcome.journalPath()));
            assertThat(dispatcher.outcomeOf("run-wire-1")).contains("PASSED");
        }
    }

    @Test
    void whatARunLearnedWhileNobodyCouldBeReachedArrivesAfterwards() throws Exception {
        // The gate from the far side: a runner that stopped cleanly and lost
        // its findings has failed just as surely as one that ran too long.
        Pair tls = trustingEachOther();
        Path workingDirectory = directory.resolve("work-2");

        DispatchedRun.Outcome outcome;
        List<String> journal;
        try (QualificationDispatcher dispatcher =
                     new QualificationDispatcher(tls.dispatcher().sslContext())) {
            RunnerClient client = clientFor(dispatcher, tls.runner());
            Session session = client.register();
            dispatcher.offer(dispatch("run-wire-2"));
            Dispatch work = client.pollForWork(session.sessionId()).orElseThrow();

            outcome = runnerWriting(workingDirectory).execute(work, Map.of(), EXTENSIONS);
            journal = Files.readAllLines(outcome.journalPath());

            // Half the journal reaches the far side, and then the far side
            // disappears mid-delivery.
            long acknowledged = client.sendProgress(
                    "run-wire-2", 0, journal.subList(0, journal.size() / 2));
            assertThat(acknowledged).isEqualTo(journal.size() / 2);
        }

        // Nothing is lost while the far side is gone: the journal is a file.
        assertThat(Files.readAllLines(outcome.journalPath())).isEqualTo(journal);

        // It comes back — a new listener, and the runner resumes from where the
        // last acknowledgement left off.
        try (QualificationDispatcher returned =
                     new QualificationDispatcher(tls.dispatcher().sslContext())) {
            RunnerClient client = clientFor(returned, tls.runner());
            client.register();

            long half = journal.size() / 2;
            // Deliberately re-sending what was already acknowledged: delivery is
            // at-least-once, and the position is what makes that harmless.
            client.sendProgress("run-wire-2", 0, journal.subList(0, (int) half));
            client.sendProgress("run-wire-2", half,
                    journal.subList((int) half, journal.size()));

            assertThat(returned.journalOf("run-wire-2"))
                    .as("every line, once, in order — after a re-send that overlapped")
                    .isEqualTo(journal);
        }
    }

    @Test
    void aRunnerSpeakingNoCommonVersionIsToldSoRatherThanLeftHanging() throws Exception {
        // The refusal has to survive the wire as a refusal. A runner inside a
        // private network that got a dropped connection instead would have
        // nothing to act on; what it gets is a status and a named reason.
        Pair tls = trustingEachOther();

        try (QualificationDispatcher dispatcher =
                     new QualificationDispatcher(tls.dispatcher().sslContext())) {
            Registration fromTheFuture = new Registration(
                    "runner-99", "99.0.0", List.of("99", "100"), Set.of());

            HttpResponse<String> response = HttpClient.newBuilder()
                    .sslContext(tls.runner().sslContext()).build()
                    .send(HttpRequest.newBuilder(
                                    URI.create(dispatcher.address() + "/runner/register"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    MAPPER.writeValueAsString(fromTheFuture)))
                            .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(409);
            Session refused = MAPPER.readValue(response.body(), Session.class);
            assertThat(refused.isAccepted()).isFalse();
            assertThat(refused.refusal().reason())
                    .isEqualTo(Refusal.Reason.UNSUPPORTED_PROTOCOL_VERSION);
            assertThat(refused.refusal().describe()).contains("runner-99", "99");
        }
    }

    @Test
    void aBatchThatWouldLeaveAHoleIsRefusedRatherThanClosedUp() throws Exception {
        // Re-sending is safe and skipping is not. A journal quietly missing its
        // middle reads as a complete account of a run that did something else,
        // so the far side says no and the runner learns it did.
        Pair tls = trustingEachOther();

        try (QualificationDispatcher dispatcher =
                     new QualificationDispatcher(tls.dispatcher().sslContext())) {
            RunnerClient client = clientFor(dispatcher, tls.runner());
            client.register();

            client.sendProgress("run-wire-3", 0, List.of("{\"one\":1}"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    client.sendProgress("run-wire-3", 5, List.of("{\"six\":6}")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("would be lost");
            assertThat(dispatcher.journalOf("run-wire-3")).hasSize(1);
        }
    }

    @Test
    void aRunnerNobodyTrustsCannotEvenRegister() throws Exception {
        Pair tls = trustingEachOther();
        Certificates.Identity stranger = Certificates.issue(directory, "stranger", 1);
        Certificates.Identity dispatcherOnly =
                Certificates.issue(directory, "dispatcher-only", 1);
        TlsMaterial strangerTls = new TlsMaterial(stranger.keystore(),
                Certificates.trusting(directory, "stranger", dispatcherOnly),
                () -> Certificates.PASSWORD.toCharArray());

        try (QualificationDispatcher dispatcher =
                     new QualificationDispatcher(tls.dispatcher().sslContext())) {
            RunnerClient uninvited = clientFor(dispatcher, strangerTls);

            org.assertj.core.api.Assertions.assertThatThrownBy(uninvited::register)
                    .as("the conversation does not begin at all")
                    .isInstanceOf(IOException.class);
            assertThat(dispatcher.registrations()).isEmpty();
        }
    }
}
