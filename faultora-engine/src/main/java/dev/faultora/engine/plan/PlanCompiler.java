package dev.faultora.engine.plan;

import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.model.*;
import dev.faultora.schema.SchemaCatalog;
import dev.faultora.spec.parser.DurationSyntax;

import java.util.*;

/**
 * Compiles a ScenarioDocument + ApiCatalog into an immutable ExecutionPlan.
 * Resolves scenario references against the catalog, validates operations exist,
 * checks safety classification, and produces a DAG with stable node IDs.
 */
public class PlanCompiler {

    /** Assertion type that checks a response against its declared schema. */
    private static final String SCHEMA_ASSERTION = "schema";

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

        GeneratedInputCompiler generatedInputs = new GeneratedInputCompiler(catalog, seed);

        // Compile setup steps
        compileSteps(scenario.setup(), "setup", operationIndex, targetPolicy,
                childToGroup, generatedInputs, nodes, diagnostics);

        // Compile fault steps before execute: a fault with no dependencies
        // activates before the first execute step runs.
        compileFaultSteps(scenario.faults(), targetPolicy, childToGroup, nodes, diagnostics);

        // Compile execute steps
        compileSteps(scenario.execute(), "execute", operationIndex, targetPolicy,
                childToGroup, generatedInputs, nodes, diagnostics);

        // Compile assertion steps
        compileAssertionSteps(
                scenario.assertions(), lastStepId(scenario.execute()),
                childToGroup, Set.copyOf(childToGroup.values()), catalog,
                nodes, diagnostics);

        // Compile cleanup steps
        compileSteps(scenario.cleanup(), "cleanup", operationIndex, targetPolicy,
                childToGroup, generatedInputs, nodes, diagnostics);

