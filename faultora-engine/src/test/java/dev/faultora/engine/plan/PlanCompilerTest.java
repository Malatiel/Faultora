package dev.faultora.engine.plan;

import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.model.*;
import dev.faultora.spec.parser.ParseResult;
import dev.faultora.spec.parser.ScenarioParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlanCompilerTest {

    private PlanCompiler compiler;
    private ApiCatalog catalog;
    private TargetPolicy policy;

    @BeforeEach
    void setUp() {
        compiler = new PlanCompiler();

        // Build a test catalog
        catalog = new ApiCatalog(
                new CatalogVersion("v1alpha1-test"),
                List.of(
                        new TargetDefinition(
                                new TargetId("default"), "Default", "http://localhost:8080",
                                List.of(new ProtocolId("http")), List.of(), Map.of()
                        )
                ),
                List.of(
                        new OperationDefinition(
                                new OperationId("create-payment"),
                                new ProtocolId("http"),
                                new TargetId("default"),
                                SafetyClassification.MUTATING,
                                Map.of(
                                        "amount", new InputDefinition("amount", InputDefinition.InputLocation.BODY, true, null, null, Map.of()),
                                        "currency", new InputDefinition("currency", InputDefinition.InputLocation.BODY, true, null, null, Map.of())
                                ),
                                null, Map.of("201", new SchemaId("Payment")),
                                Map.of("method", "POST", "path", "/payments")
                        ),
                        new OperationDefinition(
                                new OperationId("get-payment"),
                                new ProtocolId("http"),
                                new TargetId("default"),
                                SafetyClassification.READ_ONLY,
                                Map.of("paymentId", new InputDefinition("paymentId", InputDefinition.InputLocation.PATH, true, null, null, Map.of())),
                                null, Map.of("200", new SchemaId("Payment")),
                                Map.of("method", "GET", "path", "/payments/{paymentId}")
                        ),
                        new OperationDefinition(
                                new OperationId("log-cleanup"),
                                new ProtocolId("http"),
                                new TargetId("default"),
                                SafetyClassification.READ_ONLY,
                                Map.of(),
                                null, Map.of(),
                                Map.of("method", "POST", "path", "/log")
                        )
                ),
                Map.of(),
                Map.of(),
                List.of()
        );

        policy = new TargetPolicy(
                Set.of(),
                Set.of(SafetyClassification.READ_ONLY, SafetyClassification.MUTATING),
                1000, 10, 300000, 1048576,
                Set.of(), Set.of()
        );
    }

    @Test
    void compileValidScenario() {
        ScenarioDocument scenario = buildTestScenario();
        PlanCompilationResult result = compiler.compile(
                scenario, catalog, policy,
                new RunId("run-001"), 42L,
                "sha256:abc", "sha256:def"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.plan()).isNotNull();
        assertThat(result.plan().nodes()).isNotEmpty();
    }

    @Test
    void compileProducesOperationNodes() {
        ScenarioDocument scenario = buildTestScenario();
        PlanCompilationResult result = compiler.compile(
                scenario, catalog, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );

        List<PlanNode.OperationNode> opNodes = result.plan().nodes().stream()
                .filter(n -> n instanceof PlanNode.OperationNode)
                .map(n -> (PlanNode.OperationNode) n)
                .toList();

        assertThat(opNodes).isNotEmpty();
    }

    @Test
    void compileProducesAssertionNodes() {
        ScenarioDocument scenario = buildTestScenario();
        PlanCompilationResult result = compiler.compile(
                scenario, catalog, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );

        List<PlanNode.AssertionNode> assertionNodes = result.plan().nodes().stream()
                .filter(n -> n instanceof PlanNode.AssertionNode)
                .map(n -> (PlanNode.AssertionNode) n)
                .toList();

        assertThat(assertionNodes).hasSize(2);
    }

    @Test
    void compileRejectsUnknownOperation() {
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("test", "desc", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep("step-1", "operation", "nonexistent-op",
                        Map.of(), null, List.of(), null, null, Map.of())),
                List.of(),
                List.of(),
                List.of()
        );

        PlanCompilationResult result = compiler.compile(
                scenario, catalog, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().stream().map(PlanDiagnostic::message))
                .anyMatch(m -> m.contains("not found in catalog"));
    }

    @Test
    void compileRejectsUnsafeOperation() {
        // Policy only allows READ_ONLY and MUTATING, not DESTRUCTIVE
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("test", "desc", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep("step-1", "operation", "delete-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                List.of(),
                List.of(),
                List.of()
        );

        // Add a destructive operation to catalog
        ApiCatalog catalogWithDelete = new ApiCatalog(
                catalog.version(),
                catalog.targets(),
                java.util.stream.Stream.concat(
                        catalog.operations().stream(),
                        java.util.stream.Stream.of(new OperationDefinition(
                                new OperationId("delete-payment"),
                                new ProtocolId("http"),
                                new TargetId("default"),
                                SafetyClassification.DESTRUCTIVE,
                                Map.of(), null, Map.of(),
                                Map.of("method", "DELETE", "path", "/payments/{id}")
                        ))
                ).toList(),
                catalog.schemas(), catalog.authentication(), catalog.workflows()
        );

        PlanCompilationResult result = compiler.compile(
                scenario, catalogWithDelete, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().stream().map(PlanDiagnostic::message))
                .anyMatch(m -> m.contains("not allowed by execution policy"));
    }

    @Test
    void compileResolvesDependencies() {
        ScenarioDocument scenario = buildTestScenario();
        PlanCompilationResult result = compiler.compile(
                scenario, catalog, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );

        // The execute step depends on the setup step
        PlanNode executeNode = result.plan().node(new NodeId("duplicate-request")).orElse(null);
        assertThat(executeNode).isNotNull();
        assertThat(executeNode.dependencies()).contains(new NodeId("create-payment"));
    }

    @Test
    void compileIsDeterministic() {
        ScenarioDocument scenario = buildTestScenario();
        PlanCompilationResult result1 = compiler.compile(
                scenario, catalog, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );
        PlanCompilationResult result2 = compiler.compile(
                scenario, catalog, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );

        assertThat(result1.plan().nodes()).hasSameSizeAs(result2.plan().nodes());
        for (int i = 0; i < result1.plan().nodes().size(); i++) {
            assertThat(result1.plan().nodes().get(i).nodeId())
                    .isEqualTo(result2.plan().nodes().get(i).nodeId());
        }
    }

    @Test
    void compileDetectsCycles() {
        // Create a scenario with circular dependencies
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("test", "desc", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(
                        new ScenarioStep("step-a", "operation", "create-payment",
                                Map.of(), null, List.of("step-b"), null, null, Map.of()),
                        new ScenarioStep("step-b", "operation", "get-payment",
                                Map.of(), null, List.of("step-a"), null, null, Map.of())
                ),
                List.of(),
                List.of(),
                List.of()
        );

        PlanCompilationResult result = compiler.compile(
                scenario, catalog, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors().stream().map(PlanDiagnostic::message))
                .anyMatch(m -> m.contains("cycle"));
    }

    @Test
    void planStableNodeIds() {
        ScenarioDocument scenario = buildTestScenario();
        PlanCompilationResult result = compiler.compile(
                scenario, catalog, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );

        // Node IDs should match step IDs
        assertThat(result.plan().nodes().stream().map(n -> n.nodeId().value()))
                .contains("create-payment", "duplicate-request", "same-status", "same-payment-id");
    }

    @Test
    void planRecordedInResult() {
        ScenarioDocument scenario = buildTestScenario();
        PlanCompilationResult result = compiler.compile(
                scenario, catalog, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );

        assertThat(result.plan().runId().value()).isEqualTo("run-001");
        assertThat(result.plan().seed()).isEqualTo(42L);
        assertThat(result.plan().scenarioDigest()).isEqualTo("sha256:abc");
        assertThat(result.plan().catalogDigest()).isEqualTo("sha256:def");
    }

    @Test
    void compileFromParsedYaml() throws IOException {
        String yaml = loadFixture("/fixtures/spec/valid-scenario.yaml");
        ScenarioParser parser = new ScenarioParser();
        ParseResult<ScenarioDocument> parseResult = parser.parse(yaml);
        assertThat(parseResult.isSuccess()).isTrue();

        PlanCompilationResult result = compiler.compile(
                parseResult.document(), catalog, policy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def"
        );

        // Should compile successfully since the fixture uses the operations in our catalog
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.plan().nodes()).isNotEmpty();
    }

    @Test
    void compileRejectsUnsupportedStepType() {
        ScenarioDocument scenario = scenarioWithExecuteStep(new ScenarioStep(
                "script-step", "script", null,
                Map.of(), null, List.of(), null, null, Map.of()));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("Unsupported step type"));
    }

    @Test
    void compileRejectsFaultTypeNotAllowedByPolicy() {
        // The default policy allows no fault types (empty allowlist = none).
        ScenarioDocument scenario = scenarioWithFault(
                new FaultStep("latency", "http-latency", "default",
                        Map.of("delayMs", 100), "1s", List.of(), Map.of()));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .contains("Fault type is not allowed by execution policy: http-latency");
    }

    @Test
    void compileProducesFaultStartNodeWhenPolicyAllows() {
        ScenarioDocument scenario = scenarioWithFault(
                new FaultStep("latency", "http-latency", "default",
                        Map.of("delayMs", 100), "2s", List.of(), Map.of()));

        PlanCompilationResult result = compile(scenario, faultPolicy("http-latency"));

        assertThat(result.isSuccess()).isTrue();
        PlanNode.FaultStartNode fault = (PlanNode.FaultStartNode)
                result.plan().node(new NodeId("latency")).orElseThrow();
        assertThat(fault.faultType()).isEqualTo("http-latency");
        assertThat(fault.targetScope()).isEqualTo("default");
        assertThat(fault.durationMs()).isEqualTo(2000);
        assertThat(fault.params()).containsEntry("delayMs", 100);

        // A fault with no dependencies is ordered before the execute step.
        List<String> order = result.plan().nodes().stream()
                .map(n -> n.nodeId().value()).toList();
        assertThat(order.indexOf("latency")).isLessThan(order.indexOf("execute"));
    }

    @Test
    void compileRejectsFaultWithoutPositiveDuration() {
        ScenarioDocument scenario = scenarioWithFault(
                new FaultStep("latency", "http-latency", "default",
                        Map.of("delayMs", 100), null, List.of(), Map.of()));

        PlanCompilationResult result = compile(scenario, faultPolicy("http-latency"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .contains("Fault step requires a positive duration");
    }

    @Test
    void compileOrdersFaultAfterItsDependencies() {
        // Fault depends on the execute step; the topological sort must move it
        // after that step even though faults compile before the execute section.
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("fault-order", "fault-order", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(
                        new ScenarioStep("warm-up", "operation", "get-payment",
                                Map.of(), null, List.of(), null, null, Map.of()),
                        new ScenarioStep("under-fault", "operation", "create-payment",
                                Map.of(), null, List.of("inject"), null, null, Map.of())),
                List.of(new FaultStep("inject", "http-latency", "default",
                        Map.of("delayMs", 50), "1s", List.of("warm-up"), Map.of())),
                List.of(),
                List.of());

        PlanCompilationResult result = compile(scenario, faultPolicy("http-latency"));

        assertThat(result.isSuccess()).isTrue();
        List<String> order = result.plan().nodes().stream()
                .map(n -> n.nodeId().value()).toList();
        assertThat(order.indexOf("warm-up")).isLessThan(order.indexOf("inject"));
        assertThat(order.indexOf("inject")).isLessThan(order.indexOf("under-fault"));
    }

    @Test
    void compilePropagatesExpectErrorToOperationNode() {
        ScenarioDocument scenario = scenarioWithExecuteStep(new ScenarioStep(
                "execute", "operation", "create-payment",
                Map.of(), null, List.of(), null, null, true, Map.of()));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode.OperationNode node = (PlanNode.OperationNode)
                result.plan().node(new NodeId("execute")).orElseThrow();
        assertThat(node.expectError()).isTrue();
    }

    @Test
    void compileCarriesRetryPolicyIntoOperationNode() {
        ScenarioDocument scenario = scenarioWithExecuteStep(new ScenarioStep(
                "retry", "operation", "create-payment",
                Map.of(), null, List.of(), null,
                new ScenarioStep.RetryPolicy(3, 10, 2, 100),
                Map.of()));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode.OperationNode node = (PlanNode.OperationNode)
                result.plan().node(new NodeId("retry")).orElseThrow();
        assertThat(node.retrySpec()).isNotNull();
        assertThat(node.retrySpec().maxAttempts()).isEqualTo(3);
        assertThat(node.retrySpec().backoffMs()).isEqualTo(10);
        assertThat(node.maxRetries()).isEqualTo(2);
    }

    @Test
    void compileRejectsExcessiveRetryAttempts() {
        ScenarioDocument scenario = scenarioWithExecuteStep(new ScenarioStep(
                "retry", "operation", "create-payment",
                Map.of(), null, List.of(), null,
                new ScenarioStep.RetryPolicy(11, 10, 2, 100),
                Map.of()));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .contains("retry.maxAttempts must not exceed 10");
    }

    @Test
    void compileRejectsRetryOnCleanupSteps() {
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("cleanup-retry", "cleanup-retry", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep("execute", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                List.of(), List.of(),
                List.of(new ScenarioStep("tidy", "operation", "get-payment",
                        Map.of(), null, List.of(), null,
                        new ScenarioStep.RetryPolicy(3, 10, 2, 100), Map.of())));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .contains("Retries are not supported for cleanup steps");
    }

    @Test
    void compileCountsRetryAttemptsAgainstRequestBudget() {
        TargetPolicy twoRequests = new TargetPolicy(
                policy.allowedTargets(),
                policy.allowedOperationClasses(),
                2,
                policy.maxConcurrency(),
                policy.maxDurationMs(),
                policy.maxPayloadBytes(),
                policy.allowedFaultTypes(),
                policy.allowedEnvironments());
        ScenarioDocument scenario = scenarioWithExecuteStep(new ScenarioStep(
                "retry", "operation", "create-payment",
                Map.of(), null, List.of(), null,
                new ScenarioStep.RetryPolicy(3, 10, 2, 100),
                Map.of()));

        PlanCompilationResult result = compile(scenario, twoRequests);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("policy allows 2"));
    }

    @Test
    void compileEnforcesTargetAllowlist() {
        TargetPolicy restricted = new TargetPolicy(
                Set.of(new TargetId("another-target")),
                policy.allowedOperationClasses(),
                policy.maxRequests(),
                policy.maxConcurrency(),
                policy.maxDurationMs(),
                policy.maxPayloadBytes(),
                policy.allowedFaultTypes(),
                policy.allowedEnvironments());
        ScenarioDocument scenario = scenarioWithExecuteStep(new ScenarioStep(
                "execute", "operation", "create-payment",
                Map.of(), null, List.of(), null, null, Map.of()));

        PlanCompilationResult result = compile(scenario, restricted);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("Target is not allowed"));
    }

    @Test
    void compileEnforcesMaximumRequestCount() {
        TargetPolicy oneRequest = new TargetPolicy(
                policy.allowedTargets(),
                policy.allowedOperationClasses(),
                1,
                policy.maxConcurrency(),
                policy.maxDurationMs(),
                policy.maxPayloadBytes(),
                policy.allowedFaultTypes(),
                policy.allowedEnvironments());

        PlanCompilationResult result = compile(buildTestScenario(), oneRequest);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("policy allows 1"));
    }

    @Test
    void assertionWithoutTargetUsesLastExecuteStep() {
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("assertion-default", "assertion-default", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep(
                        "execute", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                List.of(),
                List.of(new AssertionStep(
                        "status", "status", Map.of("expected", 200),
                        null, List.of(), null, Map.of())),
                List.of());

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode.AssertionNode assertion = (PlanNode.AssertionNode)
                result.plan().node(new NodeId("status")).orElseThrow();
        assertThat(assertion.targetNode()).isEqualTo(new NodeId("execute"));
        assertThat(assertion.dependencies()).contains(new NodeId("execute"));
    }

    @Test
    void compileProducesParallelNodeWithCompiledChildren() {
        ScenarioDocument scenario = scenarioWithExecuteStep(parallelStep("race",
                new ScenarioStep("first", "operation", "create-payment",
                        Map.of("amount", 1), null, List.of(), null, null, Map.of()),
                new ScenarioStep("second", "operation", "create-payment",
                        Map.of("amount", 2), null, List.of(), null, null, Map.of())));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode.ParallelNode group = (PlanNode.ParallelNode)
                result.plan().node(new NodeId("race")).orElseThrow();
        assertThat(group.children()).hasSize(2);
        assertThat(group.children().get(0).nodeId().value()).isEqualTo("first");
        assertThat(group.safety()).isEqualTo(SafetyClassification.MUTATING);
        // Children are not standalone plan nodes.
        assertThat(result.plan().node(new NodeId("first"))).isEmpty();
    }

    @Test
    void compileRejectsParallelGroupExceedingConcurrencyPolicy() {
        TargetPolicy oneAtATime = new TargetPolicy(
                policy.allowedTargets(), policy.allowedOperationClasses(),
                policy.maxRequests(), 1,
                policy.maxDurationMs(), policy.maxPayloadBytes(),
                policy.allowedFaultTypes(), policy.allowedEnvironments());
        ScenarioDocument scenario = scenarioWithExecuteStep(parallelStep("race",
                new ScenarioStep("first", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of()),
                new ScenarioStep("second", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of())));

        PlanCompilationResult result = compile(scenario, oneAtATime);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("policy allows concurrency 1"));
    }

    @Test
    void assertionTargetingParallelChildDependsOnTheGroup() {
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("parallel-assert", "parallel-assert", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(parallelStep("race",
                        new ScenarioStep("first", "operation", "create-payment",
                                Map.of(), null, List.of(), null, null, Map.of()),
                        new ScenarioStep("second", "operation", "create-payment",
                                Map.of(), null, List.of(), null, null, Map.of()))),
                List.of(),
                List.of(new AssertionStep("check-first", "status",
                        Map.of("expected", 201), "first", List.of(), null, Map.of())),
                List.of());

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode.AssertionNode assertion = (PlanNode.AssertionNode)
                result.plan().node(new NodeId("check-first")).orElseThrow();
        // Evidence comes from the child; ordering waits for the group.
        assertThat(assertion.targetNode()).isEqualTo(new NodeId("first"));
        assertThat(assertion.dependencies()).contains(new NodeId("race"));
    }

    @Test
    void parallelChildrenCountTowardRequestBudget() {
        TargetPolicy twoRequests = new TargetPolicy(
                policy.allowedTargets(), policy.allowedOperationClasses(),
                2, policy.maxConcurrency(),
                policy.maxDurationMs(), policy.maxPayloadBytes(),
                policy.allowedFaultTypes(), policy.allowedEnvironments());
        ScenarioDocument scenario = scenarioWithExecuteStep(parallelStep("race",
                new ScenarioStep("first", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of()),
                new ScenarioStep("second", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of()),
                new ScenarioStep("third", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of())));

        PlanCompilationResult result = compile(scenario, twoRequests);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("policy allows 2"));
    }

    private ScenarioStep parallelStep(String id, ScenarioStep... children) {
        return new ScenarioStep(id, "parallel", null,
                Map.of(), null, List.of(), null, null, false,
                List.of(children), Map.of());
    }

    @Test
    void compileRepeatGroupWithFixedCount() {
        ScenarioDocument scenario = scenarioWithExecuteStep(repeatStep(3, null));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode.RepeatNode repeat = (PlanNode.RepeatNode) result.plan().nodes().stream()
                .filter(node -> node instanceof PlanNode.RepeatNode)
                .findFirst().orElseThrow();
        assertThat(repeat.iterations()).isEqualTo(3);
        assertThat(repeat.items()).isNull();
        assertThat(repeat.children()).hasSize(1);
    }

    @Test
    void compileRepeatGroupWithForEachItems() {
        ScenarioDocument scenario = scenarioWithExecuteStep(
                repeatStep(null, List.of("EUR", "USD")));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode.RepeatNode repeat = (PlanNode.RepeatNode) result.plan().nodes().stream()
                .filter(node -> node instanceof PlanNode.RepeatNode)
                .findFirst().orElseThrow();
        assertThat(repeat.iterations()).isEqualTo(2);
        assertThat(repeat.items()).containsExactly("EUR", "USD");
    }

    @Test
    void compileRejectsRepeatWithBothCountAndForEach() {
        ScenarioDocument scenario = scenarioWithExecuteStep(repeatStep(2, List.of("EUR")));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("exactly one of count or forEach"));
    }

    @Test
    void compileCountsEveryRepeatIterationAgainstTheRequestBudget() {
        TargetPolicy tightPolicy = new TargetPolicy(
                policy.allowedTargets(), policy.allowedOperationClasses(),
                3, policy.maxConcurrency(), policy.maxDurationMs(),
                policy.maxPayloadBytes(), policy.allowedFaultTypes(),
                policy.allowedEnvironments());
        ScenarioDocument scenario = scenarioWithExecuteStep(repeatStep(5, null));

        PlanCompilationResult result = compile(scenario, tightPolicy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("Plan requires 5 requests"));
    }

    @Test
    void compileEventuallyGroupDerivesThePollBudget() {
        ScenarioDocument scenario = scenarioWithExecuteStep(eventuallyStep("10s", "1s"));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode.EventuallyNode eventually = (PlanNode.EventuallyNode) result.plan().nodes()
                .stream()
                .filter(node -> node instanceof PlanNode.EventuallyNode)
                .findFirst().orElseThrow();
        assertThat(eventually.timeoutMs()).isEqualTo(10_000);
        assertThat(eventually.intervalMs()).isEqualTo(1000);
        assertThat(eventually.maxPolls()).isEqualTo(11);
        assertThat(eventually.conditions()).hasSize(1);
    }

    @Test
    void compileEventuallyGroupDefaultsThePollInterval() {
        ScenarioDocument scenario = scenarioWithExecuteStep(eventuallyStep("5s", null));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode.EventuallyNode eventually = (PlanNode.EventuallyNode) result.plan().nodes()
                .stream()
                .filter(node -> node instanceof PlanNode.EventuallyNode)
                .findFirst().orElseThrow();
        assertThat(eventually.intervalMs()).isEqualTo(ScenarioLimits.DEFAULT_POLL_INTERVAL_MS);
    }

    @Test
    void compileRejectsEventuallyIntervalLongerThanTheBudget() {
        ScenarioDocument scenario = scenarioWithExecuteStep(eventuallyStep("1s", "5s"));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("must not exceed the timeout budget"));
    }

    @Test
    void compileOrdersAssertionsAfterTheGroupThatOwnsTheirTargetStep() {
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("groups", "groups", Map.of(), Map.of()),
                Map.of(), List.of(), List.of(repeatStep(2, null)), List.of(),
                List.of(new AssertionStep("check", "status", Map.of("expected", 201),
                        "create", List.of(), null, Map.of())),
                List.of());

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode.AssertionNode assertion = (PlanNode.AssertionNode) result.plan().nodes().stream()
                .filter(node -> node instanceof PlanNode.AssertionNode)
                .findFirst().orElseThrow();
        assertThat(assertion.dependencies()).contains(new NodeId("batch"));
        assertThat(assertion.targetNode()).isEqualTo(new NodeId("create"));
    }

    @Test
    void compileCarriesTheScenarioDeadlineIntoThePlan() {
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("deadline", "deadline", Map.of(), Map.of()),
                Map.of(), List.of(),
                List.of(new ScenarioStep("step-1", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                List.of(), List.of(), List.of(), "30s");

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.plan().scenarioTimeoutMs()).isEqualTo(30_000);
    }

    @Test
    void compileRejectsAnEventuallyBudgetThatWouldOutgrowThePollCap() {
        ScenarioDocument scenario = scenarioWithExecuteStep(eventuallyStep("30s", "100ms"));

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("the maximum is "
                        + ScenarioLimits.MAX_POLL_ATTEMPTS)
                        && message.contains("raise interval"));
    }

    @Test
    void compileRejectsAnAssertionTargetingAGroupStep() {
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("groups", "groups", Map.of(), Map.of()),
                Map.of(), List.of(), List.of(repeatStep(2, null)), List.of(),
                // No targetStep: the default target is the last execute step,
                // which is the repeat group itself.
                List.of(new AssertionStep("check", "status", Map.of("expected", 201),
                        null, List.of(), null, Map.of())),
                List.of());

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).extracting(PlanDiagnostic::message)
                .anyMatch(message -> message.contains("is a group step, which holds no evidence"));
    }

    @Test
    void compileAttachesADependencyOnAGroupChildToTheGroup() {
        ScenarioDocument scenario = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("groups", "groups", Map.of(), Map.of()),
                Map.of(), List.of(),
                List.of(
                        repeatStep(2, null),
                        new ScenarioStep("after-batch", "operation", "get-payment",
                                Map.of(), null, List.of("create"), null, null, Map.of())),
                List.of(), List.of(), List.of());

        PlanCompilationResult result = compile(scenario, policy);

        assertThat(result.isSuccess()).isTrue();
        PlanNode after = result.plan().node(new NodeId("after-batch")).orElseThrow();
        // "create" runs inside the repeat group, so the engine waits for the
        // group rather than skipping the dependent step.
        assertThat(after.dependencies()).containsExactly(new NodeId("batch"));
    }

    private ScenarioStep repeatStep(Integer count, List<Object> forEach) {
        return new ScenarioStep(
                "batch", "repeat", null, null, null, List.of(), null, null,
                false,
                List.of(new ScenarioStep("create", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                count, forEach, null, null, Map.of());
    }

    private ScenarioStep eventuallyStep(String timeout, String interval) {
        return new ScenarioStep(
                "settled", "eventually", null, null, null, List.of(), timeout, null,
                false,
                List.of(new ScenarioStep("poll", "operation", "get-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                null, null, interval,
                List.of(new ScenarioStep.Condition(
                        "status", Map.of("expected", 200), "settled")),
                Map.of());
    }

    private PlanCompilationResult compile(ScenarioDocument scenario, TargetPolicy targetPolicy) {
        return compiler.compile(
                scenario, catalog, targetPolicy,
                new RunId("run-001"), 42L, "sha256:abc", "sha256:def");
    }

    private TargetPolicy faultPolicy(String... allowedFaultTypes) {
        return new TargetPolicy(
                policy.allowedTargets(),
                policy.allowedOperationClasses(),
                policy.maxRequests(),
                policy.maxConcurrency(),
                policy.maxDurationMs(),
                policy.maxPayloadBytes(),
                Set.of(allowedFaultTypes),
                policy.allowedEnvironments());
    }

    private ScenarioDocument scenarioWithFault(FaultStep fault) {
        return new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("fault", "fault", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep(
                        "execute", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                List.of(fault),
                List.of(),
                List.of());
    }

    private ScenarioDocument scenarioWithExecuteStep(ScenarioStep step) {
        return new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("test", "test", Map.of(), Map.of()),
                Map.of(), List.of(), List.of(step), List.of(), List.of(), List.of());
    }

    private ScenarioDocument buildTestScenario() {
        return new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("duplicate-payment", "Test idempotency", Map.of(), Map.of()),
                Map.of("idempotency-key", new InputDeclaration("string", "Idempotency key", true, null)),
                List.of(
                        new ScenarioStep("create-payment", "operation", "create-payment",
                                Map.of("amount", 100, "currency", "USD"),
                                "firstPayment", List.of(), null, null, Map.of())
                ),
                List.of(
                        new ScenarioStep("duplicate-request", "operation", "create-payment",
                                Map.of("amount", 100, "currency", "USD"),
                                "secondPayment", List.of("create-payment"), null, null, Map.of())
                ),
                List.of(),
                List.of(
                        new AssertionStep("same-status", "status",
                                Map.of("expected", 200), "duplicate-request", List.of(), null, Map.of()),
                        new AssertionStep("same-payment-id", "jsonpath",
                                Map.of("path", "$.id"), "duplicate-request",
                                List.of("duplicate-request"),
                                "Duplicate should return same ID", Map.of())
                ),
                List.of()
        );
    }

    private String loadFixture(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            assertThat(is).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
