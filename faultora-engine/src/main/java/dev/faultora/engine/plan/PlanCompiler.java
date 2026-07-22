package dev.faultora.engine.plan;

import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.model.*;

import java.util.*;

/**
 * Compiles a ScenarioDocument + ApiCatalog into an immutable ExecutionPlan.
 * Resolves scenario references against the catalog, validates operations exist,
 * checks safety classification, and produces a DAG with stable node IDs.
 */
public class PlanCompiler {

    /**
     * Compile a scenario against a catalog.
     *
     * @param scenario        the parsed scenario document
     * @param catalog         the canonical API catalog
     * @param targetPolicy    execution policy constraints
     * @param runId           the run identifier
     * @param seed            random seed for determinism
     * @param scenarioDigest  content digest of the scenario
     * @param catalogDigest   content digest of the catalog
     * @return compilation result with plan or diagnostics
     */
    public PlanCompilationResult compile(
            ScenarioDocument scenario,
            ApiCatalog catalog,
            TargetPolicy targetPolicy,
            RunId runId,
            long seed,
            String scenarioDigest,
            String catalogDigest
    ) {
        List<PlanDiagnostic> diagnostics = new ArrayList<>();
        List<PlanNode> nodes = new ArrayList<>();

        // Build operation lookup
        Map<String, OperationDefinition> operationIndex = new LinkedHashMap<>();
        for (OperationDefinition op : catalog.operations()) {
            operationIndex.put(op.id().value(), op);
        }

        // Track all step IDs for dependency resolution
        Set<String> allStepIds = new LinkedHashSet<>();
        collectStepIds(scenario, allStepIds);

        // Compile setup steps
        compileSteps(scenario.setup(), "setup", operationIndex, targetPolicy, nodes, diagnostics);

        // Compile execute steps
        compileSteps(scenario.execute(), "execute", operationIndex, targetPolicy, nodes, diagnostics);

        // Compile fault steps
        compileFaultSteps(scenario.faults(), nodes, diagnostics);

        // Compile assertion steps
        compileAssertionSteps(scenario.assertions(), nodes, diagnostics);

        // Compile cleanup steps
        compileSteps(scenario.cleanup(), "cleanup", operationIndex, targetPolicy, nodes, diagnostics);

        // Check for cycles
        if (hasCycles(nodes)) {
            diagnostics.add(PlanDiagnostic.error("validation", "",
                    "Execution plan contains a cycle"));
        }

        if (diagnostics.stream().anyMatch(PlanDiagnostic::isError)) {
            return new PlanCompilationResult(null, diagnostics);
        }

        ExecutionPlan plan = ExecutionPlan.builder()
                .runId(runId)
                .scenario(scenario)
                .catalog(catalog)
                .targetPolicy(targetPolicy)
                .seed(seed)
                .scenarioDigest(scenarioDigest)
                .catalogDigest(catalogDigest)
                .addNodes(nodes)
                .build();

        return new PlanCompilationResult(plan, diagnostics);
    }

    private void compileSteps(
            List<ScenarioStep> steps,
            String phase,
            Map<String, OperationDefinition> operationIndex,
            TargetPolicy targetPolicy,
            List<PlanNode> nodes,
            List<PlanDiagnostic> diagnostics
    ) {
        if (steps == null) return;

        for (ScenarioStep step : steps) {
            String stepId = step.id();
            NodeId nodeId = new NodeId(stepId);

            if ("operation".equals(step.type()) || step.type() == null) {
                // Resolve operation
                String opId = step.operationId();
                if (opId == null || opId.isBlank()) {
                    diagnostics.add(PlanDiagnostic.error(phase, stepId,
                            "Step has no operationId"));
                    continue;
                }

                OperationDefinition operation = operationIndex.get(opId);
                if (operation == null) {
                    diagnostics.add(PlanDiagnostic.error(phase, stepId,
                            "Operation not found in catalog: " + opId));
                    continue;
                }

                // Check safety classification against policy
                if (!isAllowedByPolicy(operation.safety(), targetPolicy)) {
                    diagnostics.add(PlanDiagnostic.error(phase, stepId,
                            "Operation " + opId + " with safety " + operation.safety() +
                                    " is not allowed by execution policy"));
                    continue;
                }

                // Parse timeout
                long deadlineMs = parseTimeout(step.timeout());

                // Parse retry policy
                int maxRetries = 0;
                if (step.retry() != null) {
                    maxRetries = step.retry().maxAttempts() - 1;
                }

                // Build input expressions
                Map<String, Object> inputExpressions = step.inputs() != null ?
                        new LinkedHashMap<>(step.inputs()) : Map.of();

                List<NodeId> deps = resolveDependencies(step.dependsOn());

                if ("cleanup".equals(phase)) {
                    nodes.add(new PlanNode.CleanupNode(
                            nodeId, new OperationId(opId), inputExpressions,
                            deps, operation.safety(), deadlineMs, maxRetries
                    ));
                } else {
                    nodes.add(new PlanNode.OperationNode(
                            nodeId, new OperationId(opId), operation,
                            inputExpressions, step.outputAs(),
                            deps, operation.safety(), deadlineMs, maxRetries
                    ));
                }

            } else if ("wait".equals(step.type())) {
                long waitMs = parseTimeout(step.timeout());
                List<NodeId> deps = resolveDependencies(step.dependsOn());
                // Wait is modeled as a no-op operation node
                nodes.add(new PlanNode.OperationNode(
                        nodeId, new OperationId("_wait"), null,
                        Map.of("waitMs", waitMs), step.outputAs(),
                        deps, dev.faultora.model.catalog.SafetyClassification.READ_ONLY, 0, 0
                ));
            } else {
                diagnostics.add(PlanDiagnostic.warning(phase, stepId,
                        "Unknown step type: " + step.type() + ", treating as operation"));
            }
        }
    }

