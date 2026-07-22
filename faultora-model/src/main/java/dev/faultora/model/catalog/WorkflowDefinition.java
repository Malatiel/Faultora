package dev.faultora.model.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.WorkflowId;

import java.util.List;
import java.util.Map;

/**
 * Describes a workflow (sequence of operations) from the source specification.
 *
 * @param id          stable workflow identifier
 * @param name        human-readable name
 * @param steps       ordered list of workflow steps
 * @param metadata    workflow-level metadata
 */
public record WorkflowDefinition(
        WorkflowId id,
        String name,
        List<WorkflowStep> steps,
        Map<String, Object> metadata
) {
    /**
     * A single step in a workflow.
     *
     * @param stepId       stable step identifier within the workflow
     * @param operationId  the operation invoked by this step
     * @param description  human-readable description
     * @param dependsOn    step IDs that must complete before this step
     * @param metadata     step-specific metadata
     */
    public record WorkflowStep(
            String stepId,
            OperationId operationId,
            String description,
            List<String> dependsOn,
            Map<String, Object> metadata
    ) {}
}
