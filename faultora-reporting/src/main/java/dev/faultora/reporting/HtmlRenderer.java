package dev.faultora.reporting;

import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

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
        RunSummary summary = RunSummary.of(events);

        output.write("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        output.write("<meta charset=\"UTF-8\">\n");
        output.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        output.write("<title>Faultora Run Report — " + escapeHtml(summary.runId()) + "</title>\n");
        output.write("<style>\n");
        output.write(CSS);
        output.write("</style>\n</head>\n<body>\n");

        output.write("<header>\n");
        output.write("<h1>Faultora Run Report</h1>\n");
        output.write(String.format("<span class=\"badge %s\">%s</span>\n",
                summary.passed() ? "pass" : "fail", summary.passed() ? "PASSED" : "FAILED"));
        output.write("</header>\n");

        output.write("<section class=\"metadata\">\n");
        output.write("<table>\n");
        output.write(metaRow("Run ID", summary.runId()));
        output.write(metaRow("Scenario digest", summary.scenarioDigest()));
        output.write(metaRow("Catalog digest", summary.catalogDigest()));
        output.write(metaRow("Seed", String.valueOf(summary.seed())));
        output.write(metaRow("Duration", summary.durationMs() + "ms"));
        output.write(metaRow("Passed assertions", String.valueOf(summary.passedAssertions())));
        output.write(metaRow("Failed assertions", String.valueOf(summary.failedAssertions())));
        output.write("</table>\n</section>\n");

        output.write("<section class=\"nodes\">\n<h2>Nodes</h2>\n");
        output.write("<table>\n<thead><tr>");
        output.write("<th>Node</th><th>Status</th><th>Status Code</th>");
        output.write("<th>Duration</th><th>Details</th></tr></thead>\n<tbody>\n");
        for (RunSummary.Node node : summary.nodes()) {
            output.write("<tr>");
            output.write("<td>" + escapeHtml(node.name()) + "</td>");
            output.write(String.format("<td><span class=\"badge %s\">%s</span></td>",
                    node.passed() ? "pass" : "fail", node.status()));
            output.write("<td>" + (node.statusCode() >= 0 ? node.statusCode() : "—") + "</td>");
            output.write("<td>" + node.durationMs() + "ms</td>");
            output.write("<td>");
            if (node.retries() != null) {
                output.write("<span class=\"muted\">" + node.retries() + " retr"
                        + (node.retries() == 1 ? "y" : "ies") + "</span> ");
            }
            if (node.polls() != null) {
                output.write("<span class=\"muted\">" + node.polls() + " poll"
                        + (node.polls() == 1 ? "" : "s") + "</span> ");
            }
            if (node.error() != null) {
                output.write(escapeHtml(node.error().message()));
            }
            for (RunSummary.Assertion assertion : node.assertions()) {
                output.write(String.format("<span class=\"badge %s\">%s</span>",
                        assertion.passed() ? "pass" : "fail",
                        escapeHtml(assertion.outcome())));
                if (assertion.message() != null) {
                    output.write(" — " + escapeHtml(assertion.message()));
                }
            }
            output.write("</td></tr>\n");
        }
        output.write("</tbody>\n</table>\n</section>\n");

        if (!summary.faultWindows().isEmpty()) {
            output.write("<section class=\"nodes\">\n<h2>Faults</h2>\n");
            output.write("<table>\n<thead><tr>");
            output.write("<th>Fault</th><th>Target</th><th>Active</th>");
            output.write("<th>Rollback</th><th>Nodes during fault</th></tr></thead>\n<tbody>\n");
            for (FaultTimeline.Window window : summary.faultWindows()) {
                output.write("<tr>");
                output.write("<td>" + escapeHtml(window.faultType())
                        + " <span class=\"muted\">(" + escapeHtml(window.handle()) + ")</span></td>");
                output.write("<td>" + escapeHtml(window.targetScope()) + "</td>");
                output.write("<td>" + Math.max(0, window.endAtMs() - window.injectedAtMs())
                        + "ms</td>");
                output.write("<td>" + escapeHtml(window.rollbackStatus()) + "</td>");
                output.write("<td>" + escapeHtml(String.join(", ", window.affectedNodes()))
                        + "</td>");
                output.write("</tr>\n");
            }
            output.write("</tbody>\n</table>\n</section>\n");
        }

        if (summary.runError() != null) {
            output.write("<section class=\"error\">\n<h2>Error</h2>\n");
            output.write("<pre>" + escapeHtml(summary.runError().message()) + "</pre>\n");
            output.write("</section>\n");
        }

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
            .muted { color: #888; font-size: 0.85rem; }
            .error pre { background: #f8f8f8; padding: 1rem; border-radius: 4px;
                         overflow-x: auto; font-size: 0.9rem; }
            footer { margin-top: 3rem; padding-top: 1rem; border-top: 1px solid #e0e0e0;
                     color: #888; font-size: 0.85rem; }
            """;
}
