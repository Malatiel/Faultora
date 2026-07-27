package dev.faultora.reporting;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.events.RunEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The run event stream, folded once into the shape every report needs.
 * <p>
 * Reports are derived artifacts: the journal is the source of truth, and each
 * renderer only decides how to present this projection. Folding the events
 * here rather than in each renderer is what keeps console, HTML, and JUnit
 * output describing the same run — a new event type is understood by all three
 * as soon as it is handled here.
 */
public final class RunSummary {

    private final RunEvent.RunStarted started;
    private final RunEvent.RunCompleted completed;
    private final RunEvent.RunFailed failed;
    private final List<Node> nodes;
    private final List<FaultTimeline.Window> faultWindows;

    private RunSummary(
            RunEvent.RunStarted started,
            RunEvent.RunCompleted completed,
            RunEvent.RunFailed failed,
            List<Node> nodes,
            List<FaultTimeline.Window> faultWindows
    ) {
        this.started = started;
        this.completed = completed;
        this.failed = failed;
        this.nodes = Collections.unmodifiableList(nodes);
        this.faultWindows = faultWindows;
    }

    /** Fold a run's events into the reporting projection. */
    public static RunSummary of(List<RunEvent> events) {
        RunEvent.RunStarted started = null;
        RunEvent.RunCompleted completed = null;
        RunEvent.RunFailed failed = null;

        List<Node> nodes = new ArrayList<>();
        Map<String, Node> byId = new LinkedHashMap<>();
        Map<String, List<Assertion>> pendingAssertions = new HashMap<>();
        Map<String, Integer> retries = new HashMap<>();
        Map<String, Integer> polls = new HashMap<>();
        Map<String, String> generated = new HashMap<>();

        for (RunEvent event : events) {
            switch (event) {
                case RunEvent.RunStarted runStarted -> started = runStarted;
                case RunEvent.RunCompleted runCompleted -> completed = runCompleted;
                case RunEvent.RunFailed runFailed -> failed = runFailed;
                case RunEvent.OperationRetried retried ->
                        retries.merge(retried.nodeId().value(), 1, Integer::sum);
                case RunEvent.ConditionPolled polled ->
                        polls.merge(polled.nodeId().value(), 1, Integer::sum);
                case RunEvent.InputsGenerated inputs -> generated.merge(
                        inputs.nodeId().value(), describe(inputs),
                        (existing, added) -> existing + ", " + added);
                case RunEvent.NodeCompleted nodeCompleted -> {
                    Node node = new Node(
                            nodeCompleted.nodeId().value(), "PASSED",
                            nodeCompleted.durationMs(), nodeCompleted.statusCode(), null);
                    node.addAssertions(pendingAssertions.remove(node.name()));
                    nodes.add(node);
                    byId.put(node.name(), node);
                }
                case RunEvent.NodeFailed nodeFailed -> {
                    Node node = new Node(
                            nodeFailed.nodeId().value(), "FAILED",
                            nodeFailed.durationMs(), -1, nodeFailed.error());
                    node.addAssertions(pendingAssertions.remove(node.name()));
                    nodes.add(node);
                    byId.put(node.name(), node);
                }
                case RunEvent.AssertionEvaluated evaluated -> {
                    // A node may report assertions before or after its own
                    // result, so late arrivals wait for the node to appear.
                    Assertion assertion = new Assertion(
                            evaluated.assertionType(), evaluated.outcome(), evaluated.message());
                    Node node = byId.get(evaluated.nodeId().value());
                    if (node != null) {
                        node.addAssertions(List.of(assertion));
                    } else {
                        pendingAssertions
                                .computeIfAbsent(evaluated.nodeId().value(), key -> new ArrayList<>())
                                .add(assertion);
                    }
                }
                default -> { /* not reflected in reports */ }
            }
        }

        for (Node node : nodes) {
            node.retries = retries.get(node.name());
            node.polls = polls.get(node.name());
            node.generated = generated.get(node.name());
        }

        return new RunSummary(
                started, completed, failed, nodes, FaultTimeline.windows(events));
    }

