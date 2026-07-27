package dev.faultora.spec.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Top-level scenario document model.
 * Parsed from YAML with apiVersion: faultora.dev/v1alpha1
 *
 * @param apiVersion  must be "faultora.dev/v1alpha1"
 * @param kind        must be "Scenario"
 * @param metadata    scenario metadata
 * @param inputs      declared input parameters
 * @param setup       setup steps
 * @param execute     execution steps
 * @param faults      fault injection steps
 * @param assertions  assertion steps
 * @param cleanup     cleanup steps
 * @param timeout     scenario deadline; once it elapses no further setup,
 *                    execute, fault, or assertion node starts and cleanup runs
 */
public record ScenarioDocument(
        String apiVersion,
        String kind,
        ScenarioMetadata metadata,
        Map<String, InputDeclaration> inputs,
        List<ScenarioStep> setup,
        List<ScenarioStep> execute,
        List<FaultStep> faults,
        List<AssertionStep> assertions,
        List<ScenarioStep> cleanup,
        String timeout
) {
    public static final String SUPPORTED_API_VERSION = "faultora.dev/v1alpha1";
    public static final String EXPECTED_KIND = "Scenario";

    /** Convenience constructor for documents without a scenario deadline. */
    public ScenarioDocument(
            String apiVersion,
            String kind,
            ScenarioMetadata metadata,
            Map<String, InputDeclaration> inputs,
            List<ScenarioStep> setup,
            List<ScenarioStep> execute,
            List<FaultStep> faults,
            List<AssertionStep> assertions,
            List<ScenarioStep> cleanup
    ) {
        this(apiVersion, kind, metadata, inputs, setup, execute, faults,
                assertions, cleanup, null);
    }
}