    private void compileFaultSteps(
            List<FaultStep> steps,
            List<PlanNode> nodes,
            List<PlanDiagnostic> diagnostics
    ) {
        if (steps == null) return;

        for (FaultStep step : steps) {
            NodeId startNodeId = new NodeId(step.id());
            NodeId stopNodeId = new NodeId(step.id() + "-stop");

            long durationMs = parseTimeout(step.duration());
            List<NodeId> deps = resolveDependencies(step.dependsOn());

            Map<String, Object> params = step.params() != null ?
                    new LinkedHashMap<>(step.params()) : Map.of();

            // Fault start node
            nodes.add(new PlanNode.FaultStartNode(
                    startNodeId, step.faultType(), step.targetScope(),
                    params, durationMs, deps,
                    SafetyClassification.MUTATING, 0, 0
            ));

            // Fault stop node (depends on start, runs after duration)
            nodes.add(new PlanNode.FaultStopNode(
                    stopNodeId, startNodeId,
                    List.of(startNodeId),
                    SafetyClassification.READ_ONLY, 0, 0
            ));
        }
    }

    private void compileAssertionSteps(
            List<AssertionStep> steps,
            List<PlanNode> nodes,
            List<PlanDiagnostic> diagnostics
    ) {
        if (steps == null) return;

        for (AssertionStep step : steps) {
            NodeId nodeId = new NodeId(step.id());
            NodeId targetNode = step.targetStep() != null ?
                    new NodeId(step.targetStep()) : null;

            List<NodeId> deps = resolveDependencies(step.dependsOn());
            // Add target step as implicit dependency
            if (targetNode != null && !deps.contains(targetNode)) {
                deps = new ArrayList<>(deps);
                deps.add(targetNode);
            }

            Map<String, Object> params = step.params() != null ?
                    new LinkedHashMap<>(step.params()) : Map.of();

            nodes.add(new PlanNode.AssertionNode(
                    nodeId, step.assertionType(), params,
                    targetNode, step.message(), deps,
                    SafetyClassification.READ_ONLY, 0, 0
            ));
        }
    }

    private List<NodeId> resolveDependencies(List<String> dependsOn) {
        if (dependsOn == null || dependsOn.isEmpty()) return List.of();
        return dependsOn.stream().map(NodeId::new).toList();
    }

    private void collectStepIds(ScenarioDocument scenario, Set<String> ids) {
        collectFromSteps(scenario.setup(), ids);
        collectFromSteps(scenario.execute(), ids);
        collectFromSteps(scenario.cleanup(), ids);
        if (scenario.faults() != null) {
            scenario.faults().forEach(f -> ids.add(f.id()));
        }
        if (scenario.assertions() != null) {
            scenario.assertions().forEach(a -> ids.add(a.id()));
        }
    }

    private void collectFromSteps(List<ScenarioStep> steps, Set<String> ids) {
        if (steps == null) return;
        steps.forEach(s -> ids.add(s.id()));
    }

    private boolean isAllowedByPolicy(SafetyClassification safety, TargetPolicy policy) {
        if (policy == null) return true;
        return policy.allowedOperationClasses().isEmpty() ||
                policy.allowedOperationClasses().contains(safety);
    }

    private long parseTimeout(String timeout) {
        if (timeout == null || timeout.isBlank()) return 0;
        try {
            // Support "30s", "5000ms", "1m" formats
            String trimmed = timeout.trim().toLowerCase();
            if (trimmed.endsWith("ms")) {
                return Long.parseLong(trimmed.substring(0, trimmed.length() - 2));
            } else if (trimmed.endsWith("s")) {
                return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 1000;
            } else if (trimmed.endsWith("m")) {
                return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 60000;
            } else {
                return Long.parseLong(trimmed);
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean hasCycles(List<PlanNode> nodes) {
        Map<NodeId, Set<NodeId>> adjacency = new LinkedHashMap<>();
        for (PlanNode node : nodes) {
            adjacency.computeIfAbsent(node.nodeId(), k -> new LinkedHashSet<>());
            for (NodeId dep : node.dependencies()) {
                adjacency.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(node.nodeId());
            }
        }

        Set<NodeId> visited = new HashSet<>();
        Set<NodeId> inStack = new HashSet<>();

        for (NodeId nodeId : adjacency.keySet()) {
            if (dfs(nodeId, adjacency, visited, inStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfs(NodeId current, Map<NodeId, Set<NodeId>> adjacency,
                         Set<NodeId> visited, Set<NodeId> inStack) {
        if (inStack.contains(current)) return true;
        if (visited.contains(current)) return false;

        visited.add(current);
        inStack.add(current);

        Set<NodeId> neighbors = adjacency.getOrDefault(current, Set.of());
        for (NodeId neighbor : neighbors) {
            if (dfs(neighbor, adjacency, visited, inStack)) return true;
        }

        inStack.remove(current);
        return false;
    }
}
