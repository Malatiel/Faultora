package dev.faultora.engine.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.evidence.MessageEvidence;

import java.util.LinkedHashMap;
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
     * <p>
     * Only that first message keeps its payload. The rest are bound by their
     * coordinates, because a scenario reads a value out of the message it is
     * about and the whole list would put a second copy of every payload in the
     * expression context, next to the one the evidence map already holds for
     * the length of the run.
     */
    private static void bindProtocolEvidence(NodeEvidence evidence, ObjectNode output) {
        Map<String, Object> protocolEvidence = evidence.protocolEvidence();
        if (protocolEvidence == null || protocolEvidence.isEmpty()) {
            return;
        }
        List<MessageEvidence> messages = MessageEvidence.observedIn(protocolEvidence);
        Map<String, Object> bindable = new LinkedHashMap<>(protocolEvidence);
        if (!messages.isEmpty()) {
            bindable.put(MessageEvidence.OBSERVED, messages.stream()
                    .map(StepOutputBinder::withoutPayload).toList());
        }
        ObjectNode protocol = MAPPER.valueToTree(bindable);
        if (!messages.isEmpty()) {
            protocol.set("message", MAPPER.valueToTree(messages.get(0)));
        }
        output.set("protocol", protocol);
    }

    /** The same message, identified rather than reproduced. */
    private static MessageEvidence withoutPayload(MessageEvidence message) {
        return new MessageEvidence(
                message.topic(), message.partition(), message.offset(),
                message.timestampMs(), message.key(), message.headers(),
                null, message.digest());
    }
}
