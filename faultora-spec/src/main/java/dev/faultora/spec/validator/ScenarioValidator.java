package dev.faultora.spec.validator;

import dev.faultora.spec.model.*;
import dev.faultora.spec.parser.Diagnostic;
import dev.faultora.spec.parser.DurationSyntax;
import dev.faultora.spec.parser.ParseResult;
import dev.faultora.schema.GenerationStrategy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a parsed scenario document for structural correctness.
 * Does not resolve catalog references (that's the compiler's job).
 */
public class ScenarioValidator {

    /** Step types that group child steps instead of invoking an operation. */
    private static final Set<String> GROUP_TYPES = Set.of("parallel", "repeat", "eventually");

    /**
     * Validate a scenario document.
     *
     * @param document the parsed scenario document
     * @return validation result with diagnostics
     */
    public ParseResult<ScenarioDocument> validate(ScenarioDocument document) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        // Validate step IDs are unique across all sections
        Set<String> stepIds = new HashSet<>();
        validateScenarioStepIds(document.setup(), "setup", stepIds, diagnostics);
        validateScenarioStepIds(document.execute(), "execute", stepIds, diagnostics);
        validateFaultStepIds(document.faults(), "faults", stepIds, diagnostics);
        validateAssertionStepIds(document.assertions(), "assertions", stepIds, diagnostics);
        validateScenarioStepIds(document.cleanup(), "cleanup", stepIds, diagnostics);

        // Validate execute section is not empty
        if (document.execute() == null || document.execute().isEmpty()) {
            diagnostics.add(Diagnostic.error("execute", "Execute section must not be empty"));
        }
        if (document.inputs() != null && document.inputs().values().stream().anyMatch(value -> value == null)) {
            diagnostics.add(Diagnostic.error("inputs", "Input declarations must be objects"));
        }

        // Validate step references in dependsOn
        validateDependsOn(document.setup(), stepIds, "setup", diagnostics);
        validateDependsOn(document.execute(), stepIds, "execute", diagnostics);
        validateDependsOn(document.cleanup(), stepIds, "cleanup", diagnostics);
        validateFaultReferences(document.faults(), stepIds, diagnostics);
        validateAssertionReferences(document.assertions(), stepIds, diagnostics);
        validateSteps(document.setup(), "setup", diagnostics);
        validateSteps(document.execute(), "execute", diagnostics);
        validateSteps(document.cleanup(), "cleanup", diagnostics);
        validateFaultSteps(document.faults(), diagnostics);
        validateOutputBindings(document, diagnostics);

        if (document.timeout() != null && !document.timeout().isBlank()
                && !isPositiveDuration(document.timeout())) {
            diagnostics.add(Diagnostic.error("timeout",
                    "Scenario timeout must be a positive duration: " + document.timeout()));
        }

