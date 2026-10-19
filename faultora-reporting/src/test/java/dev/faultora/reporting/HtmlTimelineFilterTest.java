package dev.faultora.reporting;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTML report can be narrowed to the nodes, faults and retries a reader is
 * looking for, without becoming a page that loads anything or runs anything
 * but its own script.
 */
class HtmlTimelineFilterTest {

    private static final RunId RUN_ID = new RunId("filter-run");
    /** Node IDs refuse these characters; a fault type is free text from a provider. */
    private static final String HOSTILE_FAULT =
            "x\"><img src=x onerror=alert(1)></script><script>alert(2)</script>";

    @Test
    void everyNodeAndFaultRowCarriesWhatTheFilterMatches() throws IOException {
        String html = render(events("retried-step", "http-latency"));

        assertThat(row(html, "retried-step"))
                .contains("data-filter=\"retried-step passed http-latency fault-1\"")
                .contains("data-retried=\"true\"", "data-fault=\"true\"", "data-failed=\"false\"");
        assertThat(row(html, "after-fault"))
                .contains("data-retried=\"false\"", "data-fault=\"false\"", "data-failed=\"true\"");
        assertThat(html).contains("data-filter=\"http-latency fault-1 default fault-stop retried-step\"");
    }

    @Test
    void theFilterIsHiddenUntilItsScriptRuns() throws IOException {
        String html = render(events("retried-step", "http-latency"));

        // Without scripts the report reads exactly as before: every row shown,
        // and no control that would do nothing.
        assertThat(html).contains("<section class=\"filter\" id=\"filter\" hidden>");
        assertThat(html).doesNotContain("<tr hidden");
    }

    @Test
    void thePagePermitsOnlyItsOwnStyleAndScript() throws Exception {
        String html = render(events("retried-step", "http-latency"));

        Matcher policy = Pattern.compile(
                "<meta http-equiv=\"Content-Security-Policy\" content=\"([^\"]+)\">").matcher(html);
        assertThat(policy.find()).isTrue();
        String csp = policy.group(1);
        assertThat(csp).contains("default-src 'none'", "base-uri 'none'", "form-action 'none'")
                .doesNotContain("unsafe-inline", "unsafe-eval", "http", "*");
        assertThat(csp).contains("script-src 'sha256-" + sha256(between(html, "<script>", "</script>")) + "'");
        assertThat(csp).contains("style-src 'sha256-" + sha256(between(html, "<style>", "</style>")) + "'");
    }

