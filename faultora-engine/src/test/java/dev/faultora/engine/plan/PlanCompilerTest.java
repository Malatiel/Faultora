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
