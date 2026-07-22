package dev.faultora.reporting;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders run results as human-readable console output.
 * Shows progress dots, node summaries, and a final pass/fail verdict.
 * No external assets or ANSI escapes — works in any terminal or log file.
 */
public class ConsoleRenderer implements ReportRenderer {

    @Override
    public String format() {
        return "console";
    }

    @Override
    public void render(List<RunEvent> events, Writer output) throws IOException {
        RunEvent.RunStarted started = null;
        RunEvent.RunCompleted completed = null;
        RunEvent.RunFailed failed = null;
        List<NodeSummary> nodes = new ArrayList<>();

        for (RunEvent event : events) {
            switch (event) {
                case RunEvent.RunStarted rs -> started = rs;
                case RunEvent.RunCompleted rc -> completed = rc;
                case RunEvent.RunFailed rf -> failed = rf;
                case RunEvent.NodeCompleted nc -> nodes.add(new NodeSummary(
                        nc.nodeId().value(), "PASSED", nc.durationMs(), null));
                case RunEvent.NodeFailed nf -> nodes.add(new NodeSummary(
                        nf.nodeId().value(), "FAILED", nf.durationMs(), nf.error()));
                case RunEvent.AssertionEvaluated ae -> {
                    // Attach assertion outcome to the last node if matching
                    if (!nodes.isEmpty()) {
                        NodeSummary last = nodes.get(nodes.size() - 1);
                        if (last.name.equals(ae.nodeId().value())) {
                            last.assertionOutcome = ae.outcome();
                            last.assertionMessage = ae.message();
                        }
                    }
                }
                default -> { /* skip intermediate events */ }
            }
        }

        // Header
        if (started != null) {
            output.write("=== Faultora Run Report ===\n");
            output.write("Run ID: " + started.runId().value() + "\n");
            output.write("Scenario digest: " + started.scenarioDigest() + "\n");
            output.write("Catalog digest: " + started.catalogDigest() + "\n");
            output.write("Seed: " + started.seed() + "\n\n");
        }

        // Node summaries
        output.write("--- Nodes ---\n");
        for (NodeSummary node : nodes) {
            output.write(String.format("  [%s] %s (%dms)\n",
                    node.status, node.name, node.durationMs));
            if (node.error != null) {
                output.write("         Error: " + node.error.message() + "\n");
            }
            if (node.assertionOutcome != null) {
                output.write("         Assertion: " + node.assertionOutcome);
                if (node.assertionMessage != null) {
                    output.write(" — " + node.assertionMessage);
                }
                output.write("\n");
            }
        }
        output.write("\n");

        // Summary
        if (completed != null) {
            output.write(String.format(
                    "Result: PASSED — %d nodes, %d passed assertions, %d failed assertions (%dms)\n",
                    completed.totalNodes(), completed.passedAssertions(),
                    completed.failedAssertions(), completed.durationMs()));
        } else if (failed != null) {
            output.write(String.format("Result: FAILED — %s (%dms)\n",
                    failed.error() != null ? failed.error().message() : "unknown",
                    failed.durationMs()));
        }
    }

    private static class NodeSummary {
        final String name;
        String status;
        long durationMs;
        NormalizedError error;
        String assertionOutcome;
        String assertionMessage;

        NodeSummary(String name, String status, long durationMs, NormalizedError error) {
            this.name = name;
            this.status = status;
            this.durationMs = durationMs;
            this.error = error;
        }
    }
}
