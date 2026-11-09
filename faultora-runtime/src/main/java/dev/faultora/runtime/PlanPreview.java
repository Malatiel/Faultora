package dev.faultora.runtime;

import dev.faultora.engine.plan.PlanNode;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.model.AssertionStep;
import dev.faultora.spec.model.FaultStep;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Structural preview. Contains no request data, credentials, endpoint URLs or fault parameters. */
public record PlanPreview(
        boolean compiled, CatalogDescription catalog, RuntimeCapabilities capabilities,
        Budgets budgets, List<Action> actions, List<Fault> faults, List<Diagnostic> diagnostics) {
    public PlanPreview {
        actions = List.copyOf(actions);
        faults = List.copyOf(faults);
        diagnostics = List.copyOf(diagnostics);
    }

    public record Budgets(int maxRequests, int maxConcurrency, long maxDurationMs,
                          long maxPayloadBytes, long scenarioTimeoutMs) { }

    public record Action(String nodeId, String kind, String operationId, String target,
                         String protocol, long deadlineMs, int maxAttempts, int repetitions) { }

    public record Fault(String nodeId, String type, long durationMs) { }

    /** Messages are fixed because parser/provider messages may contain document values. */
    public record Diagnostic(String severity, String phase, String nodeId, String path,
                             String message, String code) { }

    public static PlanPreview describe(
            RunPreparation.Result prepared, RuntimeCapabilities capabilities, TargetPolicy policy) {
        List<Action> actions = new ArrayList<>();
        List<Fault> faults = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        prepared.diagnostics().forEach(diagnostic -> diagnostics.add(new Diagnostic(
                diagnostic.severity().name(), "scenario", null, diagnostic.path(),
                diagnostic.isError() ? "Scenario validation failed" : "Scenario diagnostic",
                diagnostic.code().name())));
        // The importer's own words name what the document declares, so the
        // preview says only which family spoke; the operator reads the rest.
        prepared.catalogWarnings().forEach(warning -> diagnostics.add(new Diagnostic(
                "WARNING", "catalog", null, warning.family(), "Catalog import warning", "CATALOG_IMPORT_WARNING")));
        long timeout = 0;
        if (prepared.compilation() != null) {
            Map<String, String> paths = stepPaths(prepared.scenario());
            prepared.compilation().diagnostics().forEach(diagnostic -> diagnostics.add(new Diagnostic(
                    diagnostic.severity().name(), diagnostic.phase(), diagnostic.stepId(),
                    paths.getOrDefault(diagnostic.stepId(), ""),
                    diagnostic.isError() ? "Plan compilation refused" : "Plan compilation diagnostic",
                    diagnostic.isError() ? "PLAN_COMPILATION_REFUSED" : "PLAN_COMPILATION_WARNING")));
            if (prepared.isSuccess()) {
                timeout = prepared.compilation().plan().scenarioTimeoutMs();
                for (PlanNode node : prepared.compilation().plan().nodes()) {
                    describe(node, prepared.catalog(), actions, faults);
                }
            }
        }
        return new PlanPreview(prepared.isSuccess(),
                prepared.catalog() == null ? null : CatalogDescription.describe(prepared.catalog(), policy),
                capabilities,
                new Budgets(policy.maxRequests(), policy.maxConcurrency(), policy.maxDurationMs(),
                        policy.maxPayloadBytes(), timeout), actions, faults, diagnostics);
    }

    /** Attach document positions without using compiler IDs as path segments. */
    private static Map<String, String> stepPaths(ScenarioDocument scenario) {
        Map<String, String> paths = new HashMap<>();
        if (scenario != null) {
            addStepPaths(paths, scenario.setup(), "setup");
            addStepPaths(paths, scenario.execute(), "execute");
            addStepPaths(paths, scenario.cleanup(), "cleanup");
            addPaths(paths, scenario.faults(), "faults", FaultStep::id);
            addPaths(paths, scenario.assertions(), "assertions", AssertionStep::id);
        }
        return paths;
    }

    private static void addStepPaths(Map<String, String> paths, List<ScenarioStep> steps, String section) {
        addPaths(paths, steps, section, ScenarioStep::id);
        if (steps != null) {
            for (int index = 0; index < steps.size(); index++) {
                addStepPaths(paths, steps.get(index).steps(), section + '[' + index + "].steps");
            }
        }
    }

    private static <T> void addPaths(Map<String, String> paths, List<T> steps, String section, Function<T, String> id) {
        if (steps != null) {
            for (int index = 0; index < steps.size(); index++) {
                paths.put(id.apply(steps.get(index)), section + '[' + index + ']');
            }
        }
    }

    private static void describe(PlanNode node, ApiCatalog catalog, List<Action> actions, List<Fault> faults) {
        switch (node) {
            case PlanNode.OperationNode operation -> actions.add(operation(operation, 1));
            case PlanNode.ParallelNode parallel -> {
                actions.add(group(node, "parallel", parallel.deadlineMs(), 1));
                parallel.children().forEach(child -> actions.add(operation(child, 1)));
            }
            case PlanNode.RepeatNode repeat -> {
                actions.add(group(node, "repeat", repeat.deadlineMs(), repeat.iterations()));
                repeat.children().forEach(child -> actions.add(operation(child, repeat.iterations())));
            }
            case PlanNode.EventuallyNode eventually -> {
                actions.add(group(node, "eventually", eventually.timeoutMs(), eventually.maxPolls()));
                actions.add(operation(eventually.child(), eventually.maxPolls()));
            }
            case PlanNode.FaultStartNode fault ->
                    faults.add(new Fault(fault.nodeId().value(), fault.faultType(), fault.durationMs()));
            case PlanNode.CleanupNode cleanup -> {
                var operation = catalog.operations().stream()
                        .filter(candidate -> candidate.id().equals(cleanup.operationId()))
                        .findFirst().orElseThrow();
                actions.add(new Action(cleanup.nodeId().value(), "cleanup", cleanup.operationId().value(),
                        operation.target().value(), operation.protocol().value(), cleanup.deadlineMs(), 1, 1));
            }
            case PlanNode.WaitNode wait -> actions.add(group(node, "wait", wait.waitMs(), 1));
            case PlanNode.AssertionNode ignored -> actions.add(group(node, "assertion", 0, 1));
            case PlanNode.FaultStopNode ignored -> actions.add(group(node, "fault-stop", 0, 1));
        }
    }

    private static Action operation(PlanNode.OperationNode node, int repetitions) {
        return new Action(node.nodeId().value(), "operation", node.operationId().value(),
                node.operation().target().value(), node.operation().protocol().value(),
                node.deadlineMs(), node.retrySpec() == null ? 1 : node.retrySpec().maxAttempts(),
                repetitions);
    }

    private static Action group(PlanNode node, String kind, long deadline, int repetitions) {
        return new Action(node.nodeId().value(), kind, null, null, null, deadline, 0, repetitions);
    }
}
