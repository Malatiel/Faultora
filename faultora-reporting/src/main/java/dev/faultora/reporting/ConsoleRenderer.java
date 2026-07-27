package dev.faultora.reporting;

import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Renders run results as human-readable console output.
 * Shows node summaries, fault windows, and a final pass/fail verdict.
 * No external assets or ANSI escapes — works in any terminal or log file.
 */
public class ConsoleRenderer implements ReportRenderer {

    @Override
    public String format() {
        return "console";
    }

    @Override
    public void render(List<RunEvent> events, Writer output) throws IOException {
        RunSummary summary = RunSummary.of(events);

        output.write("=== Faultora Run Report ===\n");
        output.write("Run ID: " + summary.runId() + "\n");
        output.write("Scenario digest: " + summary.scenarioDigest() + "\n");
        output.write("Catalog digest: " + summary.catalogDigest() + "\n");
        output.write("Seed: " + summary.seed() + "\n\n");

        output.write("--- Nodes ---\n");
        for (RunSummary.Node node : summary.nodes()) {
            output.write(String.format("  [%s] %s (%dms)%s\n",
                    node.status(), node.name(), node.durationMs(), attemptSuffix(node)));
            if (node.error() != null) {
                output.write("         Error: " + node.error().message() + "\n");
            }
            for (RunSummary.Assertion assertion : node.assertions()) {
                output.write("         Assertion: " + assertion.outcome());
                if (assertion.message() != null) {
                    output.write(" — " + assertion.message());
                }
                output.write("\n");
            }
        }
        output.write("\n");

        if (!summary.faultWindows().isEmpty()) {
            output.write("--- Faults ---\n");
            for (FaultTimeline.Window window : summary.faultWindows()) {
                output.write(String.format("  [%s] target %s — active %dms, rollback: %s\n",
                        window.faultType(), window.targetScope(),
                        Math.max(0, window.endAtMs() - window.injectedAtMs()),
                        window.rollbackStatus()));
                if (!window.affectedNodes().isEmpty()) {
                    output.write("         During fault: "
                            + String.join(", ", window.affectedNodes()) + "\n");
                }
            }
            output.write("\n");
        }

        if (summary.passed()) {
            output.write(String.format(
                    "Result: PASSED — %d nodes, %d passed assertions, %d failed assertions (%dms)\n",
                    summary.totalNodes(), summary.passedAssertions(),
                    summary.failedAssertions(), summary.durationMs()));
        } else if (summary.failed()) {
            output.write(String.format("Result: FAILED — %s (%dms)\n",
                    summary.runError() != null ? summary.runError().message() : "unknown",
                    summary.durationMs()));
        }
        output.flush();
    }

    /** Attempt counts are only shown for nodes that made more than one. */
    private static String attemptSuffix(RunSummary.Node node) {
        if (node.retries() != null) {
            return " — " + node.retries() + " retr" + (node.retries() == 1 ? "y" : "ies");
        }
        if (node.polls() != null) {
            return " — " + node.polls() + " poll" + (node.polls() == 1 ? "" : "s");
        }
        return "";
    }
}
