package dev.faultora.engine.plan;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.model.ScenarioDocument;

import java.util.*;

/**
 * An immutable compiled execution plan.
 * Contains the DAG of plan nodes, resolved catalog, and execution policy.
 * Plans are stable and reproducible from the same scenario + catalog + seed.
 */
public final class ExecutionPlan {

    private final RunId runId;
    private final ScenarioDocument scenario;
    private final ApiCatalog catalog;
    private final List<PlanNode> nodes;
    private final Map<NodeId, PlanNode> nodeIndex;
    private final TargetPolicy targetPolicy;
    private final long seed;
    private final String scenarioDigest;
    private final String catalogDigest;
    private final long scenarioTimeoutMs;

    private ExecutionPlan(
            RunId runId,
            ScenarioDocument scenario,
            ApiCatalog catalog,
            List<PlanNode> nodes,
            TargetPolicy targetPolicy,
            long seed,
            String scenarioDigest,
            String catalogDigest,
            long scenarioTimeoutMs
    ) {
        this.runId = runId;
        this.scenario = scenario;
        this.catalog = catalog;
        this.nodes = List.copyOf(nodes);
        this.nodeIndex = new LinkedHashMap<>();
        for (PlanNode node : nodes) {
            nodeIndex.put(node.nodeId(), node);
        }
        this.targetPolicy = targetPolicy;
        this.seed = seed;
        this.scenarioDigest = scenarioDigest;
        this.catalogDigest = catalogDigest;
        this.scenarioTimeoutMs = scenarioTimeoutMs;
    }

    public RunId runId() { return runId; }
    public ScenarioDocument scenario() { return scenario; }
    public ApiCatalog catalog() { return catalog; }
    public List<PlanNode> nodes() { return nodes; }
    public TargetPolicy targetPolicy() { return targetPolicy; }
    public long seed() { return seed; }
    public String scenarioDigest() { return scenarioDigest; }
    public String catalogDigest() { return catalogDigest; }

    /**
     * Scenario deadline in milliseconds from run start (0 = no deadline).
     * Once it elapses the engine starts no further setup, execute, fault, or
     * assertion node and proceeds to cleanup.
     */
    public long scenarioTimeoutMs() { return scenarioTimeoutMs; }

    /**
     * Get a node by its ID.
     */
    public Optional<PlanNode> node(NodeId nodeId) {
        return Optional.ofNullable(nodeIndex.get(nodeId));
    }

    /**
     * Get all nodes in topological order (dependencies before dependents).
     */
    public List<PlanNode> topologicalOrder() {
        return nodes; // Already in topological order from compilation
    }

    /**
     * Get nodes that have no dependencies (entry points).
     */
    public List<PlanNode> entryNodes() {
        return nodes.stream()
                .filter(n -> n.dependencies().isEmpty())
                .toList();
    }

    /**
     * Get direct dependents of a node.
     */
    public List<PlanNode> dependents(NodeId nodeId) {
        return nodes.stream()
                .filter(n -> n.dependencies().contains(nodeId))
                .toList();
    }

    /**
     * Check if the plan has cycles (should never happen after compilation).
     */
    public boolean hasCycles() {
        Set<NodeId> visited = new HashSet<>();
        Set<NodeId> inStack = new HashSet<>();

        for (PlanNode node : nodes) {
            if (hasCycleDFS(node.nodeId(), visited, inStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycleDFS(NodeId current, Set<NodeId> visited, Set<NodeId> inStack) {
        if (inStack.contains(current)) return true;
        if (visited.contains(current)) return false;

        visited.add(current);
        inStack.add(current);

        for (NodeId dep : nodeIndex.get(current).dependencies()) {
            if (hasCycleDFS(dep, visited, inStack)) return true;
        }

        inStack.remove(current);
        return false;
    }

    /**
     * Builder for constructing an ExecutionPlan.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private RunId runId;
        private ScenarioDocument scenario;
        private ApiCatalog catalog;
        private final List<PlanNode> nodes = new ArrayList<>();
        private TargetPolicy targetPolicy;
        private long seed;
        private String scenarioDigest;
        private String catalogDigest;
        private long scenarioTimeoutMs;

        public Builder runId(RunId runId) { this.runId = runId; return this; }
        public Builder scenario(ScenarioDocument scenario) { this.scenario = scenario; return this; }
        public Builder catalog(ApiCatalog catalog) { this.catalog = catalog; return this; }
        public Builder targetPolicy(TargetPolicy policy) { this.targetPolicy = policy; return this; }
        public Builder seed(long seed) { this.seed = seed; return this; }
        public Builder scenarioDigest(String digest) { this.scenarioDigest = digest; return this; }
        public Builder catalogDigest(String digest) { this.catalogDigest = digest; return this; }
        public Builder scenarioTimeoutMs(long timeoutMs) {
            this.scenarioTimeoutMs = timeoutMs;
            return this;
        }

        public Builder addNode(PlanNode node) {
            nodes.add(node);
            return this;
        }

        public Builder addNodes(List<PlanNode> nodes) {
            this.nodes.addAll(nodes);
            return this;
        }

        public ExecutionPlan build() {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(scenario, "scenario");
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(targetPolicy, "targetPolicy");
            return new ExecutionPlan(
                    runId, scenario, catalog, nodes, targetPolicy,
                    seed, scenarioDigest, catalogDigest, scenarioTimeoutMs
            );
        }
    }
}
