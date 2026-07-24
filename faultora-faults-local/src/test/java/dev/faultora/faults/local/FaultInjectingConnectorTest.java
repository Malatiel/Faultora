package dev.faultora.faults.local;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.result.ActiveFault;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FaultInjectingConnectorTest {

    private static final TargetDefinition TARGET = new TargetDefinition(
            new TargetId("payments"), "Payments", "http://example.invalid",
            List.of(new ProtocolId("http")), List.of(), Map.of());

    private static final OperationDefinition OPERATION = new OperationDefinition(
            new OperationId("create-payment"), new ProtocolId("http"),
            new TargetId("payments"), SafetyClassification.MUTATING,
            Map.of(), null, Map.of(), Map.of("method", "POST", "path", "/payments"));

    private final LocalFaultProvider provider = new LocalFaultProvider();

    private FaultContext faultContext(String scope) {
        return new FaultContext(scope, System.currentTimeMillis() + 60_000, Map.of());
    }

    private ConnectorContext connectorContext() {
        EvidencePolicy evidencePolicy = new EvidencePolicy(
                true, true, Set.of(), 1_048_576, 100, List.of(), Set.of(), "session");
        return new ConnectorContext(
                evidencePolicy, handle -> null, 1000, 1000, 1000, Map.of());
    }

    /** Delegate returning a fixed 201 response and counting executions. */
    private static final class StubConnector implements Connector {
        final AtomicInteger executions = new AtomicInteger();
        OperationResult result = OperationResult.success(
                201, Map.of("content-type", List.of("application/json")),
                "{\"id\":\"pay-1\"}".getBytes(StandardCharsets.UTF_8), 5, Map.of());

        @Override
        public ProtocolId protocol() {
            return new ProtocolId("http");
        }

        @Override
        public Set<String> capabilities() {
            return Set.of("http");
        }

        @Override
        public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
            return () -> target;
        }

        @Override
        public OperationResult execute(
                PreparedTarget preparedTarget, OperationDefinition operation,
                Map<String, Object> inputs, ConnectorContext context) {
            executions.incrementAndGet();
            return result;
        }

        @Override
        public void release(PreparedTarget preparedTarget) {
        }

        @Override
        public void close() {
        }
    }

    private OperationResult executeOnce(FaultInjectingConnector connector) {
        var prepared = connector.prepare(TARGET, connectorContext());
        try {
            return connector.execute(prepared, OPERATION, Map.of(), connectorContext());
        } finally {
            connector.release(prepared);
        }
    }

    @Test
    void withoutFaultsRequestsPassThroughUnchanged() {
        StubConnector delegate = new StubConnector();
        FaultInjectingConnector connector = new FaultInjectingConnector(delegate, provider);

        OperationResult result = executeOnce(connector);

        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.error()).isNull();
        assertThat(delegate.executions).hasValue(1);
    }

    @Test
    void latencyFaultDelaysTheRequestAndCountsIntoDuration() {
        StubConnector delegate = new StubConnector();
        FaultInjectingConnector connector = new FaultInjectingConnector(delegate, provider);
        provider.inject("http-latency", Map.of("delayMs", 120), faultContext("payments"));

        long before = System.nanoTime();
        OperationResult result = executeOnce(connector);
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(120);
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(120);
        assertThat(delegate.executions).hasValue(1);
    }

    @Test
    void errorFaultRejectsTheRequestBeforeItIsSent() {
        StubConnector delegate = new StubConnector();
        FaultInjectingConnector connector = new FaultInjectingConnector(delegate, provider);
        provider.inject("http-error", Map.of(), faultContext("payments"));

        OperationResult result = executeOnce(connector);

        assertThat(delegate.executions).hasValue(0);
        assertThat(result.error()).isNotNull();
        assertThat(result.error().code()).isEqualTo("FAULT_INJECTED_ERROR");
        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.NETWORK);
        assertThat(result.error().retryable()).isTrue();
    }

    @Test
    void responseLossDeliversTheRequestButDiscardsTheResponse() {
        StubConnector delegate = new StubConnector();
        FaultInjectingConnector connector = new FaultInjectingConnector(delegate, provider);
        provider.inject("http-response-loss", Map.of(), faultContext("payments"));

        OperationResult result = executeOnce(connector);

        assertThat(delegate.executions).hasValue(1);
        assertThat(result.error()).isNotNull();
        assertThat(result.error().code()).isEqualTo("FAULT_RESPONSE_LOSS");
        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.TIMEOUT);
        assertThat(result.error().metadata()).containsEntry("discardedStatus", 201);
        assertThat(result.body()).isNull();
    }

    @Test
    void responseLossPropagatesDelegateFailuresUnchanged() {
        StubConnector delegate = new StubConnector();
        NormalizedError original = new NormalizedError(
                NormalizedError.ErrorCategory.NETWORK, "CONNECTION_REFUSED",
                "refused", true, Map.of());
        delegate.result = OperationResult.failure(original, 3);
        FaultInjectingConnector connector = new FaultInjectingConnector(delegate, provider);
        provider.inject("http-response-loss", Map.of(), faultContext("payments"));

        OperationResult result = executeOnce(connector);

        assertThat(result.error().code()).isEqualTo("CONNECTION_REFUSED");
    }

    @Test
    void faultsScopedToOtherTargetsDoNotApply() {
        StubConnector delegate = new StubConnector();
        FaultInjectingConnector connector = new FaultInjectingConnector(delegate, provider);
        provider.inject("http-error", Map.of(), faultContext("orders"));

        OperationResult result = executeOnce(connector);

        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(delegate.executions).hasValue(1);
    }

    @Test
    void rolledBackFaultStopsApplying() {
        StubConnector delegate = new StubConnector();
        FaultInjectingConnector connector = new FaultInjectingConnector(delegate, provider);
        ActiveFault fault = provider.inject("http-error", Map.of(), faultContext("payments"));
        provider.rollback(fault, faultContext("payments"));

        OperationResult result = executeOnce(connector);

        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(delegate.executions).hasValue(1);
    }
}
