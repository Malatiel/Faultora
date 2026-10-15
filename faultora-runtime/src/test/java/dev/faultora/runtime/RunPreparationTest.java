package dev.faultora.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.faults.local.LocalFaultProvider;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunPreparationTest {
    private static final ExtensionPolicy EXTENSIONS =
            new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());
    private static final TargetPolicy POLICY = new TargetPolicy(
            Set.of(), Set.of(SafetyClassification.READ_ONLY), 50, 2, 10_000, 1024,
            Set.of("http-latency"), Set.of());
    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata: {name: preparation}
            execute:
              - id: read
                type: operation
                operationId: read
            """;

    private static RunPreparation.Result prepare(String scenario, String digest) {
        return RunPreparation.prepare(new RunPreparation.Request(scenario, List.of(),
                "http://api.example.com", EXTENSIONS, POLICY, new RunId("run-preview"), 42, digest));
    }

    @Test
    void sharedPreparationValidatesAssemblesAndCompiles() throws Exception {
        var result = prepare(SCENARIO, null);
        assertThat(result.isSuccess()).isTrue();
        var plan = result.compilation().plan();
        assertThat(plan.runId().value()).isEqualTo("run-preview");
        assertThat(plan.seed()).isEqualTo(42);
        assertThat(plan.scenarioDigest()).isEqualTo(ContentDigest.sha256Uri(SCENARIO));
        assertThat(plan.catalogDigest()).isEqualTo(RunPreparation.digest(result.catalog()));
        assertThat(plan.nodes()).hasSize(1);
        assertThat(plan.targetPolicy()).isEqualTo(POLICY);
    }

    @Test
    void verifiedDispatchCatalogDigestRetainsItsWireMeaning() {
        String digest = ContentDigest.sha256Uri("synthetic document list");
        assertThat(prepare(SCENARIO, digest).compilation().plan().catalogDigest()).isEqualTo(digest);
    }

    @Test
    void parseErrorsStopBeforeCatalogImport() {
        var result = RunPreparation.prepare(new RunPreparation.Request("[invalid", List.of(
                new CatalogAssembly.Document("missing-importer", "invalid")), "", EXTENSIONS,
                POLICY, new RunId("run-invalid"), 1, null));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.diagnostics()).anyMatch(diagnostic -> diagnostic.isError());
        assertThat(result.catalog()).isNull();
        assertThat(result.compilation()).isNull();
    }

    @Test
    void duplicateStepIdsAreRefusedBeforeCompilation() {
        var result = prepare(SCENARIO + "  - id: read\n    type: wait\n    timeout: 1ms\n", null);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.diagnostics()).anyMatch(diagnostic -> diagnostic.message().contains("Duplicate step id"));
        assertThat(result.compilation()).isNull();
    }

    @Test
    void unenforceablePolicyStopsBeforeParsingOrImporting() {
        assertThatThrownBy(() -> RunPreparation.prepare(new RunPreparation.Request("[invalid",
                List.of(new CatalogAssembly.Document("missing", "invalid")), "",
                new ExtensionPolicy(Set.of(), true, 0, Set.of(), Set.of()), POLICY,
                new RunId("run-invalid"), 1, null))).isInstanceOf(UnenforceablePolicy.class);
    }

    @Test
    void previewContainsOperationsFaultsAndBudgetsWithoutRuntimeValues() throws Exception {
        String marker = "SYNTHETIC_PRIVATE_PREVIEW_VALUE";
        String scenario = SCENARIO + """
                    inputs: {body: '%s'}
                faults:
                  - id: latency
                    faultType: http-latency
                    targetScope: '%s'
                    duration: 1s
                    params: {delayMs: 5, privateValue: '%s'}
                """.formatted(marker, marker, marker);
        var result = prepare(scenario, null);
        assertThat(result.isSuccess()).as(() -> result.diagnostics().toString()).isTrue();
        var capabilities = RuntimeCapabilities.describe(EXTENSIONS, POLICY, LocalFaultProvider.supportedFaultTypes());
        var preview = PlanPreview.describe(result, capabilities, POLICY);
        assertThat(preview.compiled()).isTrue();
        assertThat(preview.actions()).anySatisfy(action -> {
            assertThat(action.operationId()).isEqualTo("read");
            assertThat(action.target()).isEqualTo("default");
            assertThat(action.protocol()).isEqualTo("http");
        });
        assertThat(preview.faults()).containsExactly(new PlanPreview.Fault("latency", "http-latency", 1000));
        assertThat(preview.budgets().maxRequests()).isEqualTo(50);
        assertThat(preview.budgets().maxPayloadBytes()).isEqualTo(1024);
        assertThat(new ObjectMapper().writeValueAsString(preview))
                .doesNotContain(marker, "http://api.example.com", "delayMs", "privateValue");
    }

    @Test
    void aValidationRefusalIsVisibleWithoutExportingItsDocumentValue() throws Exception {
        String scenario = SCENARIO + "timeout: SYNTHETIC_PRIVATE_DURATION\n";
        var result = prepare(scenario, null);
        var preview = PlanPreview.describe(result, RuntimeCapabilities.describe(EXTENSIONS, POLICY, Set.of()), POLICY);
        assertThat(preview.compiled()).isFalse();
        assertThat(preview.diagnostics()).anyMatch(diagnostic -> diagnostic.severity().equals("ERROR"));
        assertThat(preview.actions()).isEmpty();
        assertThat(new ObjectMapper().writeValueAsString(preview)).doesNotContain("SYNTHETIC_PRIVATE_DURATION");
    }

    @Test
    void aFaultOutsidePolicyCannotBePreviewedAsCompiled() {
        var result = prepare(SCENARIO + """
                faults:
                  - id: reset
                    faultType: network-reset
                    duration: 1s
                """, null);
        assertThat(result.isSuccess()).isFalse();
        var preview = PlanPreview.describe(result, RuntimeCapabilities.describe(EXTENSIONS, POLICY, Set.of()), POLICY);
        assertThat(preview.compiled()).isFalse();
        assertThat(preview.diagnostics()).anyMatch(diagnostic -> "faults".equals(diagnostic.phase()));
    }

    @Test
    void previewIncludesGroupChildrenAndTheirAttemptMultipliers() {
        String scenario = """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata: {name: groups}
                timeout: 5s
                execute:
                  - id: many
                    type: repeat
                    count: 3
                    steps:
                      - id: repeat-read
                        operationId: read
                        retry: {maxAttempts: 2, backoffMs: 1, backoffMultiplier: 1}
                  - id: together
                    type: parallel
                    steps:
                      - id: parallel-read
                        operationId: read
                  - id: poll
                    type: eventually
                    timeout: 20ms
                    interval: 10ms
                    steps:
                      - id: poll-read
                        operationId: read
                    until:
                      - assertionType: status
                        params: {expected: 200}
                cleanup:
                  - id: final-read
                    operationId: read
                """;
        var prepared = prepare(scenario, null);
        assertThat(prepared.isSuccess()).as(() -> prepared.diagnostics() + " " + prepared.compilation()).isTrue();
        var preview = PlanPreview.describe(prepared, RuntimeCapabilities.describe(EXTENSIONS, POLICY, Set.of()), POLICY);
        assertThat(preview.actions()).anySatisfy(action -> {
            assertThat(action.nodeId()).isEqualTo("repeat-read");
            assertThat(action.maxAttempts()).isEqualTo(2);
            assertThat(action.repetitions()).isEqualTo(3);
        }).anySatisfy(action -> {
            assertThat(action.nodeId()).isEqualTo("poll-read");
            assertThat(action.repetitions()).isEqualTo(3);
        });
        assertThat(preview.actions()).extracting(PlanPreview.Action::kind)
                .contains("repeat", "parallel", "eventually", "cleanup");
        assertThat(preview.actions()).filteredOn(action -> action.kind().equals("cleanup"))
                .singleElement().satisfies(action -> {
                    assertThat(action.target()).isEqualTo("default");
                    assertThat(action.protocol()).isEqualTo("http");
                });
        assertThat(preview.budgets().scenarioTimeoutMs()).isEqualTo(5000);
    }

    @Test
    void requestBudgetRefusalUsesTheEffectivePolicySuppliedToPreparation() {
        var limited = new TargetPolicy(Set.of(), Set.of(), 1, 1, 1000, 128, Set.of(), Set.of());
        var prepared = RunPreparation.prepare(new RunPreparation.Request(
                SCENARIO + "  - id: another\n    operationId: read\n", List.of(), "http://api.example.com",
                EXTENSIONS, limited, new RunId("run-limited"), 42, null));
        var preview = PlanPreview.describe(prepared,
                RuntimeCapabilities.describe(EXTENSIONS, limited, Set.of()), limited);
        assertThat(preview.compiled()).isFalse();
        assertThat(preview.budgets().maxRequests()).isEqualTo(1);
        assertThat(preview.budgets().maxPayloadBytes()).isEqualTo(128);
        assertThat(preview.diagnostics()).anyMatch(diagnostic ->
                diagnostic.severity().equals("ERROR") && diagnostic.phase().equals("policy"));
    }
}
