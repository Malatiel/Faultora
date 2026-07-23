package dev.faultora.reporting;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.RunId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportRendererTest {

    private static final RunId RUN_ID = new RunId("test-run-001");
    private static final NodeId NODE_1 = new NodeId("step-1");
    private static final NodeId NODE_2 = new NodeId("step-2");

    @Test
    void jsonRendererProducesValidJson() throws IOException {
        StringWriter out = new StringWriter();
        new JsonRenderer().render(sampleEvents(), out);

        String json = out.toString();
        assertThat(json).contains("RUN_STARTED");
        assertThat(json).contains("test-run-001");
        assertThat(json).contains("NODE_COMPLETED");
    }

    @Test
    void consoleRendererShowsNodeStatuses() throws IOException {
        StringWriter out = new StringWriter();
        new ConsoleRenderer().render(sampleEvents(), out);

        String text = out.toString();
        assertThat(text).contains("Faultora Run Report");
        assertThat(text).contains("test-run-001");
        assertThat(text).contains("[PASSED] step-1");
        assertThat(text).contains("[FAILED] step-2");
        assertThat(text).contains("Result: FAILED");
    }

    @Test
    void consoleRendererAssociatesAssertionBeforeNodeCompletion() throws IOException {
        List<RunEvent> events = List.of(
                new RunEvent.RunStarted("RUN_STARTED", 1000, RUN_ID,
                        "sha256:abc", "sha256:def", 42, Map.of()),
                new RunEvent.NodeStarted("NODE_STARTED", 1100, RUN_ID,
                        NODE_1, "assertion", null),
                new RunEvent.AssertionEvaluated("ASSERTION_EVALUATED", 1200, RUN_ID,
                        NODE_1, "status", "FAIL", "Expected 201 but got 500"),
                new RunEvent.NodeCompleted("NODE_COMPLETED", 1300, RUN_ID,
                        NODE_1, 5, 0, 0),
                new RunEvent.RunFailed("RUN_FAILED", 1400, RUN_ID,
                        new NormalizedError(NormalizedError.ErrorCategory.INTERNAL,
                                "ASSERTION_FAILURES", "1 assertion failed", false, Map.of()),
                        400)
        );

        StringWriter out = new StringWriter();
        new ConsoleRenderer().render(events, out);

        String text = out.toString();
        assertThat(text).contains("[FAILED] step-1");
        assertThat(text).contains("Assertion: FAIL — Expected 201 but got 500");
    }

    @Test
    void junitXmlRendererProducesValidXml() throws IOException {
        StringWriter out = new StringWriter();
        new JUnitXmlRenderer().render(sampleEvents(), out);

        String xml = out.toString();
        assertThat(xml).contains("<?xml version=\"1.0\"");
        assertThat(xml).contains("<testsuite");
        assertThat(xml).contains("<testcase name=\"step-1\"");
        assertThat(xml).contains("<failure");
        assertThat(xml).contains("</testsuite>");
    }

    @Test
    void junitXmlRendererEscapesXmlSpecialChars() throws IOException {
        List<RunEvent> events = List.of(
                new RunEvent.RunStarted("RUN_STARTED", 1000, RUN_ID,
                        "sha256:abc", "sha256:def", 42, Map.of()),
                new RunEvent.NodeFailed("NODE_FAILED", 2000, RUN_ID,
                        new NodeId("fail-node"),
                        new NormalizedError(NormalizedError.ErrorCategory.INTERNAL,
                                "ERR", "message with <special> & \"chars\"", false, Map.of()),
                        500),
                new RunEvent.RunFailed("RUN_FAILED", 3000, RUN_ID,
                        new NormalizedError(NormalizedError.ErrorCategory.INTERNAL,
                                "FAIL", "run failed", false, Map.of()), 2000)
        );

        StringWriter out = new StringWriter();
        new JUnitXmlRenderer().render(events, out);

        String xml = out.toString();
        assertThat(xml).doesNotContain("<special>");
        assertThat(xml).contains("&lt;special&gt;");
        assertThat(xml).contains("&amp;");
    }

    @Test
    void htmlRendererProducesValidHtml() throws IOException {
        StringWriter out = new StringWriter();
        new HtmlRenderer().render(sampleEvents(), out);

        String html = out.toString();
        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("<title>Faultora Run Report");
        assertThat(html).contains("test-run-001");
        assertThat(html).contains("FAILED");
        assertThat(html).contains("step-1");
        assertThat(html).contains("step-2");
        assertThat(html).contains("</html>");
    }

    @Test
    void htmlRendererHasNoExternalAssets() throws IOException {
        StringWriter out = new StringWriter();
        new HtmlRenderer().render(sampleEvents(), out);

        String html = out.toString();
        assertThat(html).doesNotContain("https://");
        assertThat(html).doesNotContain("http://");
        assertThat(html).doesNotContain("cdn.");
        assertThat(html).doesNotContain("<script src=");
        assertThat(html).doesNotContain("<link href=");
    }

    @Test
    void htmlRendererEscapesHtmlSpecialChars() throws IOException {
        List<RunEvent> events = List.of(
                new RunEvent.RunStarted("RUN_STARTED", 1000, RUN_ID,
                        "sha256:<script>", "sha256:def", 0, Map.of()),
                new RunEvent.RunCompleted("RUN_COMPLETED", 2000, RUN_ID,
                        0, 0, 0, 1000)
        );

        StringWriter out = new StringWriter();
        new HtmlRenderer().render(events, out);

        String html = out.toString();
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).doesNotContain("<script>alert");
    }

    @Test
    void htmlRendererAssociatesAssertionBeforeNodeCompletion() throws IOException {
        List<RunEvent> events = List.of(
                new RunEvent.RunStarted("RUN_STARTED", 1000, RUN_ID,
                        "sha256:abc", "sha256:def", 42, Map.of()),
                new RunEvent.NodeStarted("NODE_STARTED", 1100, RUN_ID,
                        NODE_1, "assertion", null),
                new RunEvent.AssertionEvaluated("ASSERTION_EVALUATED", 1200, RUN_ID,
                        NODE_1, "status", "FAIL", "Expected 201 but got 500"),
                new RunEvent.NodeCompleted("NODE_COMPLETED", 1300, RUN_ID,
                        NODE_1, 5, 0, 0),
                new RunEvent.RunFailed("RUN_FAILED", 1400, RUN_ID,
                        new NormalizedError(NormalizedError.ErrorCategory.INTERNAL,
                                "ASSERTION_FAILURES", "1 assertion failed", false, Map.of()),
                        400)
        );

        StringWriter out = new StringWriter();
        new HtmlRenderer().render(events, out);

        String html = out.toString();
        assertThat(html).contains("<span class=\"badge fail\">FAILED</span>");
        assertThat(html).contains("<span class=\"badge fail\">FAIL</span>");
        assertThat(html).contains("Expected 201 but got 500");
    }

    @Test
    void rendererFormatsAreDistinct() {
        assertThat(new JsonRenderer().format()).isEqualTo("json");
        assertThat(new ConsoleRenderer().format()).isEqualTo("console");
        assertThat(new JUnitXmlRenderer().format()).isEqualTo("junit");
        assertThat(new HtmlRenderer().format()).isEqualTo("html");
    }

    @Test
    void allRenderersHandleEmptyEvents() throws IOException {
        List<RunEvent> empty = List.of();

        for (var renderer : List.of(
                new JsonRenderer(), new ConsoleRenderer(),
                new JUnitXmlRenderer(), new HtmlRenderer())) {
            StringWriter out = new StringWriter();
            renderer.render(empty, out);
            assertThat(out.toString()).isNotEmpty();
        }
    }

    @Test
    void allRenderersHandlePassingRun() throws IOException {
        List<RunEvent> passingEvents = List.of(
                new RunEvent.RunStarted("RUN_STARTED", 1000, RUN_ID,
                        "sha256:abc", "sha256:def", 42, Map.of()),
                new RunEvent.NodeStarted("NODE_STARTED", 1100, RUN_ID,
                        NODE_1, "operation", new OperationId("create-payment")),
                new RunEvent.NodeCompleted("NODE_COMPLETED", 1500, RUN_ID,
                        NODE_1, 400, 200, 128),
                new RunEvent.RunCompleted("RUN_COMPLETED", 2000, RUN_ID,
                        1, 1, 0, 1000)
        );

        for (var renderer : List.of(
                new JsonRenderer(), new ConsoleRenderer(),
                new JUnitXmlRenderer(), new HtmlRenderer())) {
            StringWriter out = new StringWriter();
            renderer.render(passingEvents, out);
            String result = out.toString();
            assertThat(result).isNotEmpty();
            if (renderer instanceof ConsoleRenderer) {
                assertThat(result).contains("Result: PASSED");
            }
        }
    }

    // --- Helpers ---

    private List<RunEvent> sampleEvents() {
        return List.of(
                new RunEvent.RunStarted("RUN_STARTED", 1000, RUN_ID,
                        "sha256:abc", "sha256:def", 42, Map.of()),
                new RunEvent.NodeStarted("NODE_STARTED", 1100, RUN_ID,
                        NODE_1, "operation", new OperationId("create-payment")),
                new RunEvent.NodeCompleted("NODE_COMPLETED", 1500, RUN_ID,
                        NODE_1, 400, 201, 256),
                new RunEvent.NodeStarted("NODE_STARTED", 1600, RUN_ID,
                        NODE_2, "assertion", null),
                new RunEvent.AssertionEvaluated("ASSERTION_EVALUATED", 1700, RUN_ID,
                        NODE_2, "status", "FAIL", "Expected 200 but got 500"),
                new RunEvent.NodeFailed("NODE_FAILED", 1800, RUN_ID,
                        NODE_2,
                        new NormalizedError(NormalizedError.ErrorCategory.INTERNAL,
                                "ASSERTION_FAILURE", "Expected 200 but got 500",
                                false, Map.of()),
                        200),
                new RunEvent.RunFailed("RUN_FAILED", 2000, RUN_ID,
                        new NormalizedError(NormalizedError.ErrorCategory.INTERNAL,
                                "ASSERTION_FAILURES", "1 assertion(s) failed",
                                false, Map.of()), 1000)
        );
    }
}
