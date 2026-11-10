package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.DurableDelivery;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.Session;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.runtime.RunArtifacts;
import dev.faultora.testkit.Certificates;
import dev.faultora.testkit.QualificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            Set.of(), 4, 600_000, 1000, 1_048_576, null);

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
                    new SignedPolicy(
                            MAPPER.writeValueAsString(EffectivePolicy.of(policy)),
                            "trusted", "c2ln"),
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
    void diskFailureCannotSendAnUncommittedEventOrSuccessfulTerminal() throws Exception {
        Pair tls = trustingEachOther();
        for (int failedKind : List.of(2, 3)) {
            String run = "run-storage-" + failedKind;
            Path work = directory.resolve("disk-" + failedKind);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var start = new java.util.concurrent.atomic.AtomicLong(8);
            try (var dispatcher = new QualificationDispatcher(tls.dispatcher().sslContext())) {
                var client = new RunnerClient(java.net.URI.create(dispatcher.address()), tls.runner(),
                        "runner-storage", "0.11.3", Set.of("http"));
                Session session = client.register();
                dispatcher.offer(dispatch(run, scenarioLasting(1, "1ms"), 60_000));
                var runs = new DispatchedRun(new DispatchVerifier(LIMITS, policy -> true), work,
                        handle -> null, true, (path, identity) -> DurableRunSpool.create(path, identity,
                        DurableRunSpool.Limits.defaults(), channel -> {
                            if (calls.incrementAndGet() % 2 == 1) {
                                var kind = java.nio.ByteBuffer.allocate(1);
                                channel.read(kind, start.get() + 4);
                                if (kind.array()[0] == failedKind) throw new java.io.IOException("PRIVATE_DISK_MARKER");
                            } else { start.set(channel.position()); }
                            channel.force(true);
                        }));
                var outcome = new RunnerAgent(client, runs, Map.of(), EXTENSIONS)
                        .takeOneDispatch(session.sessionId()).orElseThrow();
                assertThat(outcome.completed()).isFalse();
                assertThat(outcome.failure()).doesNotContain("PRIVATE_DISK_MARKER");
                assertThat(dispatcher.outcomeOf(run)).isNull();
                var snapshot = DurableRunSpool.recover(outcome.journalPath().resolveSibling("spool.bin"));
                assertThat(snapshot.recoveryRequired()).isTrue();
                assertThat(dispatcher.journalOf(run)).hasSize((int) snapshot.events());
                if (failedKind == 2) assertThat(snapshot.events()).isZero();
                else assertThat(snapshot.events()).isPositive();
            }
        }
    }

    @Test
    void aBrokenRunIsReportedWhenItsSpoolCanCommitTheInfrastructureTerminal() throws Exception {
        Pair tls = trustingEachOther();
        Path work = directory.resolve("PRIVATE_MIRROR_MARKER");
        try (var dispatcher = new QualificationDispatcher(tls.dispatcher().sslContext())) {
            var client = new RunnerClient(java.net.URI.create(dispatcher.address()), tls.runner(),
                    "runner-broken", "0.11.3", Set.of("http"));
            Session session = client.register();
            String run = "run-broken-mirror";
            dispatcher.offer(dispatch(run, scenarioLasting(1, "1ms"), 60_000));
            var runs = new DispatchedRun(new DispatchVerifier(LIMITS, policy -> true), work,
                    handle -> null, true, (path, identity) -> {
                        Files.createDirectory(path.resolveSibling("events.ndjson"));
                        return DurableRunSpool.create(path, identity);
                    });
            var outcome = new RunnerAgent(client, runs, Map.of(), EXTENSIONS)
                    .takeOneDispatch(session.sessionId()).orElseThrow();
            assertThat(outcome.completed()).isFalse();
            assertThat(dispatcher.outcomeOf(run)).as("a broken mirror must not suppress its committed diagnosis")
                    .isNotNull().contains("\"broke\":true", "provider details withheld")
                    .doesNotContain("PRIVATE_MIRROR_MARKER", "PASSED");
            var snapshot = DurableRunSpool.recover(outcome.journalPath().resolveSibling("spool.bin"));
            assertThat(snapshot.terminal().status()).isEqualTo(dev.faultora.engine.run.RunResult.Status.ERROR);
            assertThat(snapshot.terminal().cleanupConfirmed()).isFalse();
            assertThat(snapshot.events()).isZero();
        }
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

    @Test
    void terminalAckLostAfterCommitIsReplayedAfterBothSidesRestart() throws Exception {
        Pair tls = trustingEachOther();
        Path receiver = directory.resolve("receiver");
        Path work = directory.resolve("runner");
        String runId = "run-durable-mutation";
        Path run = RunArtifacts.dispatchedDirectory(work, new RunId(runId));
        var mutations = new AtomicInteger();
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/change", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, -1);
            } else {
                mutations.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        target.start();
        try {
            Dispatch offered = mutatingDispatch(runId, target.getAddress().getPort());
            int port;
            DurableDelivery.Terminal terminal;
            List<String> journal;
            try (var dispatcher = new QualificationDispatcher(tls.dispatcher().sslContext(), receiver, 0)) {
                port = dispatcher.port();
                RunnerClient client = durableClient(dispatcher, tls, "runner-1");
                Session session = client.register();
                dispatcher.offer(offered);
                dispatcher.loseNextDurableTerminalAck();
                assertThatThrownBy(() -> durableAgent(client, work).takeOneDispatch(session.sessionId()))
                        .isInstanceOf(IOException.class);
                terminal = dispatcher.durableTerminalOf(runId);
                journal = dispatcher.durableJournalOf(runId);
                assertThat(terminal.status()).isEqualTo(RunResult.Status.PASSED.name());
                assertThat(terminal.cleanupConfirmed()).isTrue();
                assertThat(journal).isNotEmpty().hasSize((int) terminal.finalPosition());
                assertThat(checkpoint(run).path("nextPosition").longValue()).isEqualTo(terminal.finalPosition());
                assertThat(checkpoint(run).path("terminalDigest").isNull()).isTrue();
                assertThat(mutations).hasValue(1);
            }

            // A mirror that cannot be read must not suppress replay of committed spool records.
            Files.move(run.resolve("events.ndjson"), run.resolve("withheld-mirror.ndjson"));
            try (var dispatcher = new QualificationDispatcher(tls.dispatcher().sslContext(), receiver, port)) {
                RunnerClient client = durableClient(dispatcher, tls, "runner-1");
                Session session = client.register();
                RunnerAgent restarted = durableAgent(client, work);
                assertThat(restarted.takeOneDispatch(session.sessionId())).isEmpty();
                assertThat(checkpoint(run).path("terminalDigest").textValue()).isEqualTo(terminal.terminalDigest());
                assertThat(dispatcher.durableTerminalOf(runId)).isEqualTo(terminal);
                assertThat(dispatcher.durableJournalOf(runId)).containsExactlyElementsOf(journal);

                dispatcher.offer(offered);
                assertThatThrownBy(() -> durableAgent(client, directory.resolve("empty-runner"))
                        .takeOneDispatch(session.sessionId())).hasMessage("DURABLE_DELIVERY_WORK_REFUSED");
                assertThat(dispatcher.durableTerminalOf(runId)).isEqualTo(terminal);
                assertThat(mutations).hasValue(1);
            }
        } finally {
            target.stop(0);
        }
    }

    @Test
    void aDelayedAckKeepsOneFullBatchInFlightAndTheCheckpointUnadvanced() throws Exception {
        Pair tls = trustingEachOther();
        Path work = directory.resolve("runner");
        var runId = new RunId("run-bounded-delivery");
        var node = new NodeId("fixture-node");
        int count = DurableDelivery.MAX_BATCH_EVENTS * 3 + 1;
        try (var dispatcher = new QualificationDispatcher(tls.dispatcher().sslContext(),
                directory.resolve("receiver"), 0)) {
            var client = durableClient(dispatcher, tls, "runner-1");
            Session session = client.register();
            Dispatch offered = dispatch(runId.value(), scenarioLasting(1, "1ms"), 60_000);
            dispatcher.offer(offered);
            assertThat(client.pollForWork(session.sessionId())).contains(offered);

            var artifacts = RunArtifacts.claimDispatched(work, runId);
            var identity = new DurableRunSpool.Identity(DurableRunSpool.runnerIdentity(work), runId.value(),
                    "123e4567-e89b-12d3-a456-426614174001", ContentDigest.sha256Uri("bounded-fixture"), 123456789L);
            Path spoolPath = artifacts.journalPath().resolveSibling("spool.bin");
            try (var spool = DurableRunSpool.create(spoolPath, identity)) {
                new DurableRunDelivery(client, work).opened(identity, spoolPath);
                for (int index = 0; index < count; index++) {
                    spool.appendEvent(new RunEvent.NodeSkipped("NODE_SKIPPED", index + 1L, runId, node, "fixture"));
                }
                spool.commitTerminal(RunResult.Status.PASSED, true, false);
            }

            var ackEntered = new CountDownLatch(1);
            var releaseAck = new CountDownLatch(1);
            dispatcher.changeNextDurableProgressAck(ack -> {
                ackEntered.countDown();
                try {
                    if (!releaseAck.await(15, TimeUnit.SECONDS)) throw new AssertionError("Fixture ACK timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Fixture ACK interrupted", interrupted);
                }
                return ack;
            });

            try (var executor = Executors.newSingleThreadExecutor()) {
                var pending = executor.submit(() -> durableAgent(client, work).takeOneDispatch(session.sessionId()));
                try {
                    assertThat(ackEntered.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThat(dispatcher.durableJournalOf(runId.value())).hasSize(DurableDelivery.MAX_BATCH_EVENTS);
                    assertThat(dispatcher.durableTerminalOf(runId.value())).isNull();
                    assertThat(checkpoint(artifacts.directory()).path("nextPosition").longValue()).isZero();
                } finally {
                    releaseAck.countDown();
                }
                assertThat(pending.get(20, TimeUnit.SECONDS)).isEmpty();
            }
            assertThat(dispatcher.durableJournalOf(runId.value())).hasSize(count);
            var terminal = dispatcher.durableTerminalOf(runId.value());
            assertThat(terminal.finalPosition()).isEqualTo(count);
            assertThat(checkpoint(artifacts.directory()).path("terminalDigest").textValue())
                    .isEqualTo(terminal.terminalDigest());
        }
    }

    @Test
    void wrongAckCannotAdvanceCheckpointAndBrokenStorageStopsAdmission() throws Exception {
        Pair tls = trustingEachOther();
        Path work = directory.resolve("runner");
        String runId = "run-wrong-ack";
        Path run = RunArtifacts.dispatchedDirectory(work, new RunId(runId));
        try (var dispatcher = new QualificationDispatcher(tls.dispatcher().sslContext(),
                directory.resolve("receiver"), 0)) {
            var client = durableClient(dispatcher, tls, "runner-1");
            Session first = client.register();
            dispatcher.offer(dispatch(runId, scenarioLasting(1, "1ms"), 60_000));
            dispatcher.changeNextDurableProgressAck(ack -> {
                var identity = ack.attempt();
                var other = new DurableDelivery.Attempt(identity.runnerId(), identity.runId(),
                        "123e4567-e89b-12d3-a456-426614174000", identity.dispatchDigest());
                return new DurableDelivery.Ack(other, ack.nextPosition(), ack.terminalDigest());
            });
            RunnerAgent runner = durableAgent(client, work);
            assertThatThrownBy(() -> runner.takeOneDispatch(first.sessionId()))
                    .hasMessage("DURABLE_DELIVERY_ACK_MISMATCH");
            assertThat(checkpoint(run).path("nextPosition").longValue()).isZero();
            List<String> committed = dispatcher.durableJournalOf(runId);
            assertThat(committed).isNotEmpty();

            Session reconnected = client.register();
            assertThat(runner.takeOneDispatch(reconnected.sessionId())).isEmpty();
            assertThat(dispatcher.durableJournalOf(runId)).containsExactlyElementsOf(committed);
            assertThat(checkpoint(run).path("terminalDigest").textValue())
                    .isEqualTo(dispatcher.durableTerminalOf(runId).terminalDigest());

            var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) checkpoint(run);
            invalid.put("nextPosition", DurableDelivery.MAX_ATTEMPT_EVENTS);
            MAPPER.writeValue(run.resolve("delivery-ack.json").toFile(), invalid);
            var pending = dispatch("run-still-pending", scenarioLasting(1, "1ms"), 60_000);
            dispatcher.offer(pending);
            Session restart = client.register();
            assertThatThrownBy(() -> durableAgent(client, work).takeOneDispatch(restart.sessionId()))
                    .isInstanceOf(RunnerAgent.DeliveryStorageFailure.class)
                    .hasMessage("DURABLE_DELIVERY_STORAGE_UNAVAILABLE");
            assertThat(client.pollForWork(restart.sessionId())).contains(pending);

            Path newWork = directory.resolve("new-runner");
            var neverStarted = dispatch("run-storage-before-invocation", scenarioLasting(1, "1ms"), 60_000);
            Path newRun = RunArtifacts.dispatchedDirectory(newWork, new RunId(neverStarted.runId()));
            dispatcher.offer(neverStarted);
            assertThatThrownBy(() -> durableAgent(client, newWork).takeOneDispatch(restart.sessionId(), id -> {
                try {
                    Files.createDirectories(newRun.resolve("delivery-ack.json"));
                } catch (IOException failed) {
                    throw new java.io.UncheckedIOException(failed);
                }
            })).isInstanceOf(RunnerAgent.DeliveryStorageFailure.class);
            assertThat(newRun.resolve("events.ndjson")).doesNotExist();
            assertThat(DurableRunSpool.recover(newRun.resolve("spool.bin")).recoveryRequired()).isTrue();
        }
    }

    @Test
    void durableReceiverFencesOffersAndRejectsForeignOrCorruptState() throws Exception {
        Pair tls = trustingEachOther();
        String runId = "run-durable-fence";
        Path receiver = directory.resolve("receiver");
        Dispatch offered = dispatch(runId, scenarioLasting(1, "1ms"), 60_000);
        try (var dispatcher = new QualificationDispatcher(tls.dispatcher().sslContext(), receiver, 0)) {
            var legacy = new RunnerClient(URI.create(dispatcher.address()), tls.runner(),
                    "runner-legacy", "0.12.0", Set.of("http"));
            assertThat(legacy.register().isAccepted()).isFalse();

            var owner = durableClient(dispatcher, tls, "runner-1");
            Session session = owner.register();
            dispatcher.offer(offered);
            assertThat(owner.pollForWork(session.sessionId())).contains(offered);
            dispatcher.offer(offered);
            assertThatThrownBy(() -> owner.pollForWork(session.sessionId()))
                    .hasMessage("DURABLE_DELIVERY_WORK_REFUSED");

            var attempt = new DurableDelivery.Attempt("123e4567-e89b-12d3-a456-426614174000", runId,
                    "123e4567-e89b-12d3-a456-426614174001", ContentDigest.sha256Uri("dispatch"));
            String event = "{\"eventType\":\"RUN_STARTED\",\"runId\":\"" + runId + "\"}";
            var progress = new DurableDelivery.Progress(attempt, 0, List.of(event));
            var foreign = durableClient(dispatcher, tls, "runner-other");
            Session foreignSession = foreign.register();
            assertThatThrownBy(() -> foreign.acknowledgeProgress(foreignSession.sessionId(), progress))
                    .hasMessage("DURABLE_DELIVERY_REFUSED");
            assertThat(dispatcher.durableJournalOf(runId)).isEmpty();

            var terminal = DurableDelivery.Terminal.of(attempt, 1, "PASSED", true, false, "NONE");
            assertThatThrownBy(() -> owner.acknowledgeTerminal(session.sessionId(), terminal))
                    .hasMessage("DURABLE_DELIVERY_REFUSED");
            owner.acknowledgeProgress(session.sessionId(), progress);
            owner.acknowledgeTerminal(session.sessionId(), terminal);
            var conflict = DurableDelivery.Terminal.of(attempt, 1, "FAILED", true, false, "NONE");
            assertThatThrownBy(() -> owner.acknowledgeTerminal(session.sessionId(), conflict))
                    .hasMessage("DURABLE_DELIVERY_REFUSED");
            assertThat(dispatcher.durableTerminalOf(runId)).isEqualTo(terminal);

            Path file = receiver.resolve(ContentDigest.sha256Hex(runId) + ".json");
            String state = Files.readString(file);
            assertThat(state).contains("RUN_STARTED");
            Files.writeString(file, state.replace("RUN_STARTED", "RUN_STOPPED"));
            assertThatThrownBy(() -> owner.acknowledgeTerminal(session.sessionId(), terminal))
                    .hasMessage("DURABLE_DELIVERY_REFUSED");
            assertThatThrownBy(() -> dispatcher.durableJournalOf(runId))
                    .hasMessage("DURABLE_RECEIVER_STORAGE_CORRUPT");
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode checkpoint(Path run) throws IOException {
        return MAPPER.readTree(run.resolve("delivery-ack.json").toFile());
    }

    private static RunnerClient durableClient(QualificationDispatcher dispatcher, Pair tls, String name) {
        return new RunnerClient(URI.create(dispatcher.address()), tls.runner(), name, "0.12.0", Set.of("http"), true);
    }

    private static RunnerAgent durableAgent(RunnerClient client, Path work) {
        var limits = new LocalLimits(Set.of(), Set.of(SafetyClassification.MUTATING, SafetyClassification.READ_ONLY), Set.of(),
                Set.of(), 4, 600_000, 1000, 1_048_576, null);
        var runs = new DispatchedRun(new DispatchVerifier(limits, policy -> "trusted".equals(policy.keyId())),
                work, handle -> null, true);
        return new RunnerAgent(client, runs, Map.of(), EXTENSIONS);
    }

    private static Dispatch mutatingDispatch(String runId, int port) throws IOException {
        String scenario = """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata: {name: durable-mutation}
                execute:
                  - id: mutation
                    operationId: perform-mutation
                """;
        String api = """
                openapi: 3.0.3
                info: {title: Synthetic, version: '1'}
                paths:
                  /change:
                    post:
                      operationId: perform-mutation
                      responses:
                        '204': {description: Mutation performed}
                """;
        var policy = new TargetPolicy(Set.of(new TargetId("default")), Set.of(SafetyClassification.MUTATING),
                500, 2, 300_000, 1024, Set.of(), Set.of());
        var documents = List.of(new DispatchedDocument("openapi", api));
        long now = System.currentTimeMillis();
        return new Dispatch(runId, now, "nonce", scenario, documents, Map.of(),
                Map.of("", "http://127.0.0.1:" + port), Dispatch.Credentials.none(), 11L,
                new SignedPolicy(MAPPER.writeValueAsString(EffectivePolicy.of(policy)), "trusted", "c2ln"),
                new Lease(now, 60_000, 10_000), ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(documents));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
