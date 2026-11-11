package dev.faultora.engine.journal;

import dev.faultora.engine.exec.JournalWriter;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.RunId;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunJournalBudgetTest {
    private static final Path PATH = Path.of("fixture.ndjson");
    private static final RunId RUN = new RunId("fixture-run");
    private static final NodeId NODE = new NodeId("fixture-node");

    @Test
    void aFullJournalPreservesCleanupAndReportsEventBudgetExhaustion() throws Exception {
        var output = new StringWriter();
        try (var journal = new RunJournal(PATH, true, output, 1, 4096)) {
            var cancellation = new AtomicBoolean();
            var writer = new JournalWriter(journal, RUN, cancellation);
            writer.nodeSkipped(NODE, "fixture");
            writer.nodeSkipped(NODE, "withheld");
            assertThat(writer.failed()).isTrue();
            assertThat(cancellation).isTrue();
            var error = writer.failureError(0, 0);
            assertThat(error.code()).isEqualTo("JOURNAL_WRITE_FAILED");
            assertThat(error.metadata()).containsEntry("journalOperation", "event-budget");

            writer.cleanupStarted(1);
            writer.cleanupCompleted(1, 0, 1);
            writer.runFailed(error, 1);
            assertThat(journal.events()).extracting(RunEvent::eventType)
                    .containsExactly("NODE_SKIPPED", "CLEANUP_STARTED", "CLEANUP_COMPLETED", "RUN_FAILED");
            assertThat(output.toString()).contains("Run event budget exhausted").doesNotContain("withheld");
        }
    }

    @Test
    void exactUtf8ByteLimitIncludesTheNewline() throws Exception {
        var event = new RunEvent.NodeSkipped("NODE_SKIPPED", 1, RUN, NODE, "проверка");
        var serialized = new StringWriter();
        try (var journal = new RunJournal(PATH, true, serialized)) {
            journal.append(event);
        }
        int bytes = serialized.toString().getBytes(StandardCharsets.UTF_8).length;

        try (var journal = new RunJournal(PATH, true, new StringWriter(), 10, bytes)) {
            journal.append(event);
            assertThatThrownBy(() -> journal.append(event))
                    .isInstanceOf(RunJournal.EventBudgetExceeded.class);
            assertThat(journal.events()).containsExactly(event);
        }
        try (var journal = new RunJournal(PATH, true, new StringWriter(), 10, bytes - 1L)) {
            assertThatThrownBy(() -> journal.append(event))
                    .isInstanceOf(RunJournal.EventBudgetExceeded.class);
            assertThat(journal.events()).isEmpty();
        }
    }
}
