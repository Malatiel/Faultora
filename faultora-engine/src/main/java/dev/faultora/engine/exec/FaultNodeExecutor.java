package dev.faultora.engine.exec;

import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.fault.FaultSession;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.result.ActiveFault;

import java.util.Map;
import java.util.Optional;

/**
 * Starts and stops injected faults.
 * <p>
 * The rollback obligation is registered by the fault session before activation
 * is reported successful, so a fault can never outlive the run that created it
 * even if this executor is interrupted immediately afterwards.
 */
final class FaultNodeExecutor {

    private final Map<String, FaultProvider> providers;

    FaultNodeExecutor(Map<String, FaultProvider> providers) {
        this.providers = providers;
    }

    /** @return a failed result, or empty when the node succeeded */
    Optional<RunResult.NodeResult> start(
            PlanNode.FaultStartNode node,
            NodeContext context,
            NodeEvidence evidence,
            long startedAtMs
    ) {
        FaultProvider provider = providerFor(node.faultType());
        if (provider == null) {
            return Optional.of(NodeResults.failed(node,
                    "No fault provider supports fault type: " + node.faultType(),
                    NormalizedError.ErrorCategory.VALIDATION, startedAtMs));
        }
        ActiveFault fault;
        try {
            fault = context.faults().start(
                    provider, node.nodeId(), node.faultType(),
                    node.targetScope(), node.params(), node.durationMs());
        } catch (IllegalArgumentException rejected) {
            return Optional.of(NodeResults.failed(node,
                    "Fault injection rejected: " + rejected.getMessage(),
                    NormalizedError.ErrorCategory.VALIDATION, startedAtMs));
        }
        context.journal().faultInjected(fault);
        evidence.durationMs(System.currentTimeMillis() - startedAtMs);
        return Optional.empty();
    }

    /** @return a failed result, or empty when the node succeeded */
    Optional<RunResult.NodeResult> stop(
            PlanNode.FaultStopNode node,
            NodeContext context,
            NodeEvidence evidence,
            long startedAtMs
    ) {
        String handle = context.faults().handleForNode(node.faultStartNode());
        if (handle == null) {
            return Optional.of(NodeResults.failed(node,
                    "No fault was started by node: " + node.faultStartNode().value(),
                    NormalizedError.ErrorCategory.VALIDATION, startedAtMs));
        }
        // Idempotent: an already-expired fault is a successful stop.
        context.faults().rollback(handle, FaultSession.REASON_FAULT_STOP);
        evidence.durationMs(System.currentTimeMillis() - startedAtMs);
        return Optional.empty();
    }

    private FaultProvider providerFor(String faultType) {
        for (FaultProvider provider : providers.values()) {
            if (provider.capabilities().contains(faultType)) {
                return provider;
            }
        }
        return null;
    }
}
