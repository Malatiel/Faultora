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
 * Renders run results as a self-contained HTML report.
 * No external assets, CDN, or JavaScript dependencies.
 * Works offline and in CI artifact viewers.
 */
public class HtmlRenderer implements ReportRenderer {

    @Override
    public String format() {
        return "html";
    }

    @Override
    public void render(List<RunEvent> events, Writer output) throws IOException {
        RunEvent.RunStarted started = null;
        RunEvent.RunCompleted completed = null;
        RunEvent.RunFailed failed = null;
        List<NodeRecord> nodes = new ArrayList<>();
        Map<String, NodeRecord> nodeIndex = new HashMap<>();
        Map<String, AssertionRecord> pendingAssertions = new HashMap<>();

        for (RunEvent event : events) {
            switch (event) {
                case RunEvent.RunStarted rs -> started = rs;
                case RunEvent.RunCompleted rc -> completed = rc;
                case RunEvent.RunFailed rf -> failed = rf;
                case RunEvent.NodeCompleted nc -> {
                    String nodeId = nc.nodeId().value();
                    AssertionRecord assertion = pendingAssertions.remove(nodeId);
                    String status = assertion != null && !"PASS".equals(assertion.outcome())
                            ? "FAILED" : "PASSED";
                    NodeRecord node = new NodeRecord(
                            nodeId, status, nc.durationMs(), nc.statusCode(), null, null);
                    applyAssertion(node, assertion);
                    nodes.add(node);
                    nodeIndex.put(nodeId, node);
                }
                case RunEvent.NodeFailed nf -> {
                    String nodeId = nf.nodeId().value();
                    NodeRecord node = new NodeRecord(
                            nodeId, "FAILED", nf.durationMs(), -1, nf.error(), null);
                    applyAssertion(node, pendingAssertions.remove(nodeId));
                    nodes.add(node);
                    nodeIndex.put(nodeId, node);
                }
                case RunEvent.AssertionEvaluated ae -> {
                    String nodeId = ae.nodeId().value();
                    AssertionRecord assertion = new AssertionRecord(ae.outcome(), ae.message());
                    NodeRecord node = nodeIndex.get(nodeId);
                    if (node != null) {
                        applyAssertion(node, assertion);
                    } else {
                        pendingAssertions.put(nodeId, assertion);
                    }
                }
                default -> { /* skip */ }
            }
        }

        String runId = started != null ? started.runId().value() : "unknown";
        String scenarioDigest = started != null ? started.scenarioDigest() : "";
        String catalogDigest = started != null ? started.catalogDigest() : "";
        long seed = started != null ? started.seed() : 0;
        boolean passed = completed != null;
        long totalDuration = completed != null ? completed.durationMs()
                : (failed != null ? failed.durationMs() : 0);
        int passedAssertions = completed != null ? completed.passedAssertions() : 0;
        int failedAssertions = completed != null ? completed.failedAssertions() : 0;

        output.write("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        output.write("<meta charset=\"UTF-8\">\n");
        output.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        output.write("<title>Faultora Run Report — " + escapeHtml(runId) + "</title>\n");
        output.write("<style>\n");
        output.write(CSS);
        output.write("</style>\n</head>\n<body>\n");

        // Header
        output.write("<header>\n");
        output.write("<h1>Faultora Run Report</h1>\n");
        output.write(String.format("<span class=\"badge %s\">%s</span>\n",
                passed ? "pass" : "fail", passed ? "PASSED" : "FAILED"));
        output.write("</header>\n");

        // Run metadata
        output.write("<section class=\"metadata\">\n");
        output.write("<table>\n");
        output.write(metaRow("Run ID", runId));
        output.write(metaRow("Scenario digest", scenarioDigest));
        output.write(metaRow("Catalog digest", catalogDigest));
        output.write(metaRow("Seed", String.valueOf(seed)));
        output.write(metaRow("Duration", totalDuration + "ms"));
        output.write(metaRow("Passed assertions", String.valueOf(passedAssertions)));
        output.write(metaRow("Failed assertions", String.valueOf(failedAssertions)));
        output.write("</table>\n</section>\n");

        // Node table
        output.write("<section class=\"nodes\">\n<h2>Nodes</h2>\n");
        output.write("<table>\n<thead><tr>");
        output.write("<th>Node</th><th>Status</th><th>Status Code</th>");
        output.write("<th>Duration</th><th>Details</th></tr></thead>\n<tbody>\n");
        for (NodeRecord node : nodes) {
            String statusClass = "PASSED".equals(node.status) ? "pass" : "fail";
            output.write("<tr>");
            output.write("<td>" + escapeHtml(node.name) + "</td>");
            output.write(String.format("<td><span class=\"badge %s\">%s</span></td>",
                    statusClass, node.status));
            output.write("<td>" + (node.statusCode >= 0 ? node.statusCode : "—") + "</td>");
            output.write("<td>" + node.durationMs + "ms</td>");
            output.write("<td>");
            if (node.error != null) {
                output.write(escapeHtml(node.error.message()));
            }
            if (node.assertionOutcome != null) {
                output.write(String.format("<span class=\"badge %s\">%s</span>",
                        "PASS".equals(node.assertionOutcome) ? "pass" : "fail",
                        escapeHtml(node.assertionOutcome)));
                if (node.assertionMessage != null) {
                    output.write(" — " + escapeHtml(node.assertionMessage));
                }
            }
            output.write("</td></tr>\n");
        }
        output.write("</tbody>\n</table>\n</section>\n");

        // Error details if run failed
        if (failed != null && failed.error() != null) {
            output.write("<section class=\"error\">\n<h2>Error</h2>\n");
            output.write("<pre>" + escapeHtml(failed.error().message()) + "</pre>\n");
            output.write("</section>\n");
        }

        // Footer
        output.write("<footer>Generated by Faultora — offline report, no external assets</footer>\n");
        output.write("</body>\n</html>\n");
    }

