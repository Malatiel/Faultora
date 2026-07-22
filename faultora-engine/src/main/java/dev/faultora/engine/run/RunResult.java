package dev.faultora.engine.run;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.RunId;
import dev.faultora.spi.result.AssertionResult;

import java.util.List;
import java.util.Map;

/**
 * Final result of a run execution.
 *
 * @param runId              run identifier
 * @param status             overall run status
 * @param totalNodes         total number of nodes executed
 * @param passedAssertions   number of passing assertions
 * @param failedAssertions   number of failing assertions
 * @param nodeResults        per-node execution results
 * @param durationMs         total run duration
 * @param error              error if the run failed
 */
public record RunResult(
        RunId runId,
        Status status,
        int totalNodes,
        int passedAssertions,
        int failedAssertions,
        Map<NodeId, NodeResult> nodeResults,
        long durationMs,
        NormalizedError error
) {
    public enum Status {
        PASSED,
        FAILED,
        ERROR,
        CANCELLED
    }

    /**
     * Result of executing a single node.
     */
    public record NodeResult(
            NodeId nodeId,
            String nodeType,
            Status status,
            int statusCode,
            long durationMs,
            List<AssertionResult> assertions,
            NormalizedError error
    ) {}
}
