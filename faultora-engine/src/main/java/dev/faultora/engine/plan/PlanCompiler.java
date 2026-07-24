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

    /** Upper bound on retry attempts per step, preventing retry storms. */
    static final int MAX_RETRY_ATTEMPTS = 10;

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

        // Compile setup steps
        compileSteps(scenario.setup(), "setup", operationIndex, targetPolicy, nodes, diagnostics);

        // Compile fault steps before execute: a fault with no dependencies
        // activates before the first execute step runs.
        compileFaultSteps(scenario.faults(), targetPolicy, nodes, diagnostics);

        // Compile execute steps
        compileSteps(scenario.execute(), "execute", operationIndex, targetPolicy, nodes, diagnostics);

        // Compile assertion steps
        compileAssertionSteps(
                scenario.assertions(), lastStepId(scenario.execute()), nodes, diagnostics);

        // Compile cleanup steps
        compileSteps(scenario.cleanup(), "cleanup", operationIndex, targetPolicy, nodes, diagnostics);

        if (targetPolicy != null) {
            // Retrying nodes count once per allowed attempt, so retries cannot
            // multiply traffic past the policy's request budget.
            long requestCount = nodes.stream()
                    .mapToLong(node -> switch (node) {
                        case PlanNode.OperationNode operation when operation.operation() != null ->
                                operation.retrySpec() == null
                                        ? 1 : operation.retrySpec().maxAttempts();
                        case PlanNode.CleanupNode ignored -> 1;
                        default -> 0;
                    })
                    .sum();
            if (requestCount > targetPolicy.maxRequests()) {
                diagnostics.add(PlanDiagnostic.error(
                        "policy", "",
                        "Plan requires " + requestCount + " requests, policy allows "
                                + targetPolicy.maxRequests()));
            }
        }

        // Check for cycles
        if (hasCycles(nodes)) {
            diagnostics.add(PlanDiagnostic.error("validation", "",
                    "Execution plan contains a cycle"));
        }

        if (diagnostics.stream().anyMatch(PlanDiagnostic::isError)) {
            return new PlanCompilationResult(null, diagnostics);
        }

        // The engine executes nodes in list order and never revisits a node
        // whose dependencies were unmet, so the list must be a true topological
        // order regardless of how dependsOn crosses section boundaries.
        nodes = topologicalSort(nodes);

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
                if (targetPolicy != null
                        && !targetPolicy.allowedTargets().isEmpty()
                        && !targetPolicy.allowedTargets().contains(operation.target())) {
                    diagnostics.add(PlanDiagnostic.error(phase, stepId,
                            "Target is not allowed by execution policy: " + operation.target()));
                    continue;
                }

                long deadlineMs = parseTimeout(
                        step.timeout(), phase, stepId, "timeout", diagnostics);

                PlanNode.RetrySpec retrySpec = null;
                if (step.retry() != null) {
                    ScenarioStep.RetryPolicy retry = step.retry();
                    if (retry.maxAttempts() < 1) {
                        diagnostics.add(PlanDiagnostic.error(phase, stepId,
                                "retry.maxAttempts must be at least 1"));
                        continue;
                    }
                    if (retry.maxAttempts() > MAX_RETRY_ATTEMPTS) {
                        diagnostics.add(PlanDiagnostic.error(phase, stepId,
                                "retry.maxAttempts must not exceed " + MAX_RETRY_ATTEMPTS));
                        continue;
                    }
                    if ("cleanup".equals(phase) && retry.maxAttempts() > 1) {
                        diagnostics.add(PlanDiagnostic.error(phase, stepId,
                                "Retries are not supported for cleanup steps"));
                        continue;
                    }
                    if (step.expectError() && retry.maxAttempts() > 1) {
                        diagnostics.add(PlanDiagnostic.error(phase, stepId,
                                "expectError cannot be combined with retry"));
                        continue;
                    }
                    if (retry.maxAttempts() > 1) {
                        retrySpec = new PlanNode.RetrySpec(
                                retry.maxAttempts(), retry.backoffMs(),
                                retry.backoffMultiplier(), retry.maxBackoffMs());
                    }
                }
                int maxRetries = retrySpec == null ? 0 : retrySpec.maxAttempts() - 1;

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
                            inputExpressions, step.outputAs(), step.expectError(),
                            retrySpec, deps, operation.safety(), deadlineMs, maxRetries
                    ));
                }

            } else if ("wait".equals(step.type())) {
                long waitMs = parseTimeout(
                        step.timeout(), phase, stepId, "timeout", diagnostics);
                if (waitMs <= 0) {
                    diagnostics.add(PlanDiagnostic.error(
                            phase, stepId, "Wait step requires a positive timeout"));
                    continue;
                }
                List<NodeId> deps = resolveDependencies(step.dependsOn());
                nodes.add(new PlanNode.OperationNode(
                        nodeId, new OperationId("_wait"), null,
                        Map.of("waitMs", waitMs), step.outputAs(),
                        deps, dev.faultora.model.catalog.SafetyClassification.READ_ONLY, 0, 0
                ));
            } else {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "Unsupported step type in this release: " + step.type()));
            }
        }
    }

    private void compileFaultSteps(
            List<FaultStep> steps,
            TargetPolicy targetPolicy,
            List<PlanNode> nodes,
            List<PlanDiagnostic> diagnostics
    ) {
        if (steps == null) return;

        for (FaultStep step : steps) {
            if (step.faultType() == null || step.faultType().isBlank()) {
                diagnostics.add(PlanDiagnostic.error("faults", step.id(),
                        "Fault step has no faultType"));
                continue;
            }
            if (targetPolicy != null
                    && !targetPolicy.allowedFaultTypes().contains(step.faultType())) {
                diagnostics.add(PlanDiagnostic.error("faults", step.id(),
                        "Fault type is not allowed by execution policy: " + step.faultType()));
                continue;
            }

            long durationMs = parseTimeout(
                    step.duration(), "faults", step.id(), "duration", diagnostics);
            if (durationMs <= 0) {
                diagnostics.add(PlanDiagnostic.error("faults", step.id(),
                        "Fault step requires a positive duration"));
                continue;
            }

            nodes.add(new PlanNode.FaultStartNode(
                    new NodeId(step.id()), step.faultType(),
                    step.targetScope() == null || step.targetScope().isBlank()
                            ? "*" : step.targetScope(),
                    step.params() != null ? new LinkedHashMap<>(step.params()) : Map.of(),
                    durationMs,
                    resolveDependencies(step.dependsOn()),
                    SafetyClassification.MUTATING, 0, 0
            ));
        }
    }

    /**
     * Stable Kahn topological sort: dependencies come before dependents, and
     * nodes that are not ordered relative to each other keep compilation order.
     * Dependencies that do not resolve to a plan node are treated as satisfied.
     */
    private List<PlanNode> topologicalSort(List<PlanNode> nodes) {
        Map<NodeId, PlanNode> index = new LinkedHashMap<>();
        for (PlanNode node : nodes) {
            index.put(node.nodeId(), node);
        }

        Map<NodeId, Integer> inDegree = new LinkedHashMap<>();
        Map<NodeId, List<PlanNode>> dependents = new LinkedHashMap<>();
        for (PlanNode node : nodes) {
            int degree = 0;
            for (NodeId dep : node.dependencies()) {
                if (index.containsKey(dep)) {
                    degree++;
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(node);
                }
            }
            inDegree.put(node.nodeId(), degree);
        }

        Deque<PlanNode> ready = new ArrayDeque<>();
        for (PlanNode node : nodes) {
            if (inDegree.get(node.nodeId()) == 0) {
                ready.addLast(node);
            }
        }

        List<PlanNode> ordered = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            PlanNode node = ready.pollFirst();
            ordered.add(node);
            for (PlanNode dependent : dependents.getOrDefault(node.nodeId(), List.of())) {
                int remaining = inDegree.merge(dependent.nodeId(), -1, Integer::sum);
                if (remaining == 0) {
                    ready.addLast(dependent);
                }
            }
        }

        // A cycle was already reported by hasCycles; keep the original order
        // as a defensive fallback if anything remains unordered.
        return ordered.size() == nodes.size() ? ordered : nodes;
    }

    private void compileAssertionSteps(
            List<AssertionStep> steps,
            NodeId defaultTarget,
            List<PlanNode> nodes,
            List<PlanDiagnostic> diagnostics
    ) {
        if (steps == null) return;

        for (AssertionStep step : steps) {
            NodeId nodeId = new NodeId(step.id());
            NodeId targetNode = step.targetStep() != null && !step.targetStep().isBlank()
                    ? new NodeId(step.targetStep())
                    : defaultTarget;

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

    private NodeId lastStepId(List<ScenarioStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        return new NodeId(steps.get(steps.size() - 1).id());
    }

    private boolean isAllowedByPolicy(SafetyClassification safety, TargetPolicy policy) {
        if (policy == null) return true;
        return policy.allowedOperationClasses().isEmpty() ||
                policy.allowedOperationClasses().contains(safety);
    }

    private long parseTimeout(
            String timeout,
            String phase,
            String stepId,
            String field,
            List<PlanDiagnostic> diagnostics
    ) {
        if (timeout == null || timeout.isBlank()) return 0;
        try {
            String trimmed = timeout.trim().toLowerCase();
            long multiplier = 1;
            if (trimmed.endsWith("ms")) {
                trimmed = trimmed.substring(0, trimmed.length() - 2);
            } else if (trimmed.endsWith("s")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
                multiplier = 1000;
            } else if (trimmed.endsWith("m")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
                multiplier = 60_000;
            }
            long parsed = Math.multiplyExact(Long.parseLong(trimmed), multiplier);
            if (parsed < 0) throw new NumberFormatException("negative duration");
            return parsed;
        } catch (ArithmeticException | NumberFormatException e) {
            diagnostics.add(PlanDiagnostic.error(
                    phase, stepId, "Invalid " + field + ": " + timeout));
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
