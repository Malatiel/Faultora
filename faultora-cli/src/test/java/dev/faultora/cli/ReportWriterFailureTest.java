package dev.faultora.cli;

import dev.faultora.engine.exec.JournalWriter;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.RunId;
import dev.faultora.reporting.HtmlRenderer;
import dev.faultora.reporting.JsonRenderer;
import dev.faultora.reporting.JUnitXmlRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterFailureTest {
    @TempDir Path directory;

    @Test
    void reportsUseRetainedEventsWhenStorageFailedDuringClose() throws Exception {
        RunId run = new RunId("synthetic-report");
        var broken = new StringWriter() {
            public void close() throws IOException {
                throw new IOException("SYNTHETIC_PRIVATE_CLOSE_MESSAGE");
            }
        };
        try (RunJournal journal = new RunJournal(directory.resolve("events.ndjson"), false, broken)) {
            journal.append(new RunEvent.RunStarted("RUN_STARTED", 1, run, "synthetic", "synthetic", 42, Map.of()));
            journal.append(new RunEvent.RunCompleted("RUN_COMPLETED", 2, run, 0, 0, 0, 1));
            RunResult result = JournalWriter.closeJournal(journal,
                    new RunResult(run, RunResult.Status.PASSED, 0, 0, 0, Map.of(), 1, null));
            assertThat(result.status()).isEqualTo(RunResult.Status.ERROR);
            StringWriter notices = new StringWriter();
            ReportWriter.writeAll(List.of("json", "html", "junit"),
                    Map.of("json", new JsonRenderer(), "html", new HtmlRenderer(), "junit", new JUnitXmlRenderer()),
                    journal.events(), directory, new PrintWriter(notices));
            assertThat(notices.toString()).contains("Report written:");
            assertThat(Files.readString(directory.resolve("report.html"))).contains("badge fail\">FAILED")
                    .doesNotContain("badge pass\">PASSED", "SYNTHETIC_PRIVATE_CLOSE_MESSAGE");
            assertThat(Files.readString(directory.resolve("report.json"))).contains("JOURNAL_WRITE_FAILED")
                    .doesNotContain("SYNTHETIC_PRIVATE_CLOSE_MESSAGE");
            assertThat(Files.readString(directory.resolve("report.xml"))).contains("tests=\"1\" failures=\"1\"", "<failure")
                    .doesNotContain("SYNTHETIC_PRIVATE_CLOSE_MESSAGE");
        }
    }
}
