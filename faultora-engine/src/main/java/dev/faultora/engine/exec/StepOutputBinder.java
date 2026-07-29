package dev.faultora.engine.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.evidence.MessageEvidence;

import java.util.List;
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
        bindProtocolEvidence(evidence, output);
        return context.withStepOutput(operation.outputBinding(), output);
    }

    /**
     * Publish what the protocol contributed, under a name of its own.
     * <p>
     * Protocols keep adding evidence — a broker offset, a partition, the
     * messages an observation selected — and a step needs to read it: a
     * scenario that publishes a command and then asserts on the event it caused
     * has to name the correlation value it used. Keeping it under
     * {@code protocol} means a protocol that later adds a {@code status} or
     * {@code headers} of its own cannot displace the response ones.
     * <p>
     * The first observed message is bound beside the list because expressions
     * address objects, not array positions, and reaching into a list is
     * otherwise impossible in the dotted form scenarios are written in.
     */
    private static void bindProtocolEvidence(NodeEvidence evidence, ObjectNode output) {
        Map<String, Object> protocolEvidence = evidence.protocolEvidence();
        if (protocolEvidence == null || protocolEvidence.isEmpty()) {
            return;
        }
        ObjectNode protocol = MAPPER.valueToTree(protocolEvidence);
        List<MessageEvidence> messages = MessageEvidence.observedIn(protocolEvidence);
        if (!messages.isEmpty()) {
            protocol.set("message", MAPPER.valueToTree(messages.get(0)));
        }
        output.set("protocol", protocol);
    }
}
