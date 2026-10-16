package dev.faultora.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioMetadata;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.result.ActiveFault;
import dev.faultora.spi.evidence.MessageEvidence;
import dev.faultora.spi.result.AssertionResult;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceExportBoundaryTest {
    private static final String MARKER = "SYNTHETIC_PRIVATE_EXPORT";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OperationDefinition READ = new OperationDefinition(new OperationId("read"),
            new ProtocolId("http"), new TargetId("test"), SafetyClassification.READ_ONLY,
            Map.of(), null, Map.of(), Map.of());
    @TempDir Path directory;
    private final StringWriter journalText = new StringWriter();

    @ParameterizedTest
    @EnumSource(AssertionResult.Outcome.class)
    void permittedInternalComparisonsKeepTheirOutcomeWithoutExportingProviderText(AssertionResult.Outcome outcome) throws Exception {
        AtomicBoolean compared = new AtomicBoolean();
        AssertionProvider provider = provider(outcome, compared);
        RunResult result = run(new Source(), provider, List.of(operation(), new PlanNode.AssertionNode(
                new NodeId("check"), "synthetic", Map.of("equals", MARKER), operation().nodeId(),
                null, List.of(operation().nodeId()), SafetyClassification.READ_ONLY)));
        assertThat(compared).isTrue();
        assertThat(result.nodeResults().get(new NodeId("check")).assertions().getFirst().outcome()).isEqualTo(outcome);
        assertPrivate(result);
    }

    @ParameterizedTest
    @EnumSource(AssertionResult.Outcome.class)
    void pollingUsesTheSameExportPolicyForEachAttemptAndTerminalResult(AssertionResult.Outcome outcome) throws Exception {
        AtomicBoolean compared = new AtomicBoolean();
        RunResult result = run(new Source(), provider(outcome, compared), List.of(new PlanNode.EventuallyNode(
                new NodeId("poll"), operation(), List.of(new PlanNode.Condition("synthetic", Map.of(), MARKER)),
                5000, 1, 2, List.of(), SafetyClassification.READ_ONLY)));
        assertThat(compared).isTrue();
        assertThat(result.nodeResults().get(new NodeId("poll")).assertions().getFirst().outcome()).isEqualTo(outcome);
        assertThat(result.status()).isEqualTo(outcome == AssertionResult.Outcome.PASS ? RunResult.Status.PASSED : RunResult.Status.FAILED);
        assertPrivate(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepare", "execute", "release", "assertion", "polling-assertion"})
    void exceptionsNeverPromoteProviderValuesIntoDiagnostics(String stage) throws Exception {
        Source source = new Source(); source.throwAt = stage;
        AssertionProvider provider = new AssertionProvider() {
            public String type() {
                return "synthetic";
            }
            public AssertionResult evaluate(String type, Map<String, Object> params, EvidenceView evidence, AssertionContext context) {
                throw new IllegalStateException(MARKER);
            }
        };
        PlanNode check = stage.equals("polling-assertion")
                ? new PlanNode.EventuallyNode(new NodeId("check"), operation(),
                        List.of(new PlanNode.Condition("synthetic", Map.of(), null)), 5000, 1, 1,
                        List.of(), SafetyClassification.READ_ONLY)
                : new PlanNode.AssertionNode(new NodeId("check"), "synthetic", Map.of(), operation().nodeId(), null,
                        List.of(operation().nodeId()), SafetyClassification.READ_ONLY);
        RunResult result = run(source, provider, stage.equals("polling-assertion") ? List.of(check) : List.of(operation(), check));
        assertThat(result.status()).isNotEqualTo(RunResult.Status.PASSED);
        assertPrivate(result);
    }

    @Test
    void faultRejectionDoesNotExportProviderExceptionText() throws Exception {
        RunResult result = run(new Source(), provider(AssertionResult.Outcome.PASS, new AtomicBoolean()),
                List.of(new PlanNode.FaultStartNode(new NodeId("fault"), "synthetic", "test", Map.of(), 100,
                        List.of(), SafetyClassification.READ_ONLY)));
        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.nodeResults().get(new NodeId("fault")).error().message()).contains("Fault injection rejected");
        assertPrivate(result);
    }

    @Test
    void retryCodeAndErrorMetadataAreProjectedBeforeTheirFirstJournalEvent() throws Exception {
        Source source = new Source(); source.fail = true;
        PlanNode.OperationNode retry = new PlanNode.OperationNode(operation().nodeId(), READ.id(), READ, Map.of(), null,
                false, new PlanNode.RetrySpec(2, 0, 1, 0), List.of(), SafetyClassification.READ_ONLY, 0, 0);
        RunResult result = run(source, provider(AssertionResult.Outcome.PASS, new AtomicBoolean()), List.of(retry));
        assertThat(source.calls).hasValue(2);
        assertThat(journalText.toString()).contains("OPERATION_RETRIED", "CONNECTOR_ERROR");
        assertThat(result.nodeResults().get(operation().nodeId()).error().metadata()).containsExactly(Map.entry("maxBytes", 1024));
        assertPrivate(result);
    }

    private RunResult run(Source connector, AssertionProvider provider, List<PlanNode> nodes) throws Exception {
        TargetDefinition target = new TargetDefinition(new TargetId("test"), "Test", "http://example.invalid",
                List.of(new ProtocolId("http")), List.of(), Map.of());
        ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"), List.of(target), List.of(READ), Map.of(), Map.of(), List.of());
        ExecutionPlan plan = ExecutionPlan.builder().runId(new RunId("export"))
                .scenario(new ScenarioDocument("faultora.dev/v1", "Scenario", new ScenarioMetadata("synthetic", null, Map.of(), Map.of()),
                        Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null))
                .catalog(catalog).targetPolicy(new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                        10, 1, 10_000, 1024, Set.of(), Set.of()))
                .scenarioDigest("synthetic-scenario").catalogDigest("synthetic-catalog").addNodes(nodes).build();
        try (RunJournal journal = new RunJournal(directory.resolve("events.ndjson"), true, journalText)) {
            FaultProvider faults = new FaultProvider() {
                public Set<String> capabilities() {
                    return Set.of("synthetic");
                }
                public ActiveFault inject(String type, Map<String, Object> params, FaultContext context) {
                    throw new IllegalArgumentException(MARKER);
                }
                public void rollback(ActiveFault fault, FaultContext context) { }
            };
            RunResult result = new LocalEngine(Map.of("http", connector), Map.of(provider.type(), provider),
                    Map.of("synthetic", faults)).execute(plan, journal,
                    ExpressionContext.builder().build(), new ConnectorContext(new EvidencePolicy(true, true, Set.of(),
                            1024, 10, List.of(), Set.of(), "session"), key -> null, 1000, 1000, 5000, Map.of()), new AtomicBoolean());
            assertThat(MAPPER.writeValueAsString(journal.events())).doesNotContain(MARKER);
            return result;
        }
    }

    private void assertPrivate(RunResult result) throws Exception {
        assertThat(journalText.toString()).doesNotContain(MARKER);
        assertThat(MAPPER.writeValueAsString(result)).doesNotContain(MARKER);
    }

    private static AssertionProvider provider(AssertionResult.Outcome outcome, AtomicBoolean compared) {
        return new AssertionProvider() {
            public String type() {
                return "synthetic";
            }
            public AssertionResult evaluate(String type, Map<String, Object> params, EvidenceView evidence, AssertionContext context) {
                assertThat(evidence.responseJson().orElseThrow().path("value").asText()).isEqualTo(MARKER);
                assertThat(evidence.responseHeaders()).containsEntry("x-private", List.of(MARKER));
                compared.set(true);
                return new AssertionResult(outcome, "actual=" + MARKER, Map.of("actual", MARKER));
            }
        };
    }

    private static PlanNode.OperationNode operation() {
        return new PlanNode.OperationNode(new NodeId("read"), READ.id(), READ, Map.of(), null,
                List.of(), SafetyClassification.READ_ONLY, 0, 0);
    }

    private static class Source implements Connector {
        String throwAt;
        boolean fail;
        AtomicInteger calls = new AtomicInteger();
        public ProtocolId protocol() {
            return new ProtocolId("http");
        }
        public Set<String> capabilities() {
            return Set.of();
        }
        public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
            if ("prepare".equals(throwAt)) {
                throw new IllegalStateException(MARKER);
            }
            return () -> target;
        }
        public OperationResult execute(PreparedTarget target, OperationDefinition operation, Map<String, Object> inputs, ConnectorContext context) {
            calls.incrementAndGet();
            if ("execute".equals(throwAt)) {
                throw new IllegalStateException(MARKER);
            }
            if (fail) { return OperationResult.failure(new NormalizedError(NormalizedError.ErrorCategory.NETWORK, MARKER,
                    MARKER, true, Map.of("url", MARKER, "nested", List.of(MARKER), "maxBytes", 1024)), 1); }
            MessageEvidence published = new MessageEvidence("events", 0, 1, 1, MARKER, Map.of(), null, "sha256:synthetic");
            return OperationResult.success(200, Map.of("content-type", List.of("application/json"), "x-private", List.of(MARKER)),
                    ("{\"value\":\"" + MARKER + "\"}").getBytes(StandardCharsets.UTF_8), 1,
                    Map.of(MessageEvidence.PUBLISHED, published, "unknown", MARKER));
        }
        public void release(PreparedTarget target) {
            if ("release".equals(throwAt)) {
                throw new IllegalStateException(MARKER);
            }
        }
        public void close() { }
    }
}
