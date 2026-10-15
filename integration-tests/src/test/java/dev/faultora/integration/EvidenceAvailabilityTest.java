package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.assertions.core.*;
import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.exec.StepOutputBinder;
import dev.faultora.engine.exec.OperationInvoker;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.evidence.EvidenceCapture;
import dev.faultora.spi.evidence.MessageEvidence;
import dev.faultora.spi.evidence.TableEvidence;
import dev.faultora.spi.result.AssertionResult;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceAvailabilityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AssertionContext CONTEXT = new AssertionContext("check", Map.of());
    private static final String MARKER = "SYNTHETIC_PRIVATE_EVIDENCE";

    static Stream<Map<String, Object>> jsonChecks() {
        return Stream.of(Map.of("path", "secret", "exists", true),
                Map.of("path", "missing", "exists", false),
                Map.of("path", "secret", "equals", "***"),
                Map.of("path", "length(secret)", "equals", 3),
                Map.of("path", "secret == '***'", "equals", true),
                Map.of("path", "secret", "matches", ".*"),
                Map.of("path", "secret", "type", "string"),
                Map.of("path", "items", "unique", false),
                Map.of("path", "items", "count", 2));
    }

    @ParameterizedTest
    @MethodSource("jsonChecks")
    void redactedDocumentsCannotProveDirectOrDerivedClaims(Map<String, Object> params) {
        NodeEvidence evidence = evidence(policy(true, true, List.of("secret", "items")));
        evidence.body(bytes("{\"secret\":\"" + MARKER + "\",\"items\":[1,2]}"));
        assertThat(evaluate(new JsonPathAssertionProvider(), params, evidence).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        assertThat(new String(evidence.responseBody().orElseThrow(), StandardCharsets.UTF_8)).doesNotContain(MARKER);
    }

    @Test
    void aTruncatedPrefixThatIsStillValidJsonCannotPass() {
        NodeEvidence evidence = evidence(new EvidencePolicy(true, true, Set.of(), 3, 10,
                List.of(), Set.of(), "session"));
        evidence.body(bytes("12345"));
        assertThat(evidence.responseJson().orElseThrow().asInt()).isEqualTo(123);
        assertThat(evaluate(new JsonPathAssertionProvider(), Map.of("path", "@", "equals", 123), evidence).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    @Test
    void schemaCannotValidateRedactionAsAnOrdinaryString() {
        NodeEvidence evidence = evidence(policy(true, true, List.of("secret")));
        evidence.body(bytes("{\"secret\":123}"));
        AssertionResult result = new SchemaAssertionProvider().evaluate("schema", Map.of(), evidence,
                new AssertionContext("check", Map.of(), Map.of("type", "object", "properties",
                        Map.of("secret", Map.of("type", "string")))));
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    @Test
    void withheldAndDisallowedBodiesCannotProveAbsence() {
        for (EvidencePolicy policy : List.of(EvidencePolicy.MINIMAL,
                new EvidencePolicy(true, true, Set.of(), 1024, 10, List.of(), Set.of("text/plain"), "session"))) {
            NodeEvidence evidence = evidence(policy);
            evidence.body(bytes("{\"secret\":1}"), "application/json");
            assertThat(evaluate(new JsonPathAssertionProvider(), Map.of("path", "missing", "exists", false), evidence).outcome())
                    .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        }
    }

    @Test
    void deniedHeaderIsUnknownEvenWhenAnotherHeaderWasCaptured() {
        NodeEvidence evidence = evidence(policy(true, true, List.of()));
        evidence.headers(Map.of("Authorization", List.of(MARKER), "Content-Type", List.of("application/json")));
        for (boolean exists : List.of(true, false)) {
            assertThat(evaluate(new HeaderAssertionProvider(), Map.of("name", "AUTHORIZATION", "exists", exists), evidence).outcome())
                    .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        }
        assertThat(evaluate(new HeaderAssertionProvider(), Map.of("name", "absent", "exists", false), evidence).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(evaluate(new HeaderAssertionProvider(), Map.of("name", "content-TYPE", "equals", "application/json"), evidence).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
        NodeEvidence withheld = evidence(EvidencePolicy.MINIMAL);
        withheld.headers(Map.of("x-value", List.of(MARKER)));
        assertThat(evaluate(new HeaderAssertionProvider(), Map.of("name", "x-value", "exists", false), withheld).outcome())
                .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    @Test
    void rowValuesUseColumnProvenanceWhileCountsAndOtherColumnsStillDecide() {
        NodeEvidence evidence = evidence(policy(true, true, List.of()));
        TableEvidence table = EvidenceCapture.table(List.of("SECRET", "public"),
                List.of(Map.of("SECRET", MARKER, "public", "***")), false,
                policy(true, true, List.of("$.secret.nested")));
        evidence.protocolEvidence(TableEvidence.OBSERVED, table);
        for (AssertionProvider provider : List.of(new RowValueAssertionProvider(),
                new RowUniqueAssertionProvider(), new RowBalanceAssertionProvider())) {
            assertThat(evaluate(provider, Map.of("column", "SECRET", "equals", "***"), evidence).outcome())
                    .isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        }
        assertThat(evaluate(new RowCountAssertionProvider(), Map.of("equals", 1), evidence).outcome()).isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(evaluate(new RowValueAssertionProvider(), Map.of("column", "public", "equals", "***"), evidence).outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void mixedReadableAndWithheldMessagesCannotProveASequenceDidNotHappen() throws Exception {
        NodeEvidence evidence = evidence(policy(true, true, List.of()));
        MessageEvidence readable = message("first", MAPPER.readTree("{\"status\":\"started\"}"), true);
        MessageEvidence hidden = message("second", MAPPER.readTree("{\"status\":\"***\"}"), false);
        evidence.protocolEvidence(MessageEvidence.OBSERVED, List.of(readable, hidden));
        for (boolean ordered : List.of(true, false)) {
            assertThat(evaluate(new EventSequenceAssertionProvider(), Map.of("ordered", ordered,
                    "of", List.of(Map.of("payload:status", "completed"))), evidence).outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        }
        for (AssertionProvider provider : List.of(new EventCorrelationAssertionProvider(), new EventUniqueAssertionProvider())) {
            assertThat(evaluate(provider, Map.of("by", "payload:status"), evidence).outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
            assertThat(evaluate(provider, Map.of("by", "header:authorization"), evidence).outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        }
        assertThat(evaluate(new EventUniqueAssertionProvider(), Map.of("by", "key"), evidence).outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void outputBindingsDoNotTurnMasksIntoOrdinaryComparisonParameters() throws Exception {
        EvidencePolicy policy = policy(true, true, List.of("secret"));
        NodeEvidence evidence = evidence(policy);
        evidence.body(bytes("{\"secret\":\"" + MARKER + "\"}"));
        evidence.protocolEvidence(TableEvidence.OBSERVED, EvidenceCapture.table(List.of("secret", "public"),
                List.of(Map.of("secret", MARKER, "public", "visible")), false, policy));
        MessageEvidence hidden = message("key", MAPPER.readTree("{\"secret\":\"***\"}"), false);
        evidence.protocolEvidence(MessageEvidence.OBSERVED, List.of(hidden));
        evidence.protocolEvidence(MessageEvidence.PUBLISHED, hidden);
        OperationDefinition definition = new OperationDefinition(new OperationId("read"), new ProtocolId("http"),
                new TargetId("target"), SafetyClassification.READ_ONLY, Map.of(), null, Map.of(), Map.of());
        PlanNode.OperationNode node = new PlanNode.OperationNode(new NodeId("read"), definition.id(), definition,
                Map.of(), "output", List.of(), SafetyClassification.READ_ONLY, 0, 0);
        RunResult.NodeResult result = new RunResult.NodeResult(node.nodeId(), "operation", RunResult.Status.PASSED,
                200, 1, List.of(), null);
        var output = StepOutputBinder.bind(node, result, Map.of(node.nodeId(), evidence),
                ExpressionContext.builder().build()).tree().path("steps").path("output");
        assertThat(output.has("body")).isFalse();
        assertThat(output.at("/protocol/rows/rows/0").has("secret")).isFalse();
        assertThat(output.at("/protocol/rows/rows/0/public").asText()).isEqualTo("visible");
        assertThat(output.at("/protocol/message/payload").isNull()).isTrue();
        assertThat(output.at("/protocol/published/payload").isNull()).isTrue();
    }

    @Test
    void contentTypeIsFoundCaseInsensitivelyBeforeApplyingCapturePolicy() {
        NodeEvidence evidence = evidence(new EvidencePolicy(true, false, Set.of(), 1024, 10,
                List.of(), Set.of("application/json"), "session"));
        OperationInvoker.populateEvidence(evidence, OperationResult.success(200,
                Map.of("Content-Type", List.of("application/json; charset=utf-8")), bytes("{\"value\":1}"), 1, Map.of()));
        assertThat(evidence.bodyComplete()).isTrue();
        assertThat(evaluate(new JsonPathAssertionProvider(), Map.of("path", "value", "equals", 1), evidence).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void literalMaskWithCompleteProvenanceStillComparesAndJsonReadersCannotMutateEvidence() {
        NodeEvidence evidence = evidence(policy(true, true, List.of()));
        evidence.body(bytes("{\"secret\":\"***\"}"));
        assertThat(evaluate(new JsonPathAssertionProvider(), Map.of("path", "secret", "equals", "***"), evidence).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
        ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.responseJson().orElseThrow()).put("secret", MARKER);
        assertThat(evidence.responseJson().orElseThrow().path("secret").asText()).isEqualTo("***");
    }

    private static MessageEvidence message(String key, com.fasterxml.jackson.databind.JsonNode payload, boolean complete) {
        return new MessageEvidence("events", 0, 1, 1, key, Map.of("public", "visible"), payload, "sha256:synthetic",
                complete, true, Set.of("authorization"));
    }

    private static AssertionResult evaluate(AssertionProvider provider, Map<String, Object> params, NodeEvidence evidence) {
        return provider.evaluate(provider.type(), params, evidence, CONTEXT);
    }

    private static NodeEvidence evidence(EvidencePolicy policy) { return new NodeEvidence(policy); }

    private static EvidencePolicy policy(boolean bodies, boolean headers, List<String> redacted) {
        return new EvidencePolicy(bodies, headers, Set.of(), 1024, 10, redacted, Set.of(), "session");
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
