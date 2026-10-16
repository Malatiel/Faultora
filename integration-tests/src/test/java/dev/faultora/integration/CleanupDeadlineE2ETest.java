package dev.faultora.integration;

import com.sun.net.httpserver.HttpServer;
import dev.faultora.connector.http.DestinationPolicy;
import dev.faultora.connector.http.HttpConnector;
import dev.faultora.engine.LocalEngine;
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
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Cleanup's shared budget reaches the HTTP exchange after execution has stopped. */
@Timeout(12)
class CleanupDeadlineE2ETest {
    @TempDir
    Path directory;
    private HttpServer server;
    private ExecutorService handlers;
    private HttpConnector connector;
    private String baseUrl;
    private int journalIndex;
    private final AtomicInteger cleanupRequests = new AtomicInteger();
    private final AtomicInteger afterRequests = new AtomicInteger();
    private final CountDownLatch disconnected = new CountDownLatch(1);
    private final List<Long> cleanupBudgets = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/warm", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.createContext("/after", exchange -> {
            afterRequests.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.createContext("/cleanup", exchange -> {
            cleanupRequests.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                for (int index = 0; index < 100; index++) {
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (IOException stopped) {
                disconnected.countDown();
            }
        });
        server.start();
        connector = new HttpConnector(DestinationPolicy.permissive()) {
            @Override
            public OperationResult execute(PreparedTarget target, OperationDefinition operation,
                                           Map<String, Object> inputs, ConnectorContext context) {
                if (operation.id().value().equals("cleanup")) {
                    cleanupBudgets.add(context.totalTimeoutMs());
                }
                return super.execute(target, operation, inputs, context);
            }
        };
        assertThat(run(5000, new AtomicBoolean(), operation("warm"), List.of(), 5000).status())
                .isEqualTo(RunResult.Status.PASSED);
    }

    @AfterEach
    void tearDown() throws Exception {
        connector.close();
        server.stop(0);
        handlers.shutdownNow();
        assertThat(handlers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aCancelledRunStillStartsCleanupButCancelsItsSlowBodyAtTheSharedDeadline() throws Exception {
        long started = System.nanoTime();
        RunResult result = run(5000, new AtomicBoolean(true), operation("warm"),
                List.of(cleanup("cleanup"), cleanup("after")), 300);

        assertThat(result.status()).isEqualTo(RunResult.Status.CANCELLED);
        assertExpired(result, "cleanup", "after");
        assertThat(cleanupRequests.get()).isEqualTo(1);
        assertThat(afterRequests.get()).isZero();
        assertBounded(started);
        assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(journal()).contains("CLEANUP_COMPLETED", "RUN_FAILED", "CLEANUP_DEADLINE_EXCEEDED");
    }

    @Test
    void anExpiredMainRunDoesNotDisableCleanupOrGiveItsStepsUnboundedTime() throws Exception {
        PlanNode main = new PlanNode.WaitNode(new NodeId("main-wait"), 5000, List.of(),
                SafetyClassification.READ_ONLY);
        long started = System.nanoTime();
        RunResult result = run(80, new AtomicBoolean(), main, List.of(cleanup("cleanup"), cleanup("after")), 300);

        assertThat(result.error().code()).isEqualTo("SCENARIO_DEADLINE_EXCEEDED");
        assertExpired(result, "cleanup", "after");
        assertThat(cleanupRequests.get()).isEqualTo(1);
        assertThat(afterRequests.get()).isZero();
        assertBounded(started);
        assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cleanupWaitsAndHttpOperationsDoNotAcquireSeparateFullBudgets() throws Exception {
        PlanNode wait = new PlanNode.WaitNode(new NodeId("cleanup-wait"), 200, List.of(),
                SafetyClassification.READ_ONLY, true);
        long started = System.nanoTime();
        RunResult result = run(5000, new AtomicBoolean(), operation("warm"),
                List.of(wait, cleanup("cleanup"), cleanup("after")), 1000);

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.nodeResults().get(new NodeId("cleanup-wait")).status()).isEqualTo(RunResult.Status.PASSED);
        assertExpired(result, "cleanup", "after");
        assertThat(cleanupRequests.get()).isEqualTo(1);
        assertThat(afterRequests.get()).isZero();
        assertThat(cleanupBudgets).singleElement().satisfies(budget ->
                assertThat(budget).isPositive().isLessThanOrEqualTo(800));
        assertBounded(started);
        assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private RunResult run(long scenarioMs, AtomicBoolean cancellation, PlanNode main,
                          List<PlanNode> cleanup, long cleanupMs) throws Exception {
        List<OperationDefinition> definitions = List.of(definition("warm"), definition("cleanup"), definition("after"));
        ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"), List.of(new TargetDefinition(
                new TargetId("test"), "Test", baseUrl, List.of(new ProtocolId("http")), List.of(), Map.of())),
                definitions, Map.of(), Map.of(), List.of());
        ScenarioDocument scenario = new ScenarioDocument("faultora.dev/v1", "Scenario",
                new ScenarioMetadata("cleanup", null, Map.of(), Map.of()), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
        ExecutionPlan plan = ExecutionPlan.builder().runId(new RunId("synthetic"))
                .scenario(scenario).catalog(catalog).scenarioTimeoutMs(scenarioMs)
                .scenarioDigest("synthetic").catalogDigest("synthetic").seed(1)
                .targetPolicy(new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                        100, 1, 5000, 1024, Set.of(), Set.of()))
                .addNode(main).addNodes(cleanup).build();
        try (RunJournal journal = new RunJournal(directory.resolve("run-" + (++journalIndex) + ".ndjson"), true)) {
            return new LocalEngine(Map.of("http", connector), Map.of(), Map.of(), cleanupMs)
                    .execute(plan, journal, ExpressionContext.builder().build(),
                            new ConnectorContext(EvidencePolicy.MINIMAL, key -> null,
                                    1000, 1000, 5000, Map.of()), cancellation);
        }
    }

    private String journal() throws IOException {
        return Files.readString(directory.resolve("run-" + journalIndex + ".ndjson"));
    }

    private static PlanNode.OperationNode operation(String id) {
        OperationDefinition definition = definition(id);
        return new PlanNode.OperationNode(new NodeId(id), definition.id(), definition, Map.of(), null, List.of(),
                SafetyClassification.READ_ONLY, 0, 0);
    }

    private static PlanNode.CleanupNode cleanup(String id) {
        return new PlanNode.CleanupNode(new NodeId(id), new OperationId(id), Map.of(), List.of(),
                SafetyClassification.READ_ONLY, 0, 0);
    }

    private static OperationDefinition definition(String id) {
        return new OperationDefinition(new OperationId(id), new ProtocolId("http"), new TargetId("test"),
                SafetyClassification.READ_ONLY, Map.of(), null, Map.of(), Map.of("method", "GET", "path", "/" + id));
    }

    private static void assertExpired(RunResult result, String... ids) {
        for (String id : ids) {
            RunResult.NodeResult node = result.nodeResults().get(new NodeId(id));
            assertThat(node.status()).isEqualTo(RunResult.Status.FAILED);
            assertThat(node.error().code()).isEqualTo("CLEANUP_DEADLINE_EXCEEDED");
        }
    }

    private static void assertBounded(long start) {
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5000);
    }
}
