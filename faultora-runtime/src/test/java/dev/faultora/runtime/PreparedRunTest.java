package dev.faultora.runtime;

import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.context.ConnectorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreparedRunTest {
    @TempDir Path directory;
    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata: {name: sealed}
            execute:
              - id: pause
                type: wait
                timeout: 1ms
            """;
    private static final TargetPolicy POLICY = new TargetPolicy(
            Set.of(), Set.of(SafetyClassification.READ_ONLY), 10, 1, 1000, 1024, Set.of(), Set.of());
    private static final ExtensionPolicy EXTENSIONS =
            new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());

    private RunPreparation.Request request() {
        return new RunPreparation.Request(SCENARIO, List.of(), "http://api.example.com",
                EXTENSIONS, POLICY, new RunId("synthetic-run"), 42, null);
    }

    private PreparedRun.Settings settings() {
        return new PreparedRun.Settings(ExpressionContext.builder().inputs(Map.of("value", "approved")).build(),
                new ConnectorContext(EvidencePolicy.MINIMAL, ignored -> {
                    throw new AssertionError("Preparation must not resolve secrets");
                }, 100, 200, 300, Map.of()), false, Set.of());
    }

    private void changed(PreparedRun prepared, RunPreparation.Request request, PreparedRun.Settings settings) {
        assertThatThrownBy(() -> prepared.requireCurrent(request, settings))
                .isInstanceOfSatisfying(PreparedRun.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(PreparedRun.Refusal.CHANGED));
    }

    @Test
    void unchangedPreparationExecutesItsPrivatePlanOnce() throws Exception {
        var request = request();
        var settings = settings();
        var prepared = PreparedRun.prepare(request, settings, Duration.ofMinutes(1));
        assertThat(prepared.preview().compiled()).isTrue();
        assertThat(prepared.catalogDigest()).startsWith("sha256:");
        try (var journal = new RunJournal(directory.resolve("events.ndjson"), true)) {
            var result = prepared.execute(request, settings, Map.of(), journal, new AtomicBoolean());
            assertThat(result.status()).isEqualTo(RunResult.Status.PASSED);
            assertThat(result.runId()).isEqualTo(request.runId());
            assertThat(journal.events()).anyMatch(event -> event.eventType().equals("RUN_COMPLETED"));
            assertThatThrownBy(() -> prepared.execute(request, settings, Map.of(), journal, new AtomicBoolean()))
                    .isInstanceOfSatisfying(PreparedRun.Refused.class,
                            refused -> assertThat(refused.reason()).isEqualTo(PreparedRun.Refusal.ALREADY_EXECUTED));
        }
    }

    @Test
    void documentCatalogSeedAndPolicyChangesRequireANewPreview() {
        var original = request();
        var settings = settings();
        var prepared = PreparedRun.prepare(original, settings, Duration.ofMinutes(1));
        List<RunPreparation.Request> changedRequests = List.of(
                new RunPreparation.Request(SCENARIO + "# changed\n", List.of(), original.fallbackTargetUrl(),
                        EXTENSIONS, POLICY, original.runId(), 42, null),
                new RunPreparation.Request(SCENARIO, List.of(), "http://other.example.com",
                        EXTENSIONS, POLICY, original.runId(), 42, null),
                new RunPreparation.Request(SCENARIO, List.of(new CatalogAssembly.Document("openapi", "changed")),
                        original.fallbackTargetUrl(), EXTENSIONS, POLICY, original.runId(), 42, null),
                new RunPreparation.Request(SCENARIO, List.of(), original.fallbackTargetUrl(),
                        EXTENSIONS, POLICY, original.runId(), 43, null),
                new RunPreparation.Request(SCENARIO, List.of(), original.fallbackTargetUrl(),
                        EXTENSIONS, POLICY, original.runId(), 42, "sha256:changed"),
                new RunPreparation.Request(SCENARIO, List.of(), original.fallbackTargetUrl(),
                        EXTENSIONS, new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                        9, 1, 1000, 1024, Set.of(), Set.of()), original.runId(), 42, null),
                new RunPreparation.Request(SCENARIO, List.of(), original.fallbackTargetUrl(),
                        new ExtensionPolicy(Set.of("different"), false, 0, Set.of(), Set.of()),
                        POLICY, original.runId(), 42, null));
        changedRequests.forEach(current -> changed(prepared, current, settings));
        prepared.requireCurrent(original, settings);
    }

    @Test
    void bindingsTargetsEvidenceDeadlinesAndNetworkPermissionsAreBound() {
        var request = request();
        var original = settings();
        var prepared = PreparedRun.prepare(request, original, Duration.ofMinutes(1));
        var connector = original.connectors();
        changed(prepared, request, new PreparedRun.Settings(
                ExpressionContext.builder().inputs(Map.of("value", "changed")).build(), connector, false, Set.of()));
        changed(prepared, request, new PreparedRun.Settings(original.bindings(), connector, true, Set.of()));
        changed(prepared, request, new PreparedRun.Settings(original.bindings(), connector, false, Set.of("fault")));
        changed(prepared, request, new PreparedRun.Settings(original.bindings(),
                new ConnectorContext(connector.evidencePolicy(), connector.secretResolver(), 101, 200, 300, Map.of()),
                false, Set.of()));
        changed(prepared, request, new PreparedRun.Settings(original.bindings(),
                new ConnectorContext(connector.evidencePolicy(), connector.secretResolver(), 100, 200, 300,
                        Map.of("baseUrl", "http://other.example.com")), false, Set.of()));
        changed(prepared, request, new PreparedRun.Settings(original.bindings(),
                new ConnectorContext(RunEvidence.defaultPolicy(), connector.secretResolver(), 100, 200, 300, Map.of()),
                false, Set.of()));
    }

    @Test
    void expiryAtTheExactMonotonicBoundaryPreventsExecution() throws Exception {
        AtomicLong clock = new AtomicLong();
        var request = request();
        var settings = settings();
        var prepared = PreparedRun.prepare(request, settings, Duration.ofMillis(1), clock::get);
        clock.set(999_999);
        prepared.requireCurrent(request, settings);
        clock.set(1_000_000);
        try (var journal = new RunJournal(directory.resolve("expired.ndjson"), true)) {
            assertThatThrownBy(() -> prepared.execute(request, settings, Map.of(), journal, new AtomicBoolean()))
                    .isInstanceOfSatisfying(PreparedRun.Refused.class,
                            refused -> assertThat(refused.reason()).isEqualTo(PreparedRun.Refusal.EXPIRED));
            assertThat(journal.events()).isEmpty();
        }
    }

    @Test
    void mutableCallerViewsCannotRewriteTheCapturedPreparation() throws Exception {
        var request = request();
        var original = settings();
        var config = new LinkedHashMap<String, Object>();
        config.put("nested", new LinkedHashMap<>(Map.of("value", "approved")));
        var mutable = new PreparedRun.Settings(original.bindings(),
                new ConnectorContext(EvidencePolicy.MINIMAL, original.connectors().secretResolver(),
                        100, 200, 300, config), false, Set.of());
        var detached = new PreparedRun.Settings(mutable.bindings().snapshot(),
                new ConnectorContext(EvidencePolicy.MINIMAL, original.connectors().secretResolver(),
                        100, 200, 300, Map.of("nested", Map.of("value", "approved"))), false, Set.of());
        var prepared = PreparedRun.prepare(request, mutable, Duration.ofMinutes(1));
        ((com.fasterxml.jackson.databind.node.ObjectNode) mutable.bindings().tree().path("inputs"))
                .put("value", "changed");
        config.put("nested", Map.of("value", "changed"));
        changed(prepared, request, mutable);
        prepared.requireCurrent(request, detached);
        assertThatThrownBy(() -> prepared.preview().actions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        try (var journal = new RunJournal(directory.resolve("snapshot.ndjson"), true)) {
            assertThat(prepared.execute(request, detached, Map.of(), journal, new AtomicBoolean()).status())
                    .isEqualTo(RunResult.Status.PASSED);
        }
    }

    @Test
    void invalidPlansAndUnboundedLifetimesCannotBePrepared() {
        var bad = new RunPreparation.Request("[invalid", List.of(), "", EXTENSIONS, POLICY,
                new RunId("invalid"), 1, null);
        assertThatThrownBy(() -> PreparedRun.prepare(bad, settings(), Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(PreparedRun.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(PreparedRun.Refusal.INVALID_PLAN));
        for (Duration ttl : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofHours(2))) {
            assertThatThrownBy(() -> PreparedRun.prepare(request(), settings(), ttl))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void mapInsertionOrderDoesNotInvalidateAnEquivalentPreparation() {
        var original = settings();
        var first = new LinkedHashMap<String, Object>();
        first.put("a", 1);
        first.put("b", 2);
        var second = new LinkedHashMap<String, Object>();
        second.put("b", 2);
        second.put("a", 1);
        var left = new PreparedRun.Settings(ExpressionContext.builder().inputs(first).build(),
                original.connectors(), false, Set.of());
        var right = new PreparedRun.Settings(ExpressionContext.builder().inputs(second).build(),
                original.connectors(), false, Set.of());
        var prepared = PreparedRun.prepare(request(), left, Duration.ofMinutes(1));
        prepared.requireCurrent(request(), right);
        assertThat(prepared.preparationDigest())
                .isEqualTo(PreparedRun.prepare(request(), right, Duration.ofMinutes(1)).preparationDigest());
    }
}
