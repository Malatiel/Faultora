package dev.faultora.engine.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.spec.expression.ExpressionContext;

import java.util.Map;

/**
 * Publishes a completed step's response to later steps.
 * <p>
 * Only steps that asked for it with {@code outputAs} are published, and only
 * when they passed: binding the output of a failed step would let a scenario
 * build on evidence it never actually obtained.
 */
public final class StepOutputBinder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StepOutputBinder() {
    }

    /**
     * Return the context extended with {@code steps.<outputAs>} for this node,
     * or the unchanged context when the node publishes nothing.
     */
    public static ExpressionContext bind(
            PlanNode node,
            RunResult.NodeResult result,
            Map<NodeId, NodeEvidence> evidenceByNode,
            ExpressionContext context
    ) {
        if (!(node instanceof PlanNode.OperationNode operation)
                || operation.outputBinding() == null || operation.outputBinding().isBlank()
                || operation.operation() == null
                || result.status() != RunResult.Status.PASSED) {
            return context;
        }
        NodeEvidence evidence = evidenceByNode.get(node.nodeId());
        if (evidence == null) {
            return context;
        }
        var output = MAPPER.createObjectNode();
        output.put("status", evidence.statusCode().orElse(-1));
        evidence.responseJson().ifPresent(body -> output.set("body", body));
        output.set("headers", MAPPER.valueToTree(evidence.responseHeaders()));
        return context.withStepOutput(operation.outputBinding(), output);
    }
}
