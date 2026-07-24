package dev.faultora.spec.validator;

import dev.faultora.spec.model.*;
import dev.faultora.spec.parser.Diagnostic;
import dev.faultora.spec.parser.ParseResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a parsed scenario document for structural correctness.
 * Does not resolve catalog references (that's the compiler's job).
 */
public class ScenarioValidator {

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

        if (diagnostics.stream().anyMatch(Diagnostic::isError)) {
            return new ParseResult<>(null, diagnostics);
        }
        return new ParseResult<>(document, diagnostics);
    }

    private void validateScenarioStepIds(List<ScenarioStep> steps, String section,
                                          Set<String> allIds, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (ScenarioStep step : steps) {
            if (step.id() == null || step.id().isBlank()) {
                diagnostics.add(Diagnostic.error(section, "Step must have a non-empty id"));
            } else if (!allIds.add(step.id())) {
                diagnostics.add(Diagnostic.error(section + "." + step.id(),
                        "Duplicate step id: " + step.id()));
            }
            // Children of parallel groups share the global step-ID namespace.
            validateScenarioStepIds(step.steps(), section, allIds, diagnostics);
        }
    }

    private void validateFaultStepIds(List<FaultStep> steps, String section,
                                       Set<String> allIds, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (FaultStep step : steps) {
            if (step.id() == null || step.id().isBlank()) {
                diagnostics.add(Diagnostic.error(section, "Fault step must have a non-empty id"));
            } else if (!allIds.add(step.id())) {
                diagnostics.add(Diagnostic.error(section + "." + step.id(),
                        "Duplicate step id: " + step.id()));
            }
        }
    }

    private void validateAssertionStepIds(List<AssertionStep> steps, String section,
                                           Set<String> allIds, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (AssertionStep step : steps) {
            if (step.id() == null || step.id().isBlank()) {
                diagnostics.add(Diagnostic.error(section, "Assertion step must have a non-empty id"));
            } else if (!allIds.add(step.id())) {
                diagnostics.add(Diagnostic.error(section + "." + step.id(),
                        "Duplicate step id: " + step.id()));
            }
        }
    }

    private void validateDependsOn(List<ScenarioStep> steps, Set<String> allIds,
                                    String section, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (ScenarioStep step : steps) {
            if (step.dependsOn() != null) {
                for (String dep : step.dependsOn()) {
                    if (!allIds.contains(dep)) {
                        diagnostics.add(Diagnostic.error(section + "." + step.id() + ".dependsOn",
                                "References unknown step: " + dep));
                    }
                }
            }
        }
    }

    private void validateFaultReferences(
            List<FaultStep> steps, Set<String> allIds, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (FaultStep step : steps) {
            validateReferences(step.dependsOn(), allIds,
                    "faults." + step.id() + ".dependsOn", diagnostics);
        }
    }

    private void validateAssertionReferences(
            List<AssertionStep> steps, Set<String> allIds, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (AssertionStep step : steps) {
            validateReferences(step.dependsOn(), allIds,
                    "assertions." + step.id() + ".dependsOn", diagnostics);
            if (step.targetStep() != null && !step.targetStep().isBlank()
                    && !allIds.contains(step.targetStep())) {
                diagnostics.add(Diagnostic.error(
                        "assertions." + step.id() + ".targetStep",
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
        for (String reference : references) {
            if (!allIds.contains(reference)) {
                diagnostics.add(Diagnostic.error(path, "References unknown step: " + reference));
            }
        }
    }

    private void validateSteps(
            List<ScenarioStep> steps, String section, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (ScenarioStep step : steps) {
            String type = step.type() == null ? "operation" : step.type();
            String path = section + "." + step.id();
            if (!"operation".equals(type) && !"wait".equals(type) && !"parallel".equals(type)) {
                diagnostics.add(Diagnostic.error(path + ".type",
                        "Unsupported step type in this release: " + type));
            }
            if ("parallel".equals(type)) {
                validateParallelStep(step, section, path, diagnostics);
            } else if (step.steps() != null && !step.steps().isEmpty()) {
                diagnostics.add(Diagnostic.error(path + ".steps",
                        "Nested steps are only allowed on parallel steps"));
            }
            if ("wait".equals(type) && !isPositiveDuration(step.timeout())) {
                diagnostics.add(Diagnostic.error(path + ".timeout",
                        "Wait step requires a positive duration"));
            } else if (step.timeout() != null && !step.timeout().isBlank()
                    && !isPositiveDuration(step.timeout())) {
                diagnostics.add(Diagnostic.error(path + ".timeout",
                        "Invalid timeout: " + step.timeout()));
            }
            if (step.retry() != null) {
                ScenarioStep.RetryPolicy retry = step.retry();
                if (retry.maxAttempts() < 1
                        || retry.backoffMs() < 0
                        || retry.backoffMultiplier() < 1
                        || retry.maxBackoffMs() < 0) {
                    diagnostics.add(Diagnostic.error(path + ".retry",
                            "Retry values are out of range"));
                }
                if (step.expectError() && retry.maxAttempts() > 1) {
                    diagnostics.add(Diagnostic.error(path + ".retry",
                            "expectError cannot be combined with retry: "
                                    + "a step that must fail has nothing to retry toward"));
                }
            }
        }
    }

    private static final java.util.regex.Pattern OUTPUT_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private void validateOutputBindings(ScenarioDocument document, List<Diagnostic> diagnostics) {
        Set<String> names = new HashSet<>();
        validateOutputBindings(document.setup(), "setup", names, diagnostics);
        validateOutputBindings(document.execute(), "execute", names, diagnostics);
        validateOutputBindings(document.cleanup(), "cleanup", names, diagnostics);
    }

    private void validateOutputBindings(List<ScenarioStep> steps, String section,
                                         Set<String> names, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (ScenarioStep step : steps) {
            String outputAs = step.outputAs();
            if (outputAs == null || outputAs.isBlank()) continue;
            String path = section + "." + step.id() + ".outputAs";
            if (!OUTPUT_NAME.matcher(outputAs).matches()) {
                diagnostics.add(Diagnostic.error(path,
                        "Output name must match [A-Za-z_][A-Za-z0-9_-]*: " + outputAs));
            } else if (!names.add(outputAs)) {
                diagnostics.add(Diagnostic.error(path,
                        "Duplicate output name: " + outputAs));
            }
            if ("cleanup".equals(section)) {
                diagnostics.add(Diagnostic.warning(path,
                        "outputAs on a cleanup step has no consumers"));
            }
        }
        for (ScenarioStep step : steps) {
            validateOutputBindings(step.steps(), section, names, diagnostics);
        }
    }

    private void validateFaultSteps(List<FaultStep> steps, List<Diagnostic> diagnostics) {
        if (steps == null) return;
        for (FaultStep step : steps) {
            String path = "faults." + step.id();
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

    private void validateParallelStep(
            ScenarioStep step, String section, String path, List<Diagnostic> diagnostics) {
        if (!"cleanup".equals(section) && !"execute".equals(section) && !"setup".equals(section)) {
            return;
        }
        if ("cleanup".equals(section)) {
            diagnostics.add(Diagnostic.error(path,
                    "Parallel steps are not allowed in cleanup"));
            return;
        }
        if (step.steps() == null || step.steps().isEmpty()) {
            diagnostics.add(Diagnostic.error(path + ".steps",
                    "Parallel step requires at least one child step"));
            return;
        }
        for (ScenarioStep child : step.steps()) {
            String childPath = path + "." + child.id();
            String childType = child.type() == null ? "operation" : child.type();
            if (!"operation".equals(childType)) {
                diagnostics.add(Diagnostic.error(childPath + ".type",
                        "Parallel children must be operation steps, got: " + childType));
            }
            if (child.dependsOn() != null && !child.dependsOn().isEmpty()) {
                diagnostics.add(Diagnostic.error(childPath + ".dependsOn",
                        "Parallel children start together and cannot declare dependsOn"));
            }
            if (child.steps() != null && !child.steps().isEmpty()) {
                diagnostics.add(Diagnostic.error(childPath + ".steps",
                        "Parallel groups cannot be nested"));
            }
            if (child.retry() != null) {
                ScenarioStep.RetryPolicy retry = child.retry();
                if (retry.maxAttempts() < 1
                        || retry.backoffMs() < 0
                        || retry.backoffMultiplier() < 1
                        || retry.maxBackoffMs() < 0) {
                    diagnostics.add(Diagnostic.error(childPath + ".retry",
                            "Retry values are out of range"));
                }
                if (child.expectError() && retry.maxAttempts() > 1) {
                    diagnostics.add(Diagnostic.error(childPath + ".retry",
                            "expectError cannot be combined with retry: "
                                    + "a step that must fail has nothing to retry toward"));
                }
            }
        }
    }

    private boolean isPositiveDuration(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.trim().toLowerCase();
        long multiplier = 1;
        if (normalized.endsWith("ms")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("s")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = 1000;
        } else if (normalized.endsWith("m")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = 60_000;
        }
        try {
            return Math.multiplyExact(Long.parseLong(normalized), multiplier) > 0;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return false;
        }
    }
}