        long scenarioTimeoutMs = parseTimeout(
                scenario.timeout(), "scenario", "", "timeout", diagnostics);
        scenarioTimeoutMs = boundedByPolicy(scenarioTimeoutMs, targetPolicy, diagnostics);

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
            GeneratedInputCompiler generatedInputs,
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
                        resolveDependencies(step.dependsOn(), childToGroup),
                        generatedInputs, List.of(stepId), diagnostics);
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
                        step, phase, "Parallel", operationIndex, targetPolicy,
                        generatedInputs, 1, diagnostics);
                if (children == null) {
                    continue;
                }

                long deadlineMs = parseTimeout(
                        step.timeout(), phase, stepId, "timeout", diagnostics);
                nodes.add(new PlanNode.ParallelNode(
                        nodeId, children,
                        resolveDependencies(step.dependsOn(), childToGroup),
                        groupSafety(children), deadlineMs
                ));

            } else if ("repeat".equals(step.type())) {
                PlanNode repeatNode = compileRepeat(
                        step, phase, operationIndex, targetPolicy, childToGroup,
                        generatedInputs, diagnostics);
                if (repeatNode != null) {
                    nodes.add(repeatNode);
                }

            } else if ("eventually".equals(step.type())) {
                PlanNode eventuallyNode = compileEventually(
                        step, phase, operationIndex, targetPolicy, childToGroup,
                        generatedInputs, diagnostics);
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
                nodes.add(new PlanNode.WaitNode(
                        nodeId, waitMs,
                        resolveDependencies(step.dependsOn(), childToGroup),
                        SafetyClassification.READ_ONLY, "cleanup".equals(phase)
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
            GeneratedInputCompiler generatedInputs,
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
        if (iterations < 1 || iterations > ScenarioLimits.MAX_REPEAT_ITERATIONS) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Repeat step requires 1 to " + ScenarioLimits.MAX_REPEAT_ITERATIONS
                            + " iterations, got: " + iterations));
            return null;
        }

        List<PlanNode.OperationNode> children = compileGroupChildren(
                step, phase, "Repeat", operationIndex, targetPolicy,
                generatedInputs, iterations, diagnostics);
        if (children == null) {
            return null;
        }

        return new PlanNode.RepeatNode(
                new NodeId(stepId), children,
                hasForEach ? step.forEach() : null, iterations,
                resolveDependencies(step.dependsOn(), childToGroup), groupSafety(children),
                parseTimeout(step.timeout(), phase, stepId, "timeout", diagnostics)
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
            GeneratedInputCompiler generatedInputs,
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
                ? ScenarioLimits.DEFAULT_POLL_INTERVAL_MS
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
                step, phase, "Eventually", operationIndex, targetPolicy,
                generatedInputs, 1, diagnostics);
        if (children == null) {
            return null;
        }
        if (children.size() != 1) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Eventually step requires exactly one child operation step"));
            return null;
        }

        long requestedPolls = 1 + timeoutMs / intervalMs;
        if (requestedPolls > ScenarioLimits.MAX_POLL_ATTEMPTS) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Eventually step would need " + requestedPolls + " polls, the maximum is "
                            + ScenarioLimits.MAX_POLL_ATTEMPTS + "; raise interval to at least "
                            + (timeoutMs / (ScenarioLimits.MAX_POLL_ATTEMPTS - 1) + 1) + "ms"));
            return null;
        }
        int maxPolls = (int) requestedPolls;
        return new PlanNode.EventuallyNode(
                new NodeId(stepId), children.get(0), conditions,
                timeoutMs, intervalMs, maxPolls,
                resolveDependencies(step.dependsOn(), childToGroup),
                groupSafety(children)
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
            GeneratedInputCompiler generatedInputs,
            int iterations,
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
                    child, phase, operationIndex, targetPolicy, List.of(),
                    generatedInputs, iterationNodeIds(child.id(), iterations), diagnostics);
            if (childNode == null) {
                valid = false;
                continue;
            }
            children.add(childNode);
        }
        return valid ? children : null;
    }

    /**
     * Node IDs one step executes under. A repeat child runs once per
     * iteration, each under its own ID and therefore its own seed.
     */
    private static List<String> iterationNodeIds(String stepId, int iterations) {
        if (iterations <= 1) {
            return List.of(stepId);
        }
        List<String> ids = new ArrayList<>(iterations);
        for (int index = 0; index < iterations; index++) {
            ids.add(RepeatIterations.name(stepId, index));
        }
        return ids;
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
            GeneratedInputCompiler generatedInputs,
            List<String> generationNodeIds,
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
            if (!retry.isWithinRange()) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "retry.maxAttempts must be at least 1"));
                return null;
            }
            if (retry.exceedsAttemptLimit()) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "retry.maxAttempts must not exceed "
                                + ScenarioLimits.MAX_RETRY_ATTEMPTS));
                return null;
            }
            if ("cleanup".equals(phase) && retry.retriesAtAll()) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "Retries are not supported for cleanup steps"));
                return null;
            }
            if (step.expectError() && retry.retriesAtAll()) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "expectError cannot be combined with retry"));
                return null;
            }
            if (retry.retriesAtAll()) {
                retrySpec = new PlanNode.RetrySpec(
                        retry.maxAttempts(), retry.backoffMs(),
                        retry.backoffMultiplier(), retry.maxBackoffMs());
            }
        }
        int maxRetries = retrySpec == null ? 0 : retrySpec.maxAttempts() - 1;

        PlanNode.GenerationRequest generation = null;
        if (step.generate() != null) {
            generation = generatedInputs.compile(
                    step, operation, phase, generationNodeIds, diagnostics);
            if (generation == null) {
                return null;
            }
        }

        Map<String, Object> inputExpressions = step.inputs() != null ?
                new LinkedHashMap<>(step.inputs()) : Map.of();

        return new PlanNode.OperationNode(
                new NodeId(stepId), new OperationId(opId), operation,
                inputExpressions, step.outputAs(), step.expectError(),
                retrySpec, deps, operation.safety(), deadlineMs, maxRetries, generation
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
                    SafetyClassification.MUTATING
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
            ApiCatalog catalog,
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

            Map<String, Object> schema = null;
            if (SCHEMA_ASSERTION.equals(step.assertionType())) {
                schema = resolveResponseSchema(step, targetNode, catalog, nodes, diagnostics);
                if (schema == null) {
                    continue;
                }
            }

            nodes.add(new PlanNode.AssertionNode(
                    nodeId, step.assertionType(), params,
                    targetNode, step.message(), deps,
                    SafetyClassification.READ_ONLY, schema
            ));
        }
    }

    /**
     * The run's deadline, bounded by the policy's wall-clock budget.
     * <p>
     * A scenario declaring no deadline inherits the operator's; one declaring
     * a longer deadline is refused rather than quietly shortened. Silently
     * capping would let a scenario read as if it had an hour while the run
     * stopped after five minutes, and the author would debug the wrong thing.
     */
    private long boundedByPolicy(
            long scenarioTimeoutMs, TargetPolicy targetPolicy,
            List<PlanDiagnostic> diagnostics) {
        if (targetPolicy == null || targetPolicy.maxDurationMs() <= 0) {
            return scenarioTimeoutMs;
        }
        if (scenarioTimeoutMs <= 0) {
            return targetPolicy.maxDurationMs();
        }
        if (scenarioTimeoutMs > targetPolicy.maxDurationMs()) {
            diagnostics.add(PlanDiagnostic.error("scenario", "",
                    "Scenario timeout of " + scenarioTimeoutMs
                            + "ms exceeds the execution policy's budget of "
                            + targetPolicy.maxDurationMs() + "ms"));
        }
        return scenarioTimeoutMs;
    }

    /**
     * The response schema a schema assertion checks against.
     * <p>
     * Resolved here so that a scenario asserting against a contract the
     * catalog does not declare fails before the run starts, naming what is
     * missing. When the operation declares more than one response schema, the
     * assertion has to say which status it means: guessing would silently
     * check a created resource against the error shape.
     *
     * @return the schema, or null when it cannot be resolved
     */
    private Map<String, Object> resolveResponseSchema(
            AssertionStep step,
            NodeId targetNode,
            ApiCatalog catalog,
            List<PlanNode> nodes,
            List<PlanDiagnostic> diagnostics
    ) {
        if (targetNode == null) {
            diagnostics.add(PlanDiagnostic.error("assertions", step.id(),
                    "A schema assertion needs a targetStep naming the operation to check"));
            return null;
        }
        OperationDefinition operation = operationOf(targetNode, nodes, catalog);
        if (operation == null) {
            diagnostics.add(PlanDiagnostic.error("assertions", step.id(),
                    "targetStep '" + targetNode.value() + "' does not invoke an operation, "
                            + "so it has no declared response schema"));
            return null;
        }

        Map<String, SchemaId> outcomes = operation.outcomes() == null
                ? Map.of() : operation.outcomes();
        Object declaredStatus = step.params() == null ? null : step.params().get("status");
        SchemaId schemaId;
        if (declaredStatus != null) {
            schemaId = outcomes.get(String.valueOf(declaredStatus));
            if (schemaId == null) {
                diagnostics.add(PlanDiagnostic.error("assertions", step.id(),
                        "Operation " + operation.id().value() + " declares no response schema "
                                + "for status " + declaredStatus));
                return null;
            }
        } else if (outcomes.size() == 1) {
            schemaId = outcomes.values().iterator().next();
        } else if (outcomes.isEmpty()) {
            diagnostics.add(PlanDiagnostic.error("assertions", step.id(),
                    "Operation " + operation.id().value()
                            + " declares no response schema to check against"));
            return null;
        } else {
            diagnostics.add(PlanDiagnostic.error("assertions", step.id(),
                    "Operation " + operation.id().value() + " declares response schemas for "
                            + outcomes.keySet() + "; name the one to check with params.status"));
            return null;
        }

        DataSchema schema = catalog.schemas() == null ? null : catalog.schemas().get(schemaId);
        if (schema == null || schema.definition() == null) {
            diagnostics.add(PlanDiagnostic.error("assertions", step.id(),
                    "The catalog has no schema " + schemaId.value()));
            return null;
        }
        // The assertion receives the schema alone and cannot follow a
        // reference later, so references are expanded while the catalog is
        // still at hand.
        SchemaCatalog schemas = new SchemaCatalog(catalog.schemas());
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> inlined = mapper.convertValue(
                schemas.inline(mapper.valueToTree(schema.definition())), Map.class);
        return inlined;
    }

    /** The operation a target node invokes, or null when it invokes none. */
    private OperationDefinition operationOf(
            NodeId targetNode, List<PlanNode> nodes, ApiCatalog catalog) {
        for (PlanNode node : nodes) {
            if (node instanceof PlanNode.OperationNode operation
                    && operation.nodeId().equals(targetNode)) {
                return operation.operation();
            }
            if (node instanceof PlanNode.CleanupNode cleanup
                    && cleanup.nodeId().equals(targetNode)) {
                return catalog.operations().stream()
                        .filter(candidate -> candidate.id().equals(cleanup.operationId()))
                        .findFirst().orElse(null);
            }
            List<PlanNode.OperationNode> children = switch (node) {
                case PlanNode.ParallelNode parallel -> parallel.children();
                case PlanNode.RepeatNode repeat -> repeat.children();
                case PlanNode.EventuallyNode eventually -> List.of(eventually.child());
                default -> List.of();
            };
            for (PlanNode.OperationNode child : children) {
                if (child.nodeId().equals(targetNode)) {
                    return child.operation();
                }
            }
        }
        return null;
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
        if (DurationSyntax.isAbsent(timeout)) return 0;
        java.util.OptionalLong parsed = DurationSyntax.parseMillis(timeout);
        if (parsed.isEmpty() || parsed.getAsLong() < 0) {
            diagnostics.add(PlanDiagnostic.error(
                    phase, stepId, "Invalid " + field + ": " + timeout));
            return 0;
        }
        return parsed.getAsLong();
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
