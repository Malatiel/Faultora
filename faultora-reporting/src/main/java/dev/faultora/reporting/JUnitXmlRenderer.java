package dev.faultora.reporting;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
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
        RunEvent.RunStarted started = null;
        RunEvent.RunCompleted completed = null;
        RunEvent.RunFailed failed = null;
        List<TestCase> testCases = new ArrayList<>();

        for (RunEvent event : events) {
            switch (event) {
                case RunEvent.RunStarted rs -> started = rs;
                case RunEvent.RunCompleted rc -> completed = rc;
                case RunEvent.RunFailed rf -> failed = rf;
                case RunEvent.NodeCompleted nc -> testCases.add(new TestCase(
                        nc.nodeId().value(), nc.durationMs() / 1000.0,
                        null, null, false));
                case RunEvent.NodeFailed nf -> testCases.add(new TestCase(
                        nf.nodeId().value(), nf.durationMs() / 1000.0,
                        nf.error(), nf.error() != null ? nf.error().message() : null, true));
                case RunEvent.AssertionEvaluated ae -> {
                    if (!testCases.isEmpty()) {
                        TestCase last = testCases.get(testCases.size() - 1);
                        if (last.name.equals(ae.nodeId().value())) {
                            if ("FAIL".equals(ae.outcome())) {
                                last.failure = ae.message();
                                last.isFailure = true;
                            }
                        }
                    }
                }
                default -> { /* skip */ }
            }
        }

        // Determine overall attributes
        int totalTests = testCases.size();
        int failures = (int) testCases.stream().filter(tc -> tc.isFailure).count();
        double totalTime = testCases.stream().mapToDouble(tc -> tc.timeSeconds).sum();
        String suiteName = started != null
                ? "faultora-run-" + started.runId().value()
                : "faultora-run";

        // XML output
        output.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        output.write(String.format(
                "<testsuite name=\"%s\" tests=\"%d\" failures=\"%d\" errors=\"0\" time=\"%.3f\">\n",
                escapeXml(suiteName), totalTests, failures, totalTime));

        for (TestCase tc : testCases) {
            output.write(String.format(
                    "  <testcase name=\"%s\" classname=\"%s\" time=\"%.3f\"",
                    escapeXml(tc.name), escapeXml("faultora"), tc.timeSeconds));

            if (tc.isFailure) {
                output.write(">\n");
                output.write(String.format(
                        "    <failure message=\"%s\" type=\"%s\">%s</failure>\n",
                        escapeXml(tc.failure != null ? tc.failure : "assertion failed"),
                        escapeXml(tc.error != null ? tc.error.code() : "ASSERTION_FAILURE"),
                        escapeXml(tc.failure != null ? tc.failure : "")));
                output.write("  </testcase>\n");
            } else {
                output.write(" />\n");
            }
        }

        // If the run itself failed, add a synthetic failure case
        if (failed != null && testCases.stream().noneMatch(tc -> tc.isFailure)) {
            output.write(String.format(
                    "  <testcase name=\"run-failure\" classname=\"faultora\" time=\"%.3f\">\n",
                    failed.durationMs() / 1000.0));
            output.write(String.format(
                    "    <failure message=\"%s\" type=\"RUN_FAILURE\">%s</failure>\n",
                    escapeXml(failed.error() != null ? failed.error().message() : "run failed"),
                    escapeXml(failed.error() != null ? failed.error().code() : "")));
            output.write("  </testcase>\n");
        }

        output.write("</testsuite>\n");
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

    private static class TestCase {
        final String name;
        final double timeSeconds;
        NormalizedError error;
        String failure;
        boolean isFailure;

        TestCase(String name, double timeSeconds, NormalizedError error,
                 String failure, boolean isFailure) {
            this.name = name;
            this.timeSeconds = timeSeconds;
            this.error = error;
            this.failure = failure;
            this.isFailure = isFailure;
        }
    }
}
