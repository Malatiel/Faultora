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
}