        if (diagnostics.stream().anyMatch(Diagnostic::isError)) {
            return new ParseResult<>(null, diagnostics);
        }
        return new ParseResult<>(document, diagnostics);
    }

    private void validateScenarioStepIds(List<ScenarioStep> steps, String section,
                                          Set<String> allIds, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (int index = 0; index < steps.size(); index++) {
            ScenarioStep step = steps.get(index);
            String path = indexed(section, index);
            if (step == null) {
                diagnostics.add(Diagnostic.error(path, "Step must be an object"));
                continue;
            }
            validateId(step.id(), path, "Step", allIds, diagnostics);
            // Children of parallel groups share the global step-ID namespace.
            validateScenarioStepIds(step.steps(), path + ".steps", allIds, diagnostics);
        }
    }

    private void validateFaultStepIds(List<FaultStep> steps, String section,
                                       Set<String> allIds, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (int index = 0; index < steps.size(); index++) {
            FaultStep step = steps.get(index);
            String path = indexed(section, index);
            if (step == null) {
                diagnostics.add(Diagnostic.error(path, "Fault step must be an object"));
            } else {
                validateId(step.id(), path, "Fault step", allIds, diagnostics);
            }
        }
    }

    private void validateAssertionStepIds(List<AssertionStep> steps, String section,
                                           Set<String> allIds, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (int index = 0; index < steps.size(); index++) {
            AssertionStep step = steps.get(index);
            String path = indexed(section, index);
            if (step == null) {
                diagnostics.add(Diagnostic.error(path, "Assertion step must be an object"));
            } else {
                validateId(step.id(), path, "Assertion step", allIds, diagnostics);
            }
        }
    }

    private void validateId(String id, String path, String label,
                            Set<String> allIds, List<Diagnostic> diagnostics) {
        if (id == null || id.isBlank()) {
            diagnostics.add(Diagnostic.error(path + ".id", label + " must have a non-empty id"));
        } else if (!allIds.add(id)) {
            diagnostics.add(Diagnostic.error(path + ".id", "Duplicate step id: " + id));
        }
    }

    private void validateDependsOn(List<ScenarioStep> steps, Set<String> allIds,
                                    String section, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (int index = 0; index < steps.size(); index++) {
            ScenarioStep step = steps.get(index);
            if (step != null) {
                validateReferences(step.dependsOn(), allIds,
                        indexed(section, index) + ".dependsOn", diagnostics);
            }
        }
    }

    private void validateFaultReferences(
            List<FaultStep> steps, Set<String> allIds, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (int index = 0; index < steps.size(); index++) {
            FaultStep step = steps.get(index);
            if (step != null) {
                validateReferences(step.dependsOn(), allIds,
                        indexed("faults", index) + ".dependsOn", diagnostics);
            }
        }
    }

    private void validateAssertionReferences(
            List<AssertionStep> steps, Set<String> allIds, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (int index = 0; index < steps.size(); index++) {
            AssertionStep step = steps.get(index);
            if (step == null) continue;
            String path = indexed("assertions", index);
            validateReferences(step.dependsOn(), allIds, path + ".dependsOn", diagnostics);
            if (step.targetStep() != null && !step.targetStep().isBlank()
                    && !allIds.contains(step.targetStep())) {
                diagnostics.add(Diagnostic.error(
                        path + ".targetStep",
                        "References unknown step: " + step.targetStep()));
            }
        }
    }

    private void validateReferences(
            List<String> references,
            Set<String> allIds,
            String path,
            List<Diagnostic> diagnostics
    ) {
        if (references == null) return;
        for (int index = 0; index < references.size(); index++) {
            String reference = references.get(index);
            if (!allIds.contains(reference)) {
                diagnostics.add(Diagnostic.error(indexed(path, index), "References unknown step: " + reference));
            }
        }
    }

    private void validateSteps(
            List<ScenarioStep> steps, String section, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (int index = 0; index < steps.size(); index++) {
            ScenarioStep step = steps.get(index);
            if (step == null) continue;
            String type = step.type() == null ? "operation" : step.type();
            String path = indexed(section, index);
            if (!"operation".equals(type) && !"wait".equals(type)
                    && !GROUP_TYPES.contains(type)) {
                diagnostics.add(Diagnostic.error(path + ".type",
                        "Unsupported step type in this release: " + type));
            }
            if (GROUP_TYPES.contains(type)) {
                validateGroupStep(step, section, path, type, diagnostics);
                switch (type) {
                    case "parallel" -> validateParallelStep(step, section, path, diagnostics);
                    case "repeat" -> validateRepeatStep(step, section, path, diagnostics);
                    case "eventually" -> validateEventuallyStep(step, section, path, diagnostics);
                    default -> { /* unreachable: GROUP_TYPES is closed */ }
                }
            } else if (step.steps() != null && !step.steps().isEmpty()) {
                diagnostics.add(Diagnostic.error(path + ".steps",
                        "Nested steps are only allowed on parallel, repeat, "
                                + "and eventually steps"));
            }
            if (!GROUP_TYPES.contains(type)
                    && (step.count() != null || step.forEach() != null)) {
                diagnostics.add(Diagnostic.error(path,
                        "count and forEach are only allowed on repeat steps"));
            }
            if (!"eventually".equals(type)
                    && (step.interval() != null || step.until() != null)) {
                diagnostics.add(Diagnostic.error(path,
                        "interval and until are only allowed on eventually steps"));
            }
            validateGenerate(step, type, path, diagnostics);
            if ("wait".equals(type) && !isPositiveDuration(step.timeout())) {
                diagnostics.add(Diagnostic.error(path + ".timeout",
                        "Wait step requires a positive duration"));
            } else if (step.timeout() != null && !step.timeout().isBlank()
                    && !isPositiveDuration(step.timeout())) {
                diagnostics.add(Diagnostic.error(path + ".timeout",
                        "Invalid timeout: " + step.timeout()));
            }
            if (!GROUP_TYPES.contains(type)) {
                validateRetryPolicy(step, path, diagnostics);
            }
        }
    }

    private static final java.util.regex.Pattern OUTPUT_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private void validateOutputBindings(ScenarioDocument document, List<Diagnostic> diagnostics) {
        Set<String> names = new HashSet<>();
        validateOutputBindings(document.setup(), "setup", false, names, diagnostics);
        validateOutputBindings(document.execute(), "execute", false, names, diagnostics);
        validateOutputBindings(document.cleanup(), "cleanup", true, names, diagnostics);
    }

    private void validateOutputBindings(List<ScenarioStep> steps, String section, boolean cleanup,
                                         Set<String> names, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (int index = 0; index < steps.size(); index++) {
            ScenarioStep step = steps.get(index);
            if (step == null) continue;
            String outputAs = step.outputAs();
            if (outputAs == null || outputAs.isBlank()) continue;
            String path = indexed(section, index) + ".outputAs";
            if (!OUTPUT_NAME.matcher(outputAs).matches()) {
                diagnostics.add(Diagnostic.error(path,
                        "Output name must match [A-Za-z_][A-Za-z0-9_-]*: " + outputAs));
            } else if (!names.add(outputAs)) {
                diagnostics.add(Diagnostic.error(path,
                        "Duplicate output name: " + outputAs));
            }
            if (cleanup) {
                diagnostics.add(Diagnostic.warning(path,
                        "outputAs on a cleanup step has no consumers"));
            }
        }
        for (int index = 0; index < steps.size(); index++) {
            ScenarioStep step = steps.get(index);
            if (step != null) {
                validateOutputBindings(step.steps(), indexed(section, index) + ".steps", cleanup,
                        names, diagnostics);
            }
        }
    }

    private void validateFaultSteps(List<FaultStep> steps, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (int index = 0; index < steps.size(); index++) {
            FaultStep step = steps.get(index);
            if (step == null) continue;
            String path = indexed("faults", index);
            if (step.faultType() == null || step.faultType().isBlank()) {
                diagnostics.add(Diagnostic.error(path + ".faultType",
                        "Fault step requires a faultType"));
            }
            if (!isPositiveDuration(step.duration())) {
                diagnostics.add(Diagnostic.error(path + ".duration",
                        "Fault step requires a positive duration (e.g. 500ms, 5s)"));
            }
        }
    }

    /**
     * Rules shared by every grouping step: groups schedule their children and
     * never invoke an operation themselves, so operation-level fields on the
     * group are rejected instead of being silently ignored.
     */
    private void validateGroupStep(
            ScenarioStep step, String section, String path, String type,
            List<Diagnostic> diagnostics) {
        if ("cleanup".equals(section)) {
            diagnostics.add(Diagnostic.error(path,
                    capitalize(type) + " steps are not allowed in cleanup"));
        }
        if (step.operationId() != null && !step.operationId().isBlank()) {
            diagnostics.add(Diagnostic.error(path + ".operationId",
                    capitalize(type) + " steps invoke their children, not an operation"));
        }
        if (step.retry() != null) {
            diagnostics.add(Diagnostic.error(path + ".retry",
                    "Retry belongs on the child steps of a " + type + " group"));
        }
        if (step.expectError()) {
            diagnostics.add(Diagnostic.error(path + ".expectError",
                    "expectError belongs on the child steps of a " + type + " group"));
        }
        if (step.outputAs() != null && !step.outputAs().isBlank()) {
            diagnostics.add(Diagnostic.error(path + ".outputAs",
                    "outputAs belongs on the child steps of a " + type + " group"));
        }
        if (step.inputs() != null && !step.inputs().isEmpty()) {
            diagnostics.add(Diagnostic.error(path + ".inputs",
                    "inputs belong on the child steps of a " + type + " group"));
        }
    }

    private void validateParallelStep(
            ScenarioStep step, String section, String path, List<Diagnostic> diagnostics) {
        if ("cleanup".equals(section)) {
            return;
        }
        if (step.steps() == null || step.steps().isEmpty()) {
            diagnostics.add(Diagnostic.error(path + ".steps",
                    "Parallel step requires at least one child step"));
            return;
        }
        for (int index = 0; index < step.steps().size(); index++) {
            validateGroupChild(step.steps().get(index), indexed(path + ".steps", index), "Parallel",
                    "Parallel children start together and cannot declare dependsOn",
                    diagnostics);
        }
    }

    /**
     * A repeat group runs its children once per iteration. The iteration count
     * is known before execution: either a fixed {@code count} or a literal
     * {@code forEach} list, so the compiler can bound the request budget.
     */
    private void validateRepeatStep(
            ScenarioStep step, String section, String path, List<Diagnostic> diagnostics) {
        if ("cleanup".equals(section)) {
            return;
        }
        boolean hasCount = step.count() != null;
        boolean hasForEach = step.forEach() != null;
        if (hasCount == hasForEach) {
            diagnostics.add(Diagnostic.error(path,
                    "Repeat step requires exactly one of count or forEach"));
        } else if (hasCount) {
            if (step.count() < 1 || step.count() > ScenarioLimits.MAX_REPEAT_ITERATIONS) {
                diagnostics.add(Diagnostic.error(path + ".count",
                        "Repeat count must be between 1 and " + ScenarioLimits.MAX_REPEAT_ITERATIONS
                                + ", got: " + step.count()));
            }
        } else {
            if (step.forEach().isEmpty()) {
                diagnostics.add(Diagnostic.error(path + ".forEach",
                        "Repeat forEach requires at least one item"));
            } else if (step.forEach().size() > ScenarioLimits.MAX_REPEAT_ITERATIONS) {
                diagnostics.add(Diagnostic.error(path + ".forEach",
                        "Repeat forEach must not exceed " + ScenarioLimits.MAX_REPEAT_ITERATIONS
                                + " items, got: " + step.forEach().size()));
            }
        }
        if (step.steps() == null || step.steps().isEmpty()) {
            diagnostics.add(Diagnostic.error(path + ".steps",
                    "Repeat step requires at least one child step"));
            return;
        }
        for (int index = 0; index < step.steps().size(); index++) {
            validateGroupChild(step.steps().get(index), indexed(path + ".steps", index), "Repeat",
                    "Repeat children run in declaration order and cannot declare dependsOn",
                    diagnostics);
        }
    }

    /**
     * An eventually group polls one operation until every {@code until}
     * condition holds in the same poll, or the timeout budget is spent.
     */
    private void validateEventuallyStep(
            ScenarioStep step, String section, String path, List<Diagnostic> diagnostics) {
        if ("cleanup".equals(section)) {
            return;
        }
        if (!isPositiveDuration(step.timeout())) {
            diagnostics.add(Diagnostic.error(path + ".timeout",
                    "Eventually step requires a positive timeout budget (e.g. 10s)"));
        }
        if (step.interval() != null && !isPositiveDuration(step.interval())) {
            diagnostics.add(Diagnostic.error(path + ".interval",
                    "Eventually interval must be a positive duration: " + step.interval()));
        }
        if (step.until() == null || step.until().isEmpty()) {
            diagnostics.add(Diagnostic.error(path + ".until",
                    "Eventually step requires at least one until condition"));
        } else {
            for (int i = 0; i < step.until().size(); i++) {
                ScenarioStep.Condition condition = step.until().get(i);
                if (condition == null) {
                    diagnostics.add(Diagnostic.error(indexed(path + ".until", i), "Until condition must be an object"));
                } else if (condition.assertionType() == null || condition.assertionType().isBlank()) {
                    diagnostics.add(Diagnostic.error(path + ".until[" + i + "].assertionType",
                            "Until condition requires an assertionType"));
                }
            }
        }
        if (step.steps() == null || step.steps().size() != 1) {
            diagnostics.add(Diagnostic.error(path + ".steps",
                    "Eventually step requires exactly one child operation step"));
            return;
        }
        ScenarioStep child = step.steps().get(0);
        if (child == null) return;
        String childPath = indexed(path + ".steps", 0);
        validateGroupChild(child, childPath, "Eventually",
                "The polled step cannot declare dependsOn", diagnostics);
        if (child.retry() != null) {
            diagnostics.add(Diagnostic.error(childPath + ".retry",
                    "The polled step cannot retry: the eventually budget already "
                            + "governs repeated attempts"));
        }
        if (child.expectError()) {
            diagnostics.add(Diagnostic.error(childPath + ".expectError",
                    "The polled step cannot declare expectError: a failed poll is "
                            + "simply an unsatisfied condition"));
        }
    }

    private void validateGroupChild(
            ScenarioStep child, String childPath, String groupLabel,
            String dependsOnMessage, List<Diagnostic> diagnostics) {
        if (child == null) return;
        String childType = child.type() == null ? "operation" : child.type();
        if (!"operation".equals(childType)) {
            diagnostics.add(Diagnostic.error(childPath + ".type",
                    groupLabel + " children must be operation steps, got: " + childType));
        }
        if (child.dependsOn() != null && !child.dependsOn().isEmpty()) {
            diagnostics.add(Diagnostic.error(childPath + ".dependsOn", dependsOnMessage));
        }
        if (child.steps() != null && !child.steps().isEmpty()) {
            diagnostics.add(Diagnostic.error(childPath + ".steps",
                    groupLabel + " groups cannot be nested"));
        }
        validateRetryPolicy(child, childPath, diagnostics);
        validateGenerate(child, "operation", childPath, diagnostics);
    }

    /**
     * Generated request values are meaningful only where an operation with
     * declared schemas is invoked.
     */
    private void validateGenerate(
            ScenarioStep step, String type, String path, List<Diagnostic> diagnostics) {
        ScenarioStep.Generate generate = step.generate();
        if (generate == null) {
            return;
        }
        if (!"operation".equals(type)) {
            diagnostics.add(Diagnostic.error(path + ".generate",
                    "generate is only allowed on operation steps"));
            return;
        }
        if (generate.fields() == null || generate.fields().isEmpty()) {
            diagnostics.add(Diagnostic.error(path + ".generate.fields",
                    "generate requires at least one input name, for example [body]"));
        } else {
            Set<String> seen = new HashSet<>();
            for (String field : generate.fields()) {
                if (field == null || field.isBlank()) {
                    diagnostics.add(Diagnostic.error(path + ".generate.fields",
                            "generated input names must not be blank"));
                } else if (!seen.add(field)) {
                    diagnostics.add(Diagnostic.error(path + ".generate.fields",
                            "duplicate generated input: " + field));
                }
            }
        }
        if (generate.strategy() != null
                && GenerationStrategy.from(generate.strategy()) == null) {
            diagnostics.add(Diagnostic.error(path + ".generate.strategy",
                    "Unknown generation strategy: " + generate.strategy()
                            + ". Supported: valid, boundary, invalid"));
        }
    }

    /** The one retry rule, applied to plain steps and to group children alike. */
    private void validateRetryPolicy(
            ScenarioStep step, String path, List<Diagnostic> diagnostics) {
        ScenarioStep.RetryPolicy retry = step.retry();
        if (retry == null) {
            return;
        }
        if (!retry.isWithinRange()) {
            diagnostics.add(Diagnostic.error(path + ".retry",
                    "Retry values are out of range"));
        }
        if (step.expectError() && retry.retriesAtAll()) {
            diagnostics.add(Diagnostic.error(path + ".retry",
                    "expectError cannot be combined with retry: "
                            + "a step that must fail has nothing to retry toward"));
        }
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String indexed(String field, int index) {
        return field + '[' + index + ']';
    }

    private boolean isPositiveDuration(String value) {
        return DurationSyntax.isPositive(value);
    }
}
