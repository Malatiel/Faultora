package dev.faultora.reporting;

import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Renders run results as JUnit XML.
 * Compatible with CI parsers (Jenkins, GitLab, GitHub Actions).
 * Each node becomes a test case; failures are recorded as failure elements.
 */
public class JUnitXmlRenderer implements ReportRenderer {

    @Override
    public String format() {
        return "junit";
    }

    @Override
    public void render(List<RunEvent> events, Writer output) throws IOException {
        RunSummary summary = RunSummary.of(events);
        List<RunSummary.Node> nodes = summary.nodes();

        int failures = (int) nodes.stream().filter(node -> !node.passed()).count();
        double totalTime = nodes.stream().mapToDouble(node -> node.durationMs() / 1000.0).sum();
        String suiteName = "faultora-run-" + summary.runId();

        output.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        output.write(String.format(
                "<testsuite name=\"%s\" tests=\"%d\" failures=\"%d\" errors=\"0\" time=\"%.3f\">\n",
                escapeXml(suiteName), nodes.size(), failures, totalTime));

        for (RunSummary.Node node : nodes) {
            output.write(String.format(
                    "  <testcase name=\"%s\" classname=\"%s\" time=\"%.3f\"",
                    escapeXml(node.name()), escapeXml("faultora"), node.durationMs() / 1000.0));

            if (node.passed()) {
                output.write(" />\n");
                continue;
            }
            String message = failureMessage(node);
            output.write(">\n");
            output.write(String.format(
                    "    <failure message=\"%s\" type=\"%s\">%s</failure>\n",
                    escapeXml(message),
                    escapeXml(node.error() != null ? node.error().code() : "ASSERTION_FAILURE"),
                    escapeXml(message)));
            output.write("  </testcase>\n");
        }

        // A run can fail for reasons no single node owns — a scenario deadline,
        // cancellation, or a cleanup failure. CI must still see a failure.
        if (summary.failed() && failures == 0) {
            output.write(String.format(
                    "  <testcase name=\"run-failure\" classname=\"faultora\" time=\"%.3f\">\n",
                    summary.durationMs() / 1000.0));
            output.write(String.format(
                    "    <failure message=\"%s\" type=\"RUN_FAILURE\">%s</failure>\n",
                    escapeXml(summary.runError() != null
                            ? summary.runError().message() : "run failed"),
                    escapeXml(summary.runError() != null ? summary.runError().code() : "")));
            output.write("  </testcase>\n");
        }

        output.write("</testsuite>\n");
    }

    /**
     * The node's own error explains the failure when it has one; otherwise the
     * assertions that did not pass do.
     */
    private static String failureMessage(RunSummary.Node node) {
        if (node.error() != null && node.error().message() != null) {
            return node.error().message();
        }
        List<String> assertionMessages = node.failedAssertionMessages();
        if (!assertionMessages.isEmpty()) {
            return String.join("; ", assertionMessages);
        }
        return "assertion failed";
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