    @Test
    void theScriptOnlyReadsAttributesAndNeverReachesTheNetwork() throws IOException {
        String script = between(render(events("retried-step", "http-latency")), "<script>", "</script>");

        assertThat(script).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "eval(",
                "Function(", "fetch(", "XMLHttpRequest", "WebSocket", "sendBeacon", "import(",
                "localStorage", "document.write");
        assertThat(script).contains("dataset.filter", "textContent");
    }

    @Test
    void hostileTextStaysTextInsideAttributesAndCells() throws IOException {
        String html = render(events("retried-step", HOSTILE_FAULT));

        assertThat(html).doesNotContain("<img", "<script>alert");
        assertThat(Pattern.compile("<script>").matcher(html).results().count())
                .as("only the report's own script element").isEqualTo(1);
        // In the cell as written, and in the lower-cased filter attribute.
        assertThat(html).contains("x&quot;&gt;&lt;img src=x onerror=alert(1)&gt;"
                + "&lt;/script&gt;&lt;script&gt;alert(2)&lt;/script&gt;");
        assertThat(row(html, "retried-step")).contains("x&quot;&gt;&lt;img");
    }

    @ParameterizedTest
    @ValueSource(strings = {"faultType", "targetScope", "rollbackStatus"})
    void aFaultWithAMissingFieldStillRenders(String missing) throws IOException {
        // The journal contract allows null in these strings: ActiveFault does not
        // require a type or scope, and the reader accepts null in any of them.
        // A filter must not narrow what the journal may contain.
        NodeId node = new NodeId("during-fault");
        String html = render(List.of(
                new RunEvent.RunStarted("RUN_STARTED", 1000, RUN_ID, "sha256:a", "sha256:b", 7, Map.of()),
                new RunEvent.FaultInjected("FAULT_INJECTED", 1100, RUN_ID, "fault-1",
                        missing.equals("faultType") ? null : "http-latency",
                        missing.equals("targetScope") ? null : "default", 61_100),
                new RunEvent.NodeStarted("NODE_STARTED", 1200, RUN_ID, node, "operation",
                        new OperationId("create")),
                new RunEvent.NodeCompleted("NODE_COMPLETED", 1600, RUN_ID, node, 400, 201, 16),
                new RunEvent.FaultRolledBack("FAULT_ROLLED_BACK", 2000, RUN_ID, "fault-1",
                        missing.equals("rollbackStatus") ? null : "fault-stop"),
                new RunEvent.RunCompleted("RUN_COMPLETED", 2300, RUN_ID, 1, 0, 0, 1300)));

        assertThat(html).contains("<h2>Faults</h2>", "</html>");
        assertThat(row(html, "during-fault")).contains("data-fault=\"true\"").doesNotContain("null");
        assertThat(html).contains("data-filter=\"" + (missing.equals("faultType") ? "" : "http-latency ")
                + "fault-1");
    }

    private static String render(List<RunEvent> events) throws IOException {
        StringWriter out = new StringWriter();
        new HtmlRenderer().render(events, out);
        return out.toString();
    }

    private static String row(String html, String nodeName) {
        Matcher matcher = Pattern.compile("<tr data-filter=\"" + Pattern.quote(nodeName) + " [^>]*>")
                .matcher(html);
        assertThat(matcher.find()).as("row for " + nodeName).isTrue();
        return matcher.group();
    }

    private static String between(String html, String open, String close) {
        int start = html.indexOf(open) + open.length();
        return html.substring(start, html.indexOf(close, start));
    }

    private static String sha256(String text) throws Exception {
        return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** One retried node during a fault, then a failed node after the rollback. */
    private static List<RunEvent> events(String retriedNode, String faultType) {
        NodeId first = new NodeId(retriedNode);
        NodeId second = new NodeId("after-fault");
        return List.of(
                new RunEvent.RunStarted("RUN_STARTED", 1000, RUN_ID, "sha256:a", "sha256:b", 7, Map.of()),
                new RunEvent.FaultInjected("FAULT_INJECTED", 1100, RUN_ID,
                        "fault-1", faultType, "default", 61_100),
                new RunEvent.NodeStarted("NODE_STARTED", 1200, RUN_ID, first, "operation",
                        new OperationId("create")),
                new RunEvent.OperationRetried("OPERATION_RETRIED", 1300, RUN_ID, first, 1, 3, 10, "HTTP_503"),
                new RunEvent.NodeCompleted("NODE_COMPLETED", 1600, RUN_ID, first, 400, 201, 16),
                new RunEvent.FaultRolledBack("FAULT_ROLLED_BACK", 2000, RUN_ID, "fault-1", "fault-stop"),
                new RunEvent.NodeStarted("NODE_STARTED", 2100, RUN_ID, second, "operation",
                        new OperationId("list")),
                new RunEvent.NodeFailed("NODE_FAILED", 2200, RUN_ID, second,
                        new NormalizedError(NormalizedError.ErrorCategory.NETWORK, "CONNECTION_REFUSED",
                                "HTTP connection refused", true, Map.of()), 100),
                new RunEvent.RunFailed("RUN_FAILED", 2300, RUN_ID,
                        new NormalizedError(NormalizedError.ErrorCategory.INTERNAL, "NODE_FAILURES",
                                "1 node failed", false, Map.of()), 1300));
    }
}
