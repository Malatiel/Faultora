package dev.faultora.engine.exec;

import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.fault.FaultSession;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.spi.context.ConnectorContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything a node executor needs that does not change between nodes.
 * <p>
 * The expression context is deliberately not part of this: it is an immutable
 * snapshot that grows as steps bind outputs, so it travels as an explicit
 * argument and cannot be mutated behind an executor's back.
 *
 * @param plan             the compiled plan being executed
 * @param journal          writer for run events
 * @param connectorContext deadlines, evidence policy, and secret resolution
 * @param evidence         evidence collected so far, keyed by node
 * @param faults           the run's fault session (rollback obligations)
 * @param schemas          the catalog's schemas, resolved once for the run
 * @param cancellation     cooperative cancellation flag for the whole run
 */
public record NodeContext(
        ExecutionPlan plan,
        JournalWriter journal,
        ConnectorContext connectorContext,
        Map<NodeId, NodeEvidence> evidence,
        FaultSession faults,
        dev.faultora.schema.SchemaCatalog schemas,
        AtomicBoolean cancellation
) {
    /**
     * The same context writing evidence somewhere else — used by parallel
     * groups, whose children collect evidence concurrently before it is merged
     * into the run's map.
     */
    public NodeContext withEvidence(Map<NodeId, NodeEvidence> otherEvidence) {
        return new NodeContext(
                plan, journal, connectorContext, otherEvidence, faults, schemas, cancellation);
    }

    /**
     * The same context under a different cancellation.
     * <p>
     * Used for cleanup, which has to run after the thing that stopped the run —
     * a spent deadline, an expired lease, an operator pressing Ctrl-C. Every
     * node checks the flag it is given, so an obligation discharged through the
     * run's own flag would report itself cancelled and delete nothing.
     */
    public NodeContext withCancellation(AtomicBoolean otherCancellation) {
        return new NodeContext(
                plan, journal, connectorContext, evidence, faults, schemas, otherCancellation);
    }

    public boolean cancelled() {
        return cancellation.get();
    }
}
