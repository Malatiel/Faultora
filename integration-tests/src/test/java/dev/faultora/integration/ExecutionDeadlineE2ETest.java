package dev.faultora.integration;

import com.sun.net.httpserver.HttpServer;
import dev.faultora.assertions.core.StatusAssertionProvider;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The engine's remaining budget reaches an actual HTTP connection and body stream. */
@Timeout(12)
class ExecutionDeadlineE2ETest {
    @TempDir
    Path directory;
    private HttpServer server;
    private HttpConnector connector;
    private ExecutorService handlers;
    private String baseUrl;
    private int journalIndex;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch disconnected = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();

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
        server.createContext("/headers", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.createContext("/drip", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                for (int index = 0; index < 100; index++) {
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (IOException stopped) {
                disconnected.countDown();
            }
        });
        server.start();
        connector = new HttpConnector(DestinationPolicy.permissive());
        assertThat(run(5000, operation("/warm", 0, null), 1000).status()).isEqualTo(RunResult.Status.PASSED);
    }

    @AfterEach
    void tearDown() throws Exception {
        release.countDown();
        connector.close();
        server.stop(0);
        handlers.shutdownNow();
        assertThat(handlers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void theRunDeadlineCancelsTheBodyOfItsLastStep() throws Exception {
        long start = System.nanoTime();
        RunResult result = run(300, operation("/drip", 5000, null), 1000);

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.error().code()).isEqualTo("SCENARIO_DEADLINE_EXCEEDED");
        assertThat(requests.get()).isEqualTo(1);
        assertBounded(start);
        assertThat(disconnected.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void thePollingDeadlineCancelsAnOtherwiseSuccessfulBody() throws Exception {
        PlanNode group = new PlanNode.EventuallyNode(new NodeId("eventually"), operation("/drip", 5000, null),
                List.of(new PlanNode.Condition("status", Map.of("expected", 200), null)),
                300, 20, 20, List.of(), SafetyClassification.READ_ONLY);
        long start = System.nanoTime();
        RunResult result = run(5000, group, 1000);

        assertThat(result.nodeResults().get(new NodeId("eventually")).error().code()).isEqualTo("EVENTUALLY_TIMEOUT");
        assertThat(requests.get()).isEqualTo(1);
        assertBounded(start);
        assertThat(disconnected.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void socketTimeoutRetriesCannotRestartTheStepBudget() throws Exception {
        long start = System.nanoTime();
        RunResult result = run(5000, operation("/headers", 300,
                new PlanNode.RetrySpec(20, 0, 1, 0)), 90);

        assertThat(result.status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(result.nodeResults().get(new NodeId("request")).error().code()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(requests.get()).isBetween(2, 4);
        assertBounded(start);
    }

    private PlanNode.OperationNode operation(String path, long timeout, PlanNode.RetrySpec retry) {
        OperationDefinition definition = new OperationDefinition(new OperationId("request"),
                new ProtocolId("http"), new TargetId("test"), SafetyClassification.READ_ONLY,
                Map.of(), null, Map.of(), Map.of("method", "GET", "path", path));
        return new PlanNode.OperationNode(new NodeId("request"), definition.id(), definition, Map.of(),
                null, false, retry, List.of(), SafetyClassification.READ_ONLY, timeout, 0);
    }

    private RunResult run(long timeout, PlanNode node, long requestTimeout) throws Exception {
        PlanNode.OperationNode operation = node instanceof PlanNode.EventuallyNode group ? group.child()
                : (PlanNode.OperationNode) node;
        ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"),
                List.of(new TargetDefinition(new TargetId("test"), "Test", baseUrl,
                        List.of(new ProtocolId("http")), List.of(), Map.of())),
                List.of(operation.operation()), Map.of(), Map.of(), List.of());
        ScenarioDocument scenario = new ScenarioDocument("faultora.dev/v1", "Scenario",
                new ScenarioMetadata("deadline", null, Map.of(), Map.of()), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
        ExecutionPlan plan = ExecutionPlan.builder().runId(new RunId("synthetic"))
                .scenario(scenario).catalog(catalog).scenarioTimeoutMs(timeout)
                .scenarioDigest("synthetic").catalogDigest("synthetic").seed(1)
                .targetPolicy(new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                        100, 2, 5000, 1024, Set.of(), Set.of()))
                .addNode(node).build();
        try (RunJournal journal = new RunJournal(directory.resolve("run-" + (++journalIndex) + ".ndjson"), true)) {
            return new LocalEngine(Map.of("http", connector), Map.of("status", new StatusAssertionProvider()))
                    .execute(plan, journal, ExpressionContext.builder().build(),
                            new ConnectorContext(EvidencePolicy.MINIMAL, key -> null, 1000,
                                    requestTimeout, 5000, Map.of()), new AtomicBoolean());
        }
    }

    private static void assertBounded(long start) {
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1200);
    }
}
