package dev.faultora.reporting;

import dev.faultora.model.events.RunEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives fault windows and fault-to-node attribution from the run journal.
 * <p>
 * A node is attributed to a fault window when its execution span overlaps the
 * half-open interval [injection, rollback). Attribution states that the node
 * ran while the fault was active — it does not claim causation.
 */
final class FaultTimeline {

    record Window(
            String handle,
            String faultType,
            String targetScope,
            long injectedAtMs,
            long endAtMs,
            String rollbackStatus,
            List<String> affectedNodes
    ) {}

    private record NodeSpan(String nodeId, long startMs, long endMs) {}

    private FaultTimeline() {
    }

    static List<Window> windows(List<RunEvent> events) {
        Map<String, RunEvent.FaultInjected> injected = new LinkedHashMap<>();
        Map<String, RunEvent.FaultRolledBack> rolledBack = new LinkedHashMap<>();
        List<NodeSpan> spans = new ArrayList<>();
        Map<String, Long> openNodes = new LinkedHashMap<>();

        for (RunEvent event : events) {
            switch (event) {
                case RunEvent.FaultInjected fi -> injected.put(fi.faultHandle(), fi);
                case RunEvent.FaultRolledBack rb -> rolledBack.put(rb.faultHandle(), rb);
                case RunEvent.NodeStarted ns -> {
                    // Only operation-style nodes send traffic worth attributing.
                    if ("operation".equals(ns.nodeType()) || "cleanup".equals(ns.nodeType())) {
                        openNodes.put(ns.nodeId().value(), ns.timestamp());
                    }
                }
                case RunEvent.NodeCompleted nc -> closeSpan(openNodes, spans,
                        nc.nodeId().value(), nc.timestamp());
                case RunEvent.NodeFailed nf -> closeSpan(openNodes, spans,
                        nf.nodeId().value(), nf.timestamp());
                default -> { /* not relevant to the timeline */ }
            }
        }

        List<Window> windows = new ArrayList<>();
        for (RunEvent.FaultInjected fi : injected.values()) {
            RunEvent.FaultRolledBack rb = rolledBack.get(fi.faultHandle());
            long endAtMs = rb != null ? rb.timestamp() : fi.hardExpiryMs();
            String status = rb != null ? rb.rollbackStatus() : "not-rolled-back";

            List<String> affected = new ArrayList<>();
            for (NodeSpan span : spans) {
                long spanEnd = Math.max(span.endMs(), span.startMs() + 1);
                if (span.startMs() < endAtMs && spanEnd > fi.timestamp()) {
                    affected.add(span.nodeId());
                }
            }
            windows.add(new Window(
                    fi.faultHandle(), fi.faultType(), fi.targetScope(),
                    fi.timestamp(), endAtMs, status, List.copyOf(affected)));
        }
        return windows;
    }

    private static void closeSpan(
            Map<String, Long> openNodes, List<NodeSpan> spans, String nodeId, long endMs) {
        Long startMs = openNodes.remove(nodeId);
        if (startMs != null) {
            spans.add(new NodeSpan(nodeId, startMs, endMs));
        }
    }
}
