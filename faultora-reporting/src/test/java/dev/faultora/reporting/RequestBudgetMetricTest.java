package dev.faultora.reporting;

import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.RunId;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBudgetMetricTest {
    private static final RunId RUN = new RunId("synthetic-run");
    private static final NodeId NODE = new NodeId("synthetic-node");

    @Test
    void scheduledRetriesAndEvaluatedPollsDoNotInventStartedAttempts() throws Exception {
        List<RunEvent> events = List.of(started(Map.of("requestBudgetUnit", "connector-attempt")),
                attempted(1), attempted(2),
                new RunEvent.OperationRetried("OPERATION_RETRIED", 2, RUN, NODE, 2, 3, 5000, "SYNTHETIC"),
                new RunEvent.ConditionPolled("CONDITION_POLLED", 3, RUN, NODE, 10, 10, 0, false, "not ready"));
        assertThat(RunSummary.of(events).connectorAttempts()).isEqualTo(2);
        StringWriter console = new StringWriter();
        new ConsoleRenderer().render(events, console);
        assertThat(console.toString()).contains("Connector attempts: 2");
        StringWriter html = new StringWriter();
        new HtmlRenderer().render(events, html);
        assertThat(html.toString()).contains("Connector attempts</th><td>2</td>");
    }

    @Test
    void aNewJournalWithNoGrantedAttemptReportsZero() {
        assertThat(RunSummary.of(List.of(started(Map.of("requestBudgetUnit", "connector-attempt"))))
                .connectorAttempts()).isZero();
    }

    @Test
    void anOldJournalKeepsTheMetricUnknownRatherThanGuessingFromNodes() throws Exception {
        List<RunEvent> events = List.of(started(Map.of()),
                new RunEvent.NodeCompleted("NODE_COMPLETED", 2, RUN, NODE, 0, 200, 0));
        assertThat(RunSummary.of(events).connectorAttempts()).isNull();
        StringWriter console = new StringWriter();
        new ConsoleRenderer().render(events, console);
        assertThat(console.toString()).doesNotContain("Connector attempts:");
    }

    @Test
    void outOfOrderParallelEventsCountGrantsInsteadOfSummingSequenceNumbers() {
        assertThat(RunSummary.of(List.of(started(Map.of("requestBudgetUnit", "connector-attempt")),
                attempted(3), attempted(1), attempted(2))).connectorAttempts()).isEqualTo(3);
    }

    @Test
    void jsonReportsKeepTheSameAttemptEventsAsTheJournal() throws Exception {
        StringWriter json = new StringWriter();
        new JsonRenderer().render(List.of(started(Map.of("requestBudgetUnit", "connector-attempt")), attempted(1)), json);
        assertThat(json.toString()).contains("OPERATION_ATTEMPTED", "\"attemptNumber\" : 1");
    }

    private static RunEvent started(Map<String, Object> config) {
        return new RunEvent.RunStarted("RUN_STARTED", 1, RUN, "synthetic-scenario", "synthetic-catalog", 42, config);
    }

    private static RunEvent attempted(long sequence) {
        return new RunEvent.OperationAttempted("OPERATION_ATTEMPTED", 2, RUN, NODE,
                new OperationId("synthetic-operation"), sequence);
    }
}
