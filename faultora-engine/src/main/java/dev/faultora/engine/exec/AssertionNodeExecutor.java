package dev.faultora.engine.exec;

import dev.faultora.engine.evidence.NodeEvidence;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.result.AssertionResult;

import java.util.List;
import java.util.Map;

/**
 * Evaluates assertion nodes against the evidence of their target step.
 * <p>
 * An assertion that cannot be evaluated fails the node: unevaluatable is not
 * the same as satisfied, and reporting it as a pass would be the worst
 * possible outcome for a reliability tool.
 * <p>
 * Parameters are expressions, resolved here against the same context a step's
 * inputs are. That is what makes an invariant spanning components expressible
 * without a construct of its own: an assertion on a database row compares
 * against the value an HTTP step returned, using the assertion types that
 * already exist. The plan carries a dependency on every step a parameter reads,
 * so the value is bound before the comparison happens.
 */
final class AssertionNodeExecutor {

    private final Map<String, AssertionProvider> providers;
    private final AssertionParameters parameters = new AssertionParameters();

    AssertionNodeExecutor(Map<String, AssertionProvider> providers) {
        this.providers = providers;
    }

    RunResult.NodeResult execute(
            PlanNode.AssertionNode node,
            NodeContext context,
            NodeEvidence ownEvidence,
            ExpressionContext expressionContext,
            long startedAtMs
    ) {
        NodeId nodeId = node.nodeId();
        NodeEvidence targetEvidence = node.targetNode() != null
                ? context.evidence().getOrDefault(node.targetNode(), ownEvidence)
                : ownEvidence;

        AssertionProvider provider = providers.get(node.assertionType());
        if (provider == null) {
            return NodeResults.failed(node,
                    "Unknown assertion type: " + node.assertionType(),
                    NormalizedError.ErrorCategory.VALIDATION, startedAtMs);
        }

        Map<String, Object> params;
        try {
            params = parameters.resolve(node.params(), expressionContext);
        } catch (RuntimeException unresolvable) {
            // An assertion comparing against a value it could not resolve would
            // compare against nothing, and pass or fail for a reason its author
            // never wrote.
            return NodeResults.failed(node,
                    "Cannot resolve this assertion's parameters: " + unresolvable.getMessage(),
                    NormalizedError.ErrorCategory.VALIDATION, startedAtMs);
        }

        String refusal = parameters.refusal(node.params(), params, expressionContext);
        if (refusal != null) {
            return NodeResults.failed(node, refusal,
                    NormalizedError.ErrorCategory.VALIDATION, startedAtMs);
        }

        AssertionResult result = provider.evaluate(
                node.assertionType(), params, targetEvidence,
                new AssertionContext(nodeId.value(), params, node.schema()));

        ownEvidence.durationMs(System.currentTimeMillis() - startedAtMs);
        context.journal().assertionEvaluated(
                nodeId, node.assertionType(), result.outcome().name(), result.message());
        context.evidence().put(nodeId, ownEvidence);

        if (result.outcome() == AssertionResult.Outcome.PASS) {
            context.journal().nodeCompleted(nodeId, ownEvidence.durationMs(), 0, 0);
            return new RunResult.NodeResult(
                    nodeId, NodeResults.typeOf(node), RunResult.Status.PASSED,
                    0, ownEvidence.durationMs(), List.of(result), null);
        }

        // The journal must say the node failed, not merely that an assertion
        // event happened to be FAIL. A reader of the event stream — a CI
        // parser, a future controller — sees the same verdict the engine
        // returns, without having to correlate two events.
        NormalizedError error = new NormalizedError(
                NormalizedError.ErrorCategory.VALIDATION,
                result.outcome() == AssertionResult.Outcome.INDETERMINATE
                        ? "ASSERTION_INDETERMINATE" : "ASSERTION_FAILED",
                result.message(), false,
                Map.of("assertionType", node.assertionType(),
                        "outcome", result.outcome().name()));
        context.journal().nodeFailed(nodeId, error, ownEvidence.durationMs());
        return new RunResult.NodeResult(
                nodeId, NodeResults.typeOf(node), RunResult.Status.FAILED,
                0, ownEvidence.durationMs(), List.of(result), error);
    }
}
