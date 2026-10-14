package dev.faultora.integration;

import com.sun.net.httpserver.HttpServer;
import dev.faultora.connector.http.HttpConnector;
import dev.faultora.connector.http.DestinationPolicy;
import dev.faultora.engine.LocalEngine;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.*;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.reporting.RunSummary;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioMetadata;
import dev.faultora.spi.context.ConnectorContext;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP hops and connector attempts are different, observable units. */
@Timeout(15)
class HttpRequestBudgetE2ETest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void redirectsStayWithinOneAttemptAndTheNextOperationNeedsAnotherPermit(int limit) throws Exception {
        AtomicInteger hops = new AtomicInteger();
        AtomicInteger next = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            try (exchange) {
                hops.incrementAndGet();
                exchange.getResponseHeaders().set("Location", "/final");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/final", exchange -> {
            try (exchange) { hops.incrementAndGet(); exchange.sendResponseHeaders(204, -1); }
        });
        server.createContext("/next", exchange -> {
            try (exchange) { next.incrementAndGet(); exchange.sendResponseHeaders(204, -1); }
        });
        server.start();
        try (HttpConnector connector = new HttpConnector(DestinationPolicy.permissive());
             RunJournal journal = new RunJournal(directory.resolve("events.ndjson"), true)) {
            TargetDefinition target = new TargetDefinition(new TargetId("test"), "Synthetic HTTP",
                    "http://127.0.0.1:" + server.getAddress().getPort(), List.of(new ProtocolId("http")), List.of(), Map.of());
            OperationDefinition redirected = operation("redirect", "/redirect");
            OperationDefinition following = operation("next", "/next");
            ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"), List.of(target),
                    List.of(redirected, following), Map.of(), Map.of(), List.of());
            // Bypass static admission deliberately to exercise the runtime guard.
            ExecutionPlan plan = ExecutionPlan.builder().runId(new RunId("synthetic-budget"))
                    .scenario(new ScenarioDocument("faultora.dev/v1", "Scenario",
                            new ScenarioMetadata("synthetic-budget", null, Map.of(), Map.of()), Map.of(),
                            List.of(), List.of(), List.of(), List.of(), List.of(), null))
                    .catalog(catalog).seed(42).scenarioDigest("synthetic-scenario").catalogDigest("synthetic-catalog")
                    .targetPolicy(new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                            limit, 1, 10_000, 1024, Set.of(), Set.of()))
                    .addNodes(List.of(node(redirected), node(following))).build();
            RunResult result = new LocalEngine(Map.of("http", connector), Map.of()).execute(plan, journal,
                    ExpressionContext.builder().build(),
                    new ConnectorContext(EvidencePolicy.MINIMAL, key -> null, 1000, 2000, 5000, Map.of()),
                    new AtomicBoolean());
            assertThat(result.nodeResults().get(new NodeId("redirect")).status()).isEqualTo(RunResult.Status.PASSED);
            assertThat(result.status()).isEqualTo(limit == 1 ? RunResult.Status.FAILED : RunResult.Status.PASSED);
            assertThat(hops.get()).isEqualTo(2);
            assertThat(next.get()).isEqualTo(limit - 1);
            assertThat(RunSummary.of(journal.events()).connectorAttempts()).isEqualTo(limit);
            assertThat(journal.events().stream().filter(RunEvent.OperationAttempted.class::isInstance)).hasSize(limit);
            if (limit == 1) {
                assertThat(result.nodeResults().get(new NodeId("next")).error().code()).isEqualTo("REQUEST_BUDGET_EXCEEDED");
            }
        } finally { server.stop(0); }
    }

    private static OperationDefinition operation(String id, String path) {
        return new OperationDefinition(new OperationId(id), new ProtocolId("http"), new TargetId("test"),
                SafetyClassification.READ_ONLY, Map.of(), null, Map.of(), Map.of("method", "GET", "path", path));
    }

    private static PlanNode.OperationNode node(OperationDefinition definition) {
        return new PlanNode.OperationNode(new NodeId(definition.id().value()), definition.id(), definition,
                Map.of(), null, List.of(), SafetyClassification.READ_ONLY, 0, 0);
    }
}
