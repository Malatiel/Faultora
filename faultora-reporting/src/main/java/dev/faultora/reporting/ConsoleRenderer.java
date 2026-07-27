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
        Map<String, List<AssertionSummary>> pendingAssertions = new HashMap<>();
        Map<String, Integer> retryCounts = new HashMap<>();
        Map<String, Integer> pollCounts = new HashMap<>();

        for (RunEvent event : events) {
            switch (event) {
                case RunEvent.OperationRetried or ->
                        retryCounts.merge(or.nodeId().value(), 1, Integer::sum);
                case RunEvent.ConditionPolled cp ->
                        pollCounts.merge(cp.nodeId().value(), 1, Integer::sum);
                case RunEvent.RunStarted rs -> started = rs;
                case RunEvent.RunCompleted rc -> completed = rc;
                case RunEvent.RunFailed rf -> failed = rf;
                case RunEvent.NodeCompleted nc -> {
                    String nodeId = nc.nodeId().value();
                    NodeSummary node = new NodeSummary(nodeId, "PASSED", nc.durationMs(), null);
                    applyAssertions(node, pendingAssertions.remove(nodeId));
                    nodes.add(node);
                    nodeIndex.put(nodeId, node);
                }
                case RunEvent.NodeFailed nf -> {
                    String nodeId = nf.nodeId().value();
                    NodeSummary node = new NodeSummary(
                            nodeId, "FAILED", nf.durationMs(), nf.error());
                    applyAssertions(node, pendingAssertions.remove(nodeId));
                    nodes.add(node);
                    nodeIndex.put(nodeId, node);
                }
                case RunEvent.AssertionEvaluated ae -> {
                    String nodeId = ae.nodeId().value();
                    AssertionSummary assertion = new AssertionSummary(ae.outcome(), ae.message());
                    NodeSummary node = nodeIndex.get(nodeId);
                    if (node != null) {
                        applyAssertions(node, List.of(assertion));
                    } else {
                        pendingAssertions
                                .computeIfAbsent(nodeId, key -> new ArrayList<>())
                                .add(assertion);
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
            output.write(String.format("  [%s] %s (%dms)%s\n",
                    node.status, node.name, node.durationMs,
                    attemptSuffix(retryCounts.get(node.name), pollCounts.get(node.name))));
            if (node.error != null) {
                output.write("         Error: " + node.error.message() + "\n");
            }
            for (AssertionSummary assertion : node.assertions) {
                output.write("         Assertion: " + assertion.outcome());
                if (assertion.message() != null) {
                    output.write(" — " + assertion.message());
                }
                output.write("\n");
            }
        }
        output.write("\n");

        // Fault windows and attribution
        List<FaultTimeline.Window> faultWindows = FaultTimeline.windows(events);
        if (!faultWindows.isEmpty()) {
            output.write("--- Faults ---\n");
            for (FaultTimeline.Window window : faultWindows) {
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

    private static void applyAssertions(NodeSummary node, List<AssertionSummary> assertions) {
        if (assertions == null) {
            return;
        }
        node.assertions.addAll(assertions);
        for (AssertionSummary assertion : assertions) {
            if (!"PASS".equals(assertion.outcome())) {
                node.status = "FAILED";
            }
        }
    }

    /** Attempt counts are only shown for nodes that made more than one. */
    private static String attemptSuffix(Integer retries, Integer polls) {
        if (retries != null) {
            return " — " + retries + " retr" + (retries == 1 ? "y" : "ies");
        }
        if (polls != null) {
            return " — " + polls + " poll" + (polls == 1 ? "" : "s");
        }
        return "";
    }

    private record AssertionSummary(String outcome, String message) {}

    private static class NodeSummary {
        final String name;
        final List<AssertionSummary> assertions = new ArrayList<>();
        String status;
        long durationMs;
        NormalizedError error;

        NodeSummary(String name, String status, long durationMs, NormalizedError error) {
            this.name = name;
            this.status = status;
            this.durationMs = durationMs;
            this.error = error;
        }
    }
}
