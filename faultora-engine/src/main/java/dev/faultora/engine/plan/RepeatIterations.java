package dev.faultora.engine.plan;

import dev.faultora.model.identifier.NodeId;

/**
 * How the iterations of a repeat group are named.
 * <p>
 * The naming rule belongs to the plan rather than to the executor, because
 * compilation depends on it too: a generated value is seeded from the node ID
 * it runs under, so the feasibility check has to know the IDs the iterations
 * will use before any of them exists.
 */
public final class RepeatIterations {

    /** Separator between a repeat child's step ID and its iteration index. */
    public static final String SEPARATOR = ":";

    private RepeatIterations() {
    }

    /** Node ID of one child in one iteration, for example {@code create:2}. */
    public static NodeId nodeId(NodeId childId, int index) {
        return new NodeId(name(childId.value(), index));
    }

    /** Node ID as text, for callers that do not hold a typed identifier yet. */
    public static String name(String childId, int index) {
        return childId + SEPARATOR + index;
    }
}
