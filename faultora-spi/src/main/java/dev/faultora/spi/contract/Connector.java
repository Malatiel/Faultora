package dev.faultora.spi.contract;

import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.security.SecretHandle;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;

import java.util.Map;
import java.util.Set;

/**
 * Protocol connector for executing operations against targets.
 * Each connector handles one protocol (HTTP, Kafka, gRPC, etc.).
 */
public interface Connector extends AutoCloseable {

    /**
     * The protocol this connector handles.
     */
    ProtocolId protocol();

    /**
     * Capabilities this connector supports.
     */
    Set<String> capabilities();

    /**
     * Prepare a target for operation execution.
     * Resolves credentials, validates connectivity, returns a prepared handle.
     *
     * @param target   target definition from the catalog
     * @param context  connector context with policy and secrets
     * @return opaque prepared target handle
     */
    PreparedTarget prepare(TargetDefinition target, ConnectorContext context);

    /**
     * Execute a single operation against a prepared target.
     *
     * @param preparedTarget handle from prepare()
     * @param operation      operation definition
     * @param inputs         resolved input values
     * @param context        connector context
     * @return operation result with status, evidence, and errors
     */
    OperationResult execute(
            PreparedTarget preparedTarget,
            OperationDefinition operation,
            Map<String, Object> inputs,
            ConnectorContext context
    );

    /**
     * Release resources associated with a prepared target.
     */
    void release(PreparedTarget preparedTarget);

    /**
     * Opaque handle to a prepared target.
     */
    interface PreparedTarget {
        TargetDefinition targetDefinition();
    }
}
