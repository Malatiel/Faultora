package dev.faultora.engine;

import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.exec.JournalWriter;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.*;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioMetadata;
import dev.faultora.spi.context.*;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.result.ActiveFault;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class EngineIntegrityFailureTest {
    @TempDir Path directory;
    private static final String PRIVATE_MARKER = "SYNTHETIC_PASSWORD_URL_VALUE";
    private static final OperationDefinition READ = operation("read");
    private static final OperationDefinition CLEANUP = operation("cleanup");
    private final List<String> calls = new ArrayList<>();
    private List<RunEvent> events;
    private Faults faults;

    @ParameterizedTest
    @ValueSource(strings = {"first", "node", "terminal", "flush", "close"})
    void aMandatoryJournalFailureCannotReturnAPassAndRetainsSafePartialEvents(String mode) throws Exception {
        BrokenWriter output = new BrokenWriter(mode);
        RunResult result = run(output, List.of(node("first"), node("second"), cleanup()));
        assertThat(result.status()).isEqualTo(RunResult.Status.ERROR);
        assertThat(result.error().code()).isEqualTo("JOURNAL_WRITE_FAILED");
        assertThat(result.error().message()).doesNotContain(PRIVATE_MARKER);
        assertThat(result.error().metadata()).containsKey("journalOperation");
        assertThat(calls).contains("cleanup");
        if (mode.equals("first")) {
            assertThat(calls).containsExactly("cleanup");
        }
        if (mode.equals("node")) {
            assertThat(calls).containsExactly("read", "cleanup");
        }
        assertThat(events.getFirst()).isInstanceOf(RunEvent.RunStarted.class);
        assertThat(events.getLast()).isInstanceOf(RunEvent.RunFailed.class);
        assertThat(events.toString()).doesNotContain(PRIVATE_MARKER);
        assertThat(output.closed).isTrue();
    }

    @Test
    void aHealthyJournalStillReturnsTheOrdinaryResult() throws Exception {
        assertThat(run(new BrokenWriter("healthy"), List.of(node("read"), cleanup())).status())
                .isEqualTo(RunResult.Status.PASSED);
        assertThat(events.getLast()).isInstanceOf(RunEvent.RunCompleted.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-end", "fault-stop", "hard-expiry"})
    void confirmedRollbackFailureChangesTheTerminalOutcomeAndOtherObligationsContinue(String reason) throws Exception {
        faults = new Faults();
        faults.awaitWatchdog = reason.equals("hard-expiry");
        List<PlanNode> nodes = new ArrayList<>(List.of(fault("a", faults.awaitWatchdog ? 1 : 60_000),
                fault("b", faults.awaitWatchdog ? 1 : 60_000)));
        if (reason.equals("fault-stop")) {
            nodes.add(new PlanNode.FaultStopNode(new NodeId("stop-a"), new NodeId("a"), List.of(), SafetyClassification.READ_ONLY));
            nodes.add(new PlanNode.FaultStopNode(new NodeId("stop-b"), new NodeId("b"), List.of(), SafetyClassification.READ_ONLY));
        }
        nodes.add(node("work")); nodes.add(cleanup());
        RunResult result = run(new BrokenWriter("healthy"), nodes);
        assertThat(result.status()).isEqualTo(RunResult.Status.ERROR);
        assertThat(result.error().code()).isEqualTo("FAULT_ROLLBACK_FAILED");
        assertThat(result.error().metadata()).containsEntry("rollbackFailures", 1).containsEntry("unconfirmedRollbacks", 0);
        assertThat(faults.rolledBack.get()).isEqualTo(2);
        assertThat(calls).containsExactly("read", "cleanup");
        assertThat(events.toString()).contains(reason + "-rollback-failed").doesNotContain(PRIVATE_MARKER);
    }

    @Test
    void journalFailureKeepsRollbackFailureVisibleInItsSafeMetadata() throws Exception {
        faults = new Faults();
        RunResult result = run(new BrokenWriter("node"), List.of(fault("a", 60_000), node("work"), cleanup()));
        assertThat(result.error().code()).isEqualTo("JOURNAL_WRITE_FAILED");
        assertThat(result.error().metadata()).containsEntry("rollbackFailures", 1);
        assertThat(faults.rolledBack.get()).isEqualTo(1);
        assertThat(calls).contains("cleanup");
    }

    @Test
    void anInFlightWatchdogRollbackCannotBeReportedAsConfirmedSuccess() throws Exception {
        faults = new Faults();
        faults.awaitWatchdog = true;
        faults.block = new CountDownLatch(1);
        try {
            RunResult result = run(new BrokenWriter("healthy"), List.of(fault("a", 1), node("work"), cleanup()));
            assertThat(faults.entered.getCount()).as("The watchdog entered before the outcome was published").isZero();
            assertThat(result.status()).as(() -> result + " events: " + events).isEqualTo(RunResult.Status.ERROR);
            assertThat(result.error().code()).isEqualTo("FAULT_ROLLBACK_UNCONFIRMED");
            assertThat(result.error().metadata()).containsEntry("unconfirmedRollbacks", 1);
        } finally {
            faults.block.countDown();
            assertThat(faults.finished.await(1, TimeUnit.SECONDS)).as("The released fixture finished").isTrue();
        }
    }

    private RunResult run(BrokenWriter output, List<PlanNode> nodes) throws Exception {
        ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"),
                List.of(new TargetDefinition(new TargetId("test"), "Test", "http://127.0.0.1:1",
                        List.of(new ProtocolId("http")), List.of(), Map.of())),
                List.of(READ, CLEANUP), Map.of(), Map.of(), List.of());
        ExecutionPlan plan = ExecutionPlan.builder().runId(new RunId("synthetic-integrity"))
                .scenario(new ScenarioDocument("faultora.dev/v1", "Scenario",
                        new ScenarioMetadata("synthetic-integrity", null, Map.of(), Map.of()), Map.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), null))
                .catalog(catalog).targetPolicy(new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                        100, 1, 10_000, 1024, Set.of("synthetic-fault"), Set.of()))
                .scenarioDigest("synthetic-scenario").catalogDigest("synthetic-catalog").addNodes(nodes).build();
        Connector connector = new Connector() {
            public ProtocolId protocol() {
                return new ProtocolId("http");
            }
            public Set<String> capabilities() {
                return Set.of();
            }
            public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
                return () -> target;
            }
            public OperationResult execute(PreparedTarget target, OperationDefinition operation,
                                           Map<String, Object> inputs, ConnectorContext context) {
                calls.add(operation.id().value());
                if (operation.id().equals(READ.id()) && faults != null && faults.awaitWatchdog) {
                    try {
                        assertThat(faults.entered.await(1, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
                return OperationResult.success(200, Map.of(), null, 0, Map.of());
            }
            public void release(PreparedTarget target) { }
            public void close() { }
        };
        RunJournal journal = new RunJournal(directory.resolve("events.ndjson"), false, output);
        try {
            RunResult result = new LocalEngine(Map.of("http", connector), Map.of(),
                    faults == null ? Map.of() : Map.of("synthetic-fault", faults)).execute(plan, journal,
                    ExpressionContext.builder().build(),
                    new ConnectorContext(EvidencePolicy.MINIMAL, key -> null, 1000, 1000, 5000, Map.of()),
                    new java.util.concurrent.atomic.AtomicBoolean());
            result = JournalWriter.closeJournal(journal, result);
            events = journal.events();
            return result;
        } finally {
            journal.close();
        }
    }

    private static OperationDefinition operation(String id) {
        return new OperationDefinition(new OperationId(id), new ProtocolId("http"), new TargetId("test"),
                SafetyClassification.READ_ONLY, Map.of(), null, Map.of(), Map.of());
    }
    private static PlanNode node(String id) {
        return new PlanNode.OperationNode(new NodeId(id), READ.id(), READ, Map.of(), null,
                List.of(), SafetyClassification.READ_ONLY, 0, 0);
    }
    private static PlanNode cleanup() {
        return new PlanNode.CleanupNode(new NodeId("cleanup"), CLEANUP.id(), Map.of(), List.of(), SafetyClassification.READ_ONLY, 0, 0);
    }
    private static PlanNode fault(String id, long duration) {
        return new PlanNode.FaultStartNode(new NodeId(id), "synthetic-fault", "*", Map.of(), duration,
                List.of(), SafetyClassification.READ_ONLY);
    }

    private static class BrokenWriter extends Writer {
        final String mode;
        int writes;
        boolean closed;
        BrokenWriter(String mode) {
            this.mode = mode;
        }
        public void write(char[] value, int offset, int length) throws IOException {
            String text = new String(value, offset, length);
            if (mode.equals("first") && writes++ == 0 || mode.equals("node") && text.contains("NODE_COMPLETED")
                    || mode.equals("terminal") && text.contains("RUN_COMPLETED")) {
                        throw new IOException(PRIVATE_MARKER);
                    }
        }
        public void flush() throws IOException {
            if (mode.equals("flush")) {
                throw new IOException(PRIVATE_MARKER);
            }
        }
        public void close() throws IOException {
            closed = true;
            if (mode.equals("close")) {
                throw new IOException(PRIVATE_MARKER);
            }
        }
    }

    private static class Faults implements FaultProvider {
        final AtomicInteger injected = new AtomicInteger();
        final AtomicInteger rolledBack = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        boolean awaitWatchdog;
        CountDownLatch block;
        public Set<String> capabilities() {
            return Set.of("synthetic-fault");
        }
        public ActiveFault inject(String type, Map<String, Object> params, FaultContext context) {
            return new ActiveFault("synthetic-" + injected.incrementAndGet(), type, context.targetScope(),
                    Math.min(System.currentTimeMillis(), context.hardExpiryMs() - 1),
                    context.hardExpiryMs(), "synthetic rollback");
        }
        public void rollback(ActiveFault fault, FaultContext context) {
            rolledBack.incrementAndGet(); entered.countDown();
            try {
                if (block != null) {
                    boolean interrupted = false;
                    while (block.getCount() > 0) {
                        try {
                            block.await();
                        } catch (InterruptedException stopped) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                if (fault.handle().equals("synthetic-1")) {
                    throw new IllegalStateException(PRIVATE_MARKER);
                }
            } finally {
                finished.countDown();
            }
        }
    }
}
