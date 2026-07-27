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

    /** Upper bound on iterations of a single repeat group. */
    static final int MAX_REPEAT_ITERATIONS = 100;

    /** Upper bound on polls of a single eventually group. */
    static final int MAX_POLL_ATTEMPTS = 100;

    /** Poll interval used when an eventually step does not declare one. */
    static final long DEFAULT_POLL_INTERVAL_MS = 1000;

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

        // Children of a grouping step execute inside the group, so a
        // dependency on a child must attach to the group that runs it.
        Map<String, String> childToGroup = mapChildrenToGroups(scenario);

        // Build operation lookup
        Map<String, OperationDefinition> operationIndex = new LinkedHashMap<>();
        for (OperationDefinition op : catalog.operations()) {
            operationIndex.put(op.id().value(), op);
        }

        // Compile setup steps
        compileSteps(scenario.setup(), "setup", operationIndex, targetPolicy,
                childToGroup, nodes, diagnostics);

        // Compile fault steps before execute: a fault with no dependencies
        // activates before the first execute step runs.
        compileFaultSteps(scenario.faults(), targetPolicy, childToGroup, nodes, diagnostics);

        // Compile execute steps
        compileSteps(scenario.execute(), "execute", operationIndex, targetPolicy,
                childToGroup, nodes, diagnostics);

        // Compile assertion steps
        compileAssertionSteps(
                scenario.assertions(), lastStepId(scenario.execute()),
                childToGroup, Set.copyOf(childToGroup.values()), nodes, diagnostics);

        // Compile cleanup steps
        compileSteps(scenario.cleanup(), "cleanup", operationIndex, targetPolicy,
                childToGroup, nodes, diagnostics);

        long scenarioTimeoutMs = parseTimeout(
                scenario.timeout(), "scenario", "", "timeout", diagnostics);

        if (targetPolicy != null) {
            // Retrying nodes count once per allowed attempt, so retries cannot
            // multiply traffic past the policy's request budget.
            long requestCount = nodes.stream()
                    .mapToLong(node -> switch (node) {
                        case PlanNode.OperationNode operation when operation.operation() != null ->
                                operation.retrySpec() == null
                                        ? 1 : operation.retrySpec().maxAttempts();
                        case PlanNode.ParallelNode parallel ->
                                attempts(parallel.children());
                        // Every iteration re-runs every child, and every poll
                        // sends one request, so both are budgeted in full.
                        case PlanNode.RepeatNode repeat ->
                                attempts(repeat.children()) * repeat.iterations();
                        case PlanNode.EventuallyNode eventually -> eventually.maxPolls();
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
                .scenarioTimeoutMs(scenarioTimeoutMs)
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
            Map<String, String> childToGroup,
            List<PlanNode> nodes,
            List<PlanDiagnostic> diagnostics
    ) {
        if (steps == null) return;

        for (ScenarioStep step : steps) {
            String stepId = step.id();
            NodeId nodeId = new NodeId(stepId);

            if ("operation".equals(step.type()) || step.type() == null) {
                PlanNode.OperationNode operationNode = compileOperation(
                        step, phase, operationIndex, targetPolicy,
                        resolveDependencies(step.dependsOn(), childToGroup), diagnostics);
                if (operationNode == null) {
                    continue;
                }
                if ("cleanup".equals(phase)) {
                    nodes.add(new PlanNode.CleanupNode(
                            operationNode.nodeId(), operationNode.operationId(),
                            operationNode.inputExpressions(),
                            operationNode.dependencies(), operationNode.safety(),
                            operationNode.deadlineMs(), operationNode.maxRetries()
                    ));
                } else {
                    nodes.add(operationNode);
                }

            } else if ("parallel".equals(step.type())) {
                if ("cleanup".equals(phase)) {
                    diagnostics.add(PlanDiagnostic.error(phase, stepId,
                            "Parallel steps are not allowed in cleanup"));
                    continue;
                }
                int childCount = step.steps() == null ? 0 : step.steps().size();
                if (targetPolicy != null && childCount > targetPolicy.maxConcurrency()) {
                    diagnostics.add(PlanDiagnostic.error(phase, stepId,
                            "Parallel step has " + childCount
                                    + " children, policy allows concurrency "
                                    + targetPolicy.maxConcurrency()));
                    continue;
                }

                List<PlanNode.OperationNode> children = compileGroupChildren(
                        step, phase, "Parallel", operationIndex, targetPolicy, diagnostics);
                if (children == null) {
                    continue;
                }

                long deadlineMs = parseTimeout(
                        step.timeout(), phase, stepId, "timeout", diagnostics);
                nodes.add(new PlanNode.ParallelNode(
                        nodeId, children,
                        resolveDependencies(step.dependsOn(), childToGroup),
                        groupSafety(children), deadlineMs, 0
                ));

            } else if ("repeat".equals(step.type())) {
                PlanNode repeatNode = compileRepeat(
                        step, phase, operationIndex, targetPolicy, childToGroup, diagnostics);
                if (repeatNode != null) {
                    nodes.add(repeatNode);
                }

            } else if ("eventually".equals(step.type())) {
                PlanNode eventuallyNode = compileEventually(
                        step, phase, operationIndex, targetPolicy, childToGroup, diagnostics);
                if (eventuallyNode != null) {
                    nodes.add(eventuallyNode);
                }

            } else if ("wait".equals(step.type())) {
                long waitMs = parseTimeout(
                        step.timeout(), phase, stepId, "timeout", diagnostics);
                if (waitMs <= 0) {
                    diagnostics.add(PlanDiagnostic.error(
                            phase, stepId, "Wait step requires a positive timeout"));
                    continue;
                }
                List<NodeId> deps = resolveDependencies(step.dependsOn(), childToGroup);
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

    /**
     * Compile a repeat group. The iteration count is resolved here, so the
     * request budget of the whole group is known before execution starts.
     */
    private PlanNode compileRepeat(
            ScenarioStep step,
            String phase,
            Map<String, OperationDefinition> operationIndex,
            TargetPolicy targetPolicy,
            Map<String, String> childToGroup,
            List<PlanDiagnostic> diagnostics
    ) {
        String stepId = step.id();
        if ("cleanup".equals(phase)) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Repeat steps are not allowed in cleanup"));
            return null;
        }

        boolean hasCount = step.count() != null;
        boolean hasForEach = step.forEach() != null;
        if (hasCount == hasForEach) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Repeat step requires exactly one of count or forEach"));
            return null;
        }
        int iterations = hasCount ? step.count() : step.forEach().size();
        if (iterations < 1 || iterations > MAX_REPEAT_ITERATIONS) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Repeat step requires 1 to " + MAX_REPEAT_ITERATIONS
                            + " iterations, got: " + iterations));
            return null;
        }

        List<PlanNode.OperationNode> children = compileGroupChildren(
                step, phase, "Repeat", operationIndex, targetPolicy, diagnostics);
        if (children == null) {
            return null;
        }

        return new PlanNode.RepeatNode(
                new NodeId(stepId), children,
                hasForEach ? step.forEach() : null, iterations,
                resolveDependencies(step.dependsOn(), childToGroup), groupSafety(children),
                parseTimeout(step.timeout(), phase, stepId, "timeout", diagnostics), 0
        );
    }

    /**
     * Compile an eventually group. The poll budget is derived from the timeout
     * and interval, so the group cannot outlive its declared window or exceed
     * the request budget.
     */
    private PlanNode compileEventually(
            ScenarioStep step,
            String phase,
            Map<String, OperationDefinition> operationIndex,
            TargetPolicy targetPolicy,
            Map<String, String> childToGroup,
            List<PlanDiagnostic> diagnostics
    ) {
        String stepId = step.id();
        if ("cleanup".equals(phase)) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Eventually steps are not allowed in cleanup"));
            return null;
        }

        long timeoutMs = parseTimeout(step.timeout(), phase, stepId, "timeout", diagnostics);
        if (timeoutMs <= 0) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Eventually step requires a positive timeout budget"));
            return null;
        }
        long intervalMs = step.interval() == null || step.interval().isBlank()
                ? DEFAULT_POLL_INTERVAL_MS
                : parseTimeout(step.interval(), phase, stepId, "interval", diagnostics);
        if (intervalMs <= 0) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Eventually step requires a positive interval"));
            return null;
        }
        if (intervalMs > timeoutMs) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Eventually interval (" + intervalMs
                            + "ms) must not exceed the timeout budget (" + timeoutMs + "ms)"));
            return null;
        }

        if (step.until() == null || step.until().isEmpty()) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Eventually step requires at least one until condition"));
            return null;
        }
        List<PlanNode.Condition> conditions = new ArrayList<>();
        for (ScenarioStep.Condition condition : step.until()) {
            if (condition.assertionType() == null || condition.assertionType().isBlank()) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "Until condition requires an assertionType"));
                return null;
            }
            conditions.add(new PlanNode.Condition(
                    condition.assertionType(), condition.params(), condition.message()));
        }

        List<PlanNode.OperationNode> children = compileGroupChildren(
                step, phase, "Eventually", operationIndex, targetPolicy, diagnostics);
        if (children == null) {
            return null;
        }
        if (children.size() != 1) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Eventually step requires exactly one child operation step"));
            return null;
        }

        long requestedPolls = 1 + timeoutMs / intervalMs;
        if (requestedPolls > MAX_POLL_ATTEMPTS) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Eventually step would need " + requestedPolls + " polls, the maximum is "
                            + MAX_POLL_ATTEMPTS + "; raise interval to at least "
                            + (timeoutMs / (MAX_POLL_ATTEMPTS - 1) + 1) + "ms"));
            return null;
        }
        int maxPolls = (int) requestedPolls;
        return new PlanNode.EventuallyNode(
                new NodeId(stepId), children.get(0), conditions,
                timeoutMs, intervalMs, maxPolls,
                resolveDependencies(step.dependsOn(), childToGroup),
                groupSafety(children), 0, 0
        );
    }

    /**
     * Compile the operation children of a grouping step, or null when the
     * group declares no children or a child is invalid.
     */
    private List<PlanNode.OperationNode> compileGroupChildren(
            ScenarioStep step,
            String phase,
            String groupLabel,
            Map<String, OperationDefinition> operationIndex,
            TargetPolicy targetPolicy,
            List<PlanDiagnostic> diagnostics
    ) {
        List<ScenarioStep> childSteps = step.steps() == null ? List.of() : step.steps();
        if (childSteps.isEmpty()) {
            diagnostics.add(PlanDiagnostic.error(phase, step.id(),
                    groupLabel + " step requires at least one child step"));
            return null;
        }
        List<PlanNode.OperationNode> children = new ArrayList<>();
        boolean valid = true;
        for (ScenarioStep child : childSteps) {
            PlanNode.OperationNode childNode = compileOperation(
                    child, phase, operationIndex, targetPolicy, List.of(), diagnostics);
            if (childNode == null) {
                valid = false;
                continue;
            }
            children.add(childNode);
        }
        return valid ? children : null;
    }

    private static SafetyClassification groupSafety(List<PlanNode.OperationNode> children) {
        return children.stream()
                .map(PlanNode.OperationNode::safety)
                .max(java.util.Comparator.comparingInt(Enum::ordinal))
                .orElse(SafetyClassification.READ_ONLY);
    }

    private static long attempts(List<PlanNode.OperationNode> children) {
        return children.stream()
                .mapToLong(child -> child.retrySpec() == null
                        ? 1 : child.retrySpec().maxAttempts())
                .sum();
    }

    /**
     * Compile one operation step into an OperationNode, reporting diagnostics
     * and returning null when the step is invalid.
     */
    private PlanNode.OperationNode compileOperation(
            ScenarioStep step,
            String phase,
            Map<String, OperationDefinition> operationIndex,
            TargetPolicy targetPolicy,
            List<NodeId> deps,
            List<PlanDiagnostic> diagnostics
    ) {
        String stepId = step.id();
        String opId = step.operationId();
        if (opId == null || opId.isBlank()) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Step has no operationId"));
            return null;
        }

        OperationDefinition operation = operationIndex.get(opId);
        if (operation == null) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Operation not found in catalog: " + opId));
            return null;
        }

        // Check safety classification against policy
        if (!isAllowedByPolicy(operation.safety(), targetPolicy)) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Operation " + opId + " with safety " + operation.safety() +
                            " is not allowed by execution policy"));
            return null;
        }
        if (targetPolicy != null
                && !targetPolicy.allowedTargets().isEmpty()
                && !targetPolicy.allowedTargets().contains(operation.target())) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Target is not allowed by execution policy: " + operation.target()));
            return null;
        }

        long deadlineMs = parseTimeout(
                step.timeout(), phase, stepId, "timeout", diagnostics);

        PlanNode.RetrySpec retrySpec = null;
        if (step.retry() != null) {
            ScenarioStep.RetryPolicy retry = step.retry();
            if (retry.maxAttempts() < 1) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "retry.maxAttempts must be at least 1"));
                return null;
            }
            if (retry.maxAttempts() > MAX_RETRY_ATTEMPTS) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "retry.maxAttempts must not exceed " + MAX_RETRY_ATTEMPTS));
                return null;
            }
            if ("cleanup".equals(phase) && retry.maxAttempts() > 1) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "Retries are not supported for cleanup steps"));
                return null;
            }
            if (step.expectError() && retry.maxAttempts() > 1) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "expectError cannot be combined with retry"));
                return null;
            }
            if (retry.maxAttempts() > 1) {
                retrySpec = new PlanNode.RetrySpec(
                        retry.maxAttempts(), retry.backoffMs(),
                        retry.backoffMultiplier(), retry.maxBackoffMs());
            }
        }
        int maxRetries = retrySpec == null ? 0 : retrySpec.maxAttempts() - 1;

        Map<String, Object> inputExpressions = step.inputs() != null ?
                new LinkedHashMap<>(step.inputs()) : Map.of();

        return new PlanNode.OperationNode(
                new NodeId(stepId), new OperationId(opId), operation,
                inputExpressions, step.outputAs(), step.expectError(),
                retrySpec, deps, operation.safety(), deadlineMs, maxRetries
        );
    }

    private void compileFaultSteps(
            List<FaultStep> steps,
            TargetPolicy targetPolicy,
            Map<String, String> childToGroup,
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
                    resolveDependencies(step.dependsOn(), childToGroup),
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
            Map<String, String> childToGroup,
            Set<String> groupIds,
            List<PlanNode> nodes,
            List<PlanDiagnostic> diagnostics
    ) {
        if (steps == null) return;

        for (AssertionStep step : steps) {
            NodeId nodeId = new NodeId(step.id());
            boolean explicitTarget =
                    step.targetStep() != null && !step.targetStep().isBlank();
            NodeId targetNode = explicitTarget
                    ? new NodeId(step.targetStep())
                    : defaultTarget;

            // A grouping step holds no evidence of its own: the requests are
            // made by its children, so the assertion must name one.
            if (targetNode != null && groupIds.contains(targetNode.value())) {
                diagnostics.add(PlanDiagnostic.error("assertions", step.id(),
                        (explicitTarget ? "targetStep" : "The default target")
                                + " '" + targetNode.value() + "' is a group step, which holds"
                                + " no evidence; target one of its child steps instead"));
                continue;
            }

            List<NodeId> deps = new ArrayList<>(
                    resolveDependencies(step.dependsOn(), childToGroup));
            // Add target step as implicit ordering dependency; evidence still
            // comes from the (possibly child) target node itself.
            NodeId orderingTarget = targetNode == null ? null
                    : new NodeId(childToGroup.getOrDefault(
                            targetNode.value(), targetNode.value()));
            if (orderingTarget != null && !deps.contains(orderingTarget)) {
                deps.add(orderingTarget);
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

    /**
     * Resolve declared dependencies to plan node IDs. A dependency on a step
     * that runs inside a group resolves to the group, which is the node the
     * engine can actually wait for.
     */
    private List<NodeId> resolveDependencies(
            List<String> dependsOn, Map<String, String> childToGroup) {
        if (dependsOn == null || dependsOn.isEmpty()) return List.of();
        return dependsOn.stream()
                .map(id -> new NodeId(childToGroup.getOrDefault(id, id)))
                .distinct()
                .toList();
    }

    /** Index every child step of a grouping step to the group that runs it. */
    private Map<String, String> mapChildrenToGroups(ScenarioDocument scenario) {
        Map<String, String> childToGroup = new LinkedHashMap<>();
        for (List<ScenarioStep> section :
                List.of(nullSafe(scenario.setup()), nullSafe(scenario.execute()),
                        nullSafe(scenario.cleanup()))) {
            for (ScenarioStep step : section) {
                if (step.steps() == null || step.id() == null) continue;
                for (ScenarioStep child : step.steps()) {
                    if (child.id() != null) {
                        childToGroup.put(child.id(), step.id());
                    }
                }
            }
        }
        return childToGroup;
    }

    private static List<ScenarioStep> nullSafe(List<ScenarioStep> steps) {
        return steps == null ? List.of() : steps;
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