    private static String metaRow(String label, String value) {
        return "<tr><th>" + escapeHtml(label) + "</th><td>" + escapeHtml(value) + "</td></tr>\n";
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void applyAssertion(NodeRecord node, AssertionRecord assertion) {
        if (assertion == null) {
            return;
        }
        node.assertionOutcome = assertion.outcome();
        node.assertionMessage = assertion.message();
        if (!"PASS".equals(assertion.outcome())) {
            node.status = "FAILED";
        }
    }

    private record AssertionRecord(String outcome, String message) {}

    private static class NodeRecord {
        final String name;
        String status;
        long durationMs;
        int statusCode;
        NormalizedError error;
        String assertionOutcome;
        String assertionMessage;

        NodeRecord(String name, String status, long durationMs, int statusCode,
                   NormalizedError error, String assertionMessage) {
            this.name = name;
            this.status = status;
            this.durationMs = durationMs;
            this.statusCode = statusCode;
            this.error = error;
            this.assertionMessage = assertionMessage;
        }
    }

    private static final String CSS = """
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                   max-width: 960px; margin: 0 auto; padding: 2rem; color: #1a1a1a;
                   background: #fafafa; }
            header { display: flex; align-items: center; gap: 1rem; margin-bottom: 2rem; }
            h1 { font-size: 1.5rem; }
            h2 { font-size: 1.2rem; margin: 1.5rem 0 0.5rem; }
            .badge { padding: 0.2rem 0.6rem; border-radius: 4px; font-size: 0.85rem;
                     font-weight: 600; }
            .badge.pass { background: #d4edda; color: #155724; }
            .badge.fail { background: #f8d7da; color: #721c24; }
            .metadata table { width: 100%; border-collapse: collapse; margin-bottom: 1rem; }
            .metadata th { text-align: left; padding: 0.4rem; color: #555; width: 180px; }
            .metadata td { padding: 0.4rem; }
            .nodes table { width: 100%; border-collapse: collapse; }
            .nodes th, .nodes td { padding: 0.5rem; border-bottom: 1px solid #e0e0e0;
                                   text-align: left; }
            .nodes th { background: #f0f0f0; }
            .error pre { background: #f8f8f8; padding: 1rem; border-radius: 4px;
                         overflow-x: auto; font-size: 0.9rem; }
            footer { margin-top: 3rem; padding-top: 1rem; border-top: 1px solid #e0e0e0;
                     color: #888; font-size: 0.85rem; }
            """;
}
