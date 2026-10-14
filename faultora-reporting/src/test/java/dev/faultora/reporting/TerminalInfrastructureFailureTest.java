package dev.faultora.reporting;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.RunId;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalInfrastructureFailureTest {
    @Test
    void aJournalFlushFailureAfterCompletionCannotProduceAPassingReport() throws Exception {
        RunId run = new RunId("synthetic-run");
        List<RunEvent> events = List.of(new RunEvent.RunStarted("RUN_STARTED", 1, run, "synthetic", "synthetic", 42, Map.of()),
                new RunEvent.RunCompleted("RUN_COMPLETED", 2, run, 0, 0, 0, 1),
                new RunEvent.RunFailed("RUN_FAILED", 3, run,
                        new NormalizedError(NormalizedError.ErrorCategory.INTERNAL, "JOURNAL_WRITE_FAILED",
                                "Mandatory run journal could not be written", false, Map.of()), 2));
        assertThat(RunSummary.of(events).passed()).isFalse();
        assertThat(RunSummary.of(events).durationMs()).isEqualTo(2);
        StringWriter console = new StringWriter();
        new ConsoleRenderer().render(events, console);
        assertThat(console.toString()).contains("Result: FAILED").doesNotContain("Result: PASSED");
        StringWriter html = new StringWriter();
        new HtmlRenderer().render(events, html);
        assertThat(html.toString()).contains("badge fail\">FAILED").doesNotContain("badge pass\">PASSED");
        StringWriter xml = new StringWriter();
        new JUnitXmlRenderer().render(events, xml);
        assertThat(xml.toString()).contains("tests=\"1\" failures=\"1\"", "<failure");
    }
}
