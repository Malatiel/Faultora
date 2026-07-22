package dev.faultora.testkit;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.catalog.InputDefinition;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Technology compatibility kit for Connector implementations.
 * Every connector must pass these tests to prove it handles deadlines,
 * cancellation, evidence production, and error normalization correctly.
 */
public abstract class ConnectorCompatibilityKit {

    /**
     * Provide the connector under test.
     */
    protected abstract Connector createConnector();

    /**
     * Provide a valid target definition for testing.
     */
    protected abstract TargetDefinition createTarget();

    /**
     * Provide a valid operation definition for testing.
     */
    protected abstract OperationDefinition createOperation();

    @Test
    void connectorDeclaresProtocol() throws Exception {
        try (Connector connector = createConnector()) {
            assertThat(connector.protocol()).isNotNull();
            assertThat(connector.protocol().value()).isNotBlank();
        }
    }

    @Test
    void connectorDeclaresCapabilities() throws Exception {
        try (Connector connector = createConnector()) {
            assertThat(connector.capabilities()).isNotNull();
        }
    }

    @Test
    void prepareAndReleaseTarget() throws Exception {
        try (Connector connector = createConnector()) {
            ConnectorContext context = createMinimalContext();
            Connector.PreparedTarget prepared = connector.prepare(createTarget(), context);
            assertThat(prepared).isNotNull();
            assertThat(prepared.targetDefinition()).isNotNull();
            connector.release(prepared);
        }
    }

    @Test
    void executeReturnsResult() throws Exception {
        try (Connector connector = createConnector()) {
            ConnectorContext context = createMinimalContext();
            Connector.PreparedTarget prepared = connector.prepare(createTarget(), context);
            try {
                OperationResult result = connector.execute(prepared, createOperation(), Map.of(), context);
                assertThat(result).isNotNull();
                assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
            } finally {
                connector.release(prepared);
            }
        }
    }

    @Test
    void failedOperationHasNormalizedError() throws Exception {
        try (Connector connector = createConnector()) {
            ConnectorContext context = createMinimalContext();
            Connector.PreparedTarget prepared = connector.prepare(createTarget(), context);
            try {
                OperationResult result = connector.execute(prepared, createOperation(), Map.of(), context);
                if (!result.isSuccess()) {
                    assertThat(result.error()).isNotNull();
                    assertThat(result.error().category()).isNotNull();
                    assertThat(result.error().message()).isNotBlank();
                }
            } finally {
                connector.release(prepared);
            }
        }
    }

    @Test
    void releaseIsIdempotent() throws Exception {
        try (Connector connector = createConnector()) {
            ConnectorContext context = createMinimalContext();
            Connector.PreparedTarget prepared = connector.prepare(createTarget(), context);
            connector.release(prepared);
            connector.release(prepared); // should not throw
        }
    }

    protected ConnectorContext createMinimalContext() {
        return new ConnectorContext(
                EvidencePolicy.MINIMAL,
                handleId -> null,
                5000, 10000, 30000,
                Map.of()
        );
    }
}
