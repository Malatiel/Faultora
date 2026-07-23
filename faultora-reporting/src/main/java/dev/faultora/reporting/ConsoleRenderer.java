package dev.faultora.reporting;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, NodeSummary> nodeIndex = new HashMap<>();
        Map<String, AssertionSummary> pendingAssertions = new HashMap<>();

        for (RunEvent event : events) {
            switch (event) {
                case RunEvent.RunStarted rs -> started = rs;
                case RunEvent.RunCompleted rc -> completed = rc;
                case RunEvent.RunFailed rf -> failed = rf;
                case RunEvent.NodeCompleted nc -> {
                    String nodeId = nc.nodeId().value();
                    AssertionSummary assertion = pendingAssertions.remove(nodeId);
                    String status = assertion != null && !"PASS".equals(assertion.outcome())
                            ? "FAILED" : "PASSED";
                    NodeSummary node = new NodeSummary(nodeId, status, nc.durationMs(), null);
                    applyAssertion(node, assertion);
                    nodes.add(node);
                    nodeIndex.put(nodeId, node);
                }
                case RunEvent.NodeFailed nf -> {
                    String nodeId = nf.nodeId().value();
                    NodeSummary node = new NodeSummary(
                            nodeId, "FAILED", nf.durationMs(), nf.error());
                    applyAssertion(node, pendingAssertions.remove(nodeId));
                    nodes.add(node);
                    nodeIndex.put(nodeId, node);
                }
                case RunEvent.AssertionEvaluated ae -> {
                    String nodeId = ae.nodeId().value();
                    AssertionSummary assertion = new AssertionSummary(ae.outcome(), ae.message());
                    NodeSummary node = nodeIndex.get(nodeId);
                    if (node != null) {
                        applyAssertion(node, assertion);
                    } else {
                        pendingAssertions.put(nodeId, assertion);
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
        output.flush();
    }

    private static void applyAssertion(NodeSummary node, AssertionSummary assertion) {
        if (assertion == null) {
            return;
        }
        node.assertionOutcome = assertion.outcome();
        node.assertionMessage = assertion.message();
        if (!"PASS".equals(assertion.outcome())) {
            node.status = "FAILED";
        }
    }

    private record AssertionSummary(String outcome, String message) {}

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
