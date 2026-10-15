package dev.faultora.connector.http;

import dev.faultora.engine.LocalEngine;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioMetadata;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.result.ActiveFault;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HttpCancellationEngineTest {
    @TempDir Path directory;

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transportCancellationSurvivesEngineCleanupAndFaultRollback(boolean withCleanup) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        CloseableHttpClient client = new CloseableHttpClient() {
            @Override
            protected CloseableHttpResponse doExecute(HttpHost host, ClassicHttpRequest request,
                                                       HttpContext context) throws InterruptedIOException {
                if (request.getPath().equals("/main")) {
                    requests.incrementAndGet();
                    Thread.currentThread().interrupt();
                    assertThat(Thread.interrupted()).isTrue();
                    throw new InterruptedIOException("SYNTHETIC_PRIVATE_FAILURE");
                }
                assertThat(request.getPath()).isEqualTo("/cleanup");
                assertThat(Thread.currentThread().isInterrupted()).isFalse();
                cleanups.incrementAndGet();
                return CloseableHttpResponse.adapt(new BasicClassicHttpResponse(200));
            }

            @Override
            public void close() {
            }

            @Override
            public void close(CloseMode mode) {
            }
        };
        FaultProvider faults = new FaultProvider() {
            @Override
            public Set<String> capabilities() {
                return Set.of("synthetic");
            }

            @Override
            public ActiveFault inject(String type, Map<String, Object> params, FaultContext context) {
                return new ActiveFault("synthetic", type, context.targetScope(),
                        System.currentTimeMillis(), context.hardExpiryMs(), "synthetic rollback");
            }

            @Override
            public void rollback(ActiveFault fault, FaultContext context) {
                assertThat(Thread.currentThread().isInterrupted()).isFalse();
                rollbacks.incrementAndGet();
            }
        };
        Path journalPath = directory.resolve("events.ndjson");
        try (HttpConnector connector = new HttpConnector(DestinationPolicy.permissive(), client);
             RunJournal journal = new RunJournal(journalPath, true)) {
            RunResult result = new LocalEngine(Map.of("http", connector), Map.of(), Map.of("synthetic", faults))
                    .execute(plan(withCleanup), journal, ExpressionContext.builder().build(),
                            new ConnectorContext(EvidencePolicy.MINIMAL, handle -> null,
                                    5000, 30_000, 60_000, Map.of()), new AtomicBoolean());

            assertThat(result.status()).isEqualTo(RunResult.Status.CANCELLED);
            assertThat(Thread.interrupted()).isTrue();
            assertThat(requests.get()).isEqualTo(1);
            assertThat(cleanups.get()).isEqualTo(withCleanup ? 1 : 0);
            assertThat(rollbacks.get()).isEqualTo(1);
        }
        assertThat(Files.readString(journalPath)).contains("FAULT_ROLLED_BACK", "CANCELLED")
                .doesNotContain("SYNTHETIC_PRIVATE_FAILURE", "rollback-failed");
    }

    private static ExecutionPlan plan(boolean cleanup) {
        TargetId target = new TargetId("test");
        OperationDefinition main = operation("main", target);
        OperationDefinition end = operation("cleanup", target);
        ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"), List.of(new TargetDefinition(
                target, "Test", "http://127.0.0.1:1", List.of(new ProtocolId("http")), List.of(), Map.of())),
                List.of(main, end), Map.of(), Map.of(), List.of());
        ScenarioDocument scenario = new ScenarioDocument("faultora.dev/v1", "Scenario",
                new ScenarioMetadata("cancellation", null, Map.of(), Map.of()), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        var builder = ExecutionPlan.builder().runId(new RunId("synthetic"))
                .scenario(scenario).catalog(catalog)
                .targetPolicy(new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                        10, 1, 60_000, 1024, Set.of("synthetic"), Set.of()))
                .addNode(new PlanNode.FaultStartNode(new NodeId("fault"), "synthetic", "test", Map.of(),
                        30_000, List.of(), SafetyClassification.READ_ONLY))
                .addNode(new PlanNode.OperationNode(new NodeId("request"), main.id(), main, Map.of(), null, false,
                        new PlanNode.RetrySpec(3, 0, 1, 0), List.of(), SafetyClassification.READ_ONLY, 0, 0));
        if (cleanup) {
            builder.addNode(new PlanNode.CleanupNode(new NodeId("cleanup"), end.id(), Map.of(), List.of(),
                    SafetyClassification.READ_ONLY, 0, 0));
        }
        return builder.build();
    }

    private static OperationDefinition operation(String id, TargetId target) {
        return new OperationDefinition(new OperationId(id), new ProtocolId("http"), target,
                SafetyClassification.READ_ONLY, Map.of(), null, Map.of(), Map.of("method", "GET", "path", "/" + id));
    }
}
