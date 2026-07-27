package dev.faultora.engine.exec;

import dev.faultora.engine.run.RunResult;
import dev.faultora.model.identifier.NodeId;

import java.util.Map;

/**
 * What a group node produced: its own verdict plus the results of the steps it
 * ran. Children are reported individually so a report can show what actually
 * happened inside the group, not just whether it passed.
 *
 * @param group    verdict of the group node itself
 * @param children results of the steps the group executed, keyed by node ID
 */
public record GroupOutcome(
        RunResult.NodeResult group,
        Map<NodeId, RunResult.NodeResult> children
) {}