    /** How a generated input is described in a report. */
    private static String describe(RunEvent.InputsGenerated inputs) {
        String label = inputs.field() + " (" + inputs.strategy() + ")";
        return inputs.violation() == null ? label : label + ": " + inputs.violation();
    }

    public boolean passed() {
        return completed != null;
    }

    /** Whether the run ended with an explicit failure event. */
    public boolean failed() {
        return failed != null;
    }

    public String runId() {
        return started != null ? started.runId().value() : "unknown";
    }

    public String scenarioDigest() {
        return started != null ? started.scenarioDigest() : "";
    }

    public String catalogDigest() {
        return started != null ? started.catalogDigest() : "";
    }

    public long seed() {
        return started != null ? started.seed() : 0;
    }

    public long durationMs() {
        if (completed != null) return completed.durationMs();
        return failed != null ? failed.durationMs() : 0;
    }

    public int totalNodes() {
        return completed != null ? completed.totalNodes() : nodes.size();
    }

    public int passedAssertions() {
        return completed != null ? completed.passedAssertions() : 0;
    }

    public int failedAssertions() {
        return completed != null ? completed.failedAssertions() : 0;
    }

    /** The error that ended the run, or null when it passed. */
    public NormalizedError runError() {
        return failed != null ? failed.error() : null;
    }

    /** Nodes in the order they finished. */
    public List<Node> nodes() {
        return nodes;
    }

    /** Fault windows and the nodes that ran inside them. */
    public List<FaultTimeline.Window> faultWindows() {
        return faultWindows;
    }

    /** One assertion outcome attached to a node. */
    public record Assertion(String assertionType, String outcome, String message) {
        public boolean passed() {
            return "PASS".equals(outcome);
        }
    }

    /**
     * One executed node. A node is failed when it reported a failure or when
     * any of its assertions did not pass.
     */
    public static final class Node {
        private final String name;
        private final List<Assertion> assertions = new ArrayList<>();
        private final long durationMs;
        private final int statusCode;
        private final NormalizedError error;
        private String status;
        private Integer retries;
        private Integer polls;
        private String generated;

        private Node(String name, String status, long durationMs,
                     int statusCode, NormalizedError error) {
            this.name = name;
            this.status = status;
            this.durationMs = durationMs;
            this.statusCode = statusCode;
            this.error = error;
        }

        private void addAssertions(List<Assertion> newAssertions) {
            if (newAssertions == null) {
                return;
            }
            assertions.addAll(newAssertions);
            for (Assertion assertion : newAssertions) {
                if (!assertion.passed()) {
                    status = "FAILED";
                }
            }
        }

        public String name() {
            return name;
        }

        public String status() {
            return status;
        }

        public boolean passed() {
            return "PASSED".equals(status);
        }

        public long durationMs() {
            return durationMs;
        }

        public int statusCode() {
            return statusCode;
        }

        public NormalizedError error() {
            return error;
        }

        public List<Assertion> assertions() {
            return Collections.unmodifiableList(assertions);
        }

        /** Retry count, or null when the node never retried. */
        public Integer retries() {
            return retries;
        }

        /** Poll count, or null when the node is not a polling group. */
        public Integer polls() {
            return polls;
        }

        /**
         * Generated inputs of this node, or null when it sent only values the
         * scenario stated. Naming the strategy — and, for a deliberately
         * invalid payload, the constraint broken — is what makes a run
         * reviewable without the payload itself.
         */
        public String generated() {
            return generated;
        }

        /** Messages of the assertions that did not pass. */
        public List<String> failedAssertionMessages() {
            return assertions.stream()
                    .filter(assertion -> !assertion.passed())
                    .map(assertion -> assertion.message() != null
                            ? assertion.message() : assertion.outcome() + " assertion")
                    .toList();
        }
    }
}
