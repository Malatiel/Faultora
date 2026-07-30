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
import dev.faultora.spi.result.OperationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls one operation until its conditions hold, or the budget is spent.
 * <p>
 * A failed request is an unsatisfied poll, not a failure: a system that is
 * still catching up may legitimately refuse, time out, or answer with the old
 * state before it converges. The budget — and only the budget — decides when
 * to give up, which is what keeps the block from hanging.
 */
public final class EventuallyGroupExecutor {

    private final OperationInvoker invoker;
    private final InputResolver inputResolver = new InputResolver();
    private final AssertionParameters parameters = new AssertionParameters();
    private final Map<String, AssertionProvider> assertionProviders;

    public EventuallyGroupExecutor(
            OperationInvoker invoker, Map<String, AssertionProvider> assertionProviders) {
        this.invoker = invoker;
        this.assertionProviders = assertionProviders;
    }

    public GroupOutcome execute(
            PlanNode.EventuallyNode group,
            NodeContext context,
            ExpressionContext expressionContext
    ) {
        long groupStart = System.currentTimeMillis();
        NodeId groupId = group.nodeId();
        PlanNode.OperationNode child = group.child();

        context.journal().nodeStarted(groupId, "eventually", null);

        String unknownType = firstUnknownConditionType(group);
        if (unknownType != null) {
            NormalizedError error = new NormalizedError(
                    NormalizedError.ErrorCategory.VALIDATION, "VALIDATION",
                    "Unknown assertion type in until condition: " + unknownType,
                    false, Map.of());
            long durationMs = System.currentTimeMillis() - groupStart;
            context.journal().nodeFailed(groupId, error, durationMs);
            return new GroupOutcome(new RunResult.NodeResult(
                    groupId, "eventually", RunResult.Status.FAILED,
                    -1, durationMs, List.of(), error), Map.of());
        }

        context.journal().nodeStarted(child.nodeId(), "operation", child.operationId());

        // Every poll asks the identical question: inputs and the conditions'
        // own parameters are resolved once, together. Resolving a condition per
        // poll would give the block a second place where a poll's meaning could
        // drift.
        Map<String, Object> inputs;
        List<Map<String, Object>> conditionParams;
        try {
            inputs = inputResolver.resolve(child, context, expressionContext);
            conditionParams = resolveConditionParams(group, expressionContext);
        } catch (RuntimeException unresolvable) {
            // Compilation proves generated values are satisfiable, but a
            // schema with alternatives can still surprise a run. Failing the
            // group keeps cleanup and the run's terminal event intact.
            return failed(context, groupId, child, new NormalizedError(
                    NormalizedError.ErrorCategory.VALIDATION, "INPUTS_UNRESOLVABLE",
                    "Cannot resolve what this block polls with: " + unresolvable.getMessage(),
                    false, Map.of()), System.currentTimeMillis() - groupStart);
        }

        List<AssertionResult> conditionResults = List.of();
        NodeEvidence evidence = null;
        boolean satisfied = false;
        int attempt = 0;

        while (attempt < group.maxPolls() && !context.cancelled()) {
            attempt++;

            evidence = new NodeEvidence(context.connectorContext().evidencePolicy());
            OperationResult result = invoker.invoke(child, context, inputs);
            OperationInvoker.populateEvidence(evidence, result);
            // Every poll is an execution, so every poll's evidence reaches the
            // journal. A polling block is the usual way to observe an
            // asynchronous effect, and its observations were invisible while
            // this was the only execution path that skipped it.
            context.journal().evidenceOf(child.nodeId(), evidence);

            conditionResults = evaluateConditions(group, conditionParams, evidence);
            satisfied = !evidence.hasError() && conditionResults.stream()
                    .allMatch(condition -> condition.outcome() == AssertionResult.Outcome.PASS);

            long elapsedMs = System.currentTimeMillis() - groupStart;
            context.journal().conditionPolled(
                    groupId, attempt, group.maxPolls(), elapsedMs, satisfied,
                    pollDetail(evidence, conditionResults));

            if (satisfied) {
                break;
            }
            long remainingMs = group.timeoutMs() - elapsedMs;
            if (remainingMs <= 0 || attempt >= group.maxPolls()) {
                break;
            }
            try {
                Waits.sleep(Math.min(group.intervalMs(), remainingMs), context.cancellation());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                context.cancellation().set(true);
                break;
            }
        }

        long durationMs = System.currentTimeMillis() - groupStart;
        int statusCode = evidence == null ? -1 : evidence.statusCode().orElse(-1);
        if (evidence != null) {
            context.evidence().put(child.nodeId(), evidence);
        }
        recordConditionOutcomes(group, context, conditionResults);

        Map<NodeId, RunResult.NodeResult> childResults = new LinkedHashMap<>();
        if (satisfied) {
            context.journal().nodeCompleted(child.nodeId(), durationMs, statusCode, 0);
            childResults.put(child.nodeId(), new RunResult.NodeResult(
                    child.nodeId(), "operation", RunResult.Status.PASSED,
                    statusCode, durationMs, List.of(), null));
            context.journal().nodeCompleted(groupId, durationMs, statusCode, 0);
            return new GroupOutcome(new RunResult.NodeResult(
                    groupId, "eventually", RunResult.Status.PASSED,
                    statusCode, durationMs, conditionResults, null), childResults);
        }

        NormalizedError error = new NormalizedError(
                context.cancelled()
                        ? NormalizedError.ErrorCategory.CANCELLED
                        : NormalizedError.ErrorCategory.TIMEOUT,
                context.cancelled() ? "CANCELLED" : "EVENTUALLY_TIMEOUT",
                "Conditions were not satisfied within " + group.timeoutMs()
                        + "ms after " + attempt + " poll" + (attempt == 1 ? "" : "s")
                        + ": " + pollDetail(evidence, conditionResults),
                false, Map.of("polls", attempt, "timeoutMs", group.timeoutMs()));
        context.journal().nodeFailed(child.nodeId(), error, durationMs);
        childResults.put(child.nodeId(), new RunResult.NodeResult(
                child.nodeId(), "operation", RunResult.Status.FAILED,
                statusCode, durationMs, List.of(), error));
        context.journal().nodeFailed(groupId, error, durationMs);
        return new GroupOutcome(new RunResult.NodeResult(
                groupId, "eventually", RunResult.Status.FAILED,
                statusCode, durationMs, conditionResults, error), childResults);
    }

    /** The group and its polled step both failed, for reasons outside polling. */
    private GroupOutcome failed(
            NodeContext context, NodeId groupId, PlanNode.OperationNode child,
            NormalizedError error, long durationMs) {
        context.journal().nodeFailed(child.nodeId(), error, durationMs);
        context.journal().nodeFailed(groupId, error, durationMs);
        Map<NodeId, RunResult.NodeResult> childResults = new LinkedHashMap<>();
        childResults.put(child.nodeId(), new RunResult.NodeResult(
                child.nodeId(), "operation", RunResult.Status.FAILED,
                -1, durationMs, List.of(), error));
        return new GroupOutcome(new RunResult.NodeResult(
                groupId, "eventually", RunResult.Status.FAILED,
                -1, durationMs, List.of(), error), childResults);
    }

    private String firstUnknownConditionType(PlanNode.EventuallyNode group) {
        for (PlanNode.Condition condition : group.conditions()) {
            if (!assertionProviders.containsKey(condition.assertionType())) {
                return condition.assertionType();
            }
        }
        return null;
    }

    /**
     * The conditions' parameters, resolved as expressions like an assertion's.
     * <p>
     * A condition comparing against a value an earlier step produced is exactly
     * what a polling block is usually for: wait until the record the API
     * returned appears somewhere else.
     */
    private List<Map<String, Object>> resolveConditionParams(
            PlanNode.EventuallyNode group, ExpressionContext expressionContext) {
        List<Map<String, Object>> resolved = new ArrayList<>(group.conditions().size());
        for (PlanNode.Condition condition : group.conditions()) {
            Map<String, Object> params =
                    parameters.resolve(condition.params(), expressionContext);
            // The same refusals an assertion gets. A condition is an assertion
            // that runs repeatedly, and a poll comparing against nothing would
            // simply never be satisfied — a timeout where the report should
            // have said the scenario named a value the run does not have.
            String refusal =
                    parameters.refusal(condition.params(), params, expressionContext);
            if (refusal != null) {
                throw new IllegalStateException(refusal);
            }
            resolved.add(params);
        }
        return resolved;
    }

    private List<AssertionResult> evaluateConditions(
            PlanNode.EventuallyNode group,
            List<Map<String, Object>> conditionParams,
            NodeEvidence evidence
    ) {
        if (evidence.hasError()) {
            return List.of();
        }
        List<AssertionResult> results = new ArrayList<>();
        for (int index = 0; index < group.conditions().size(); index++) {
            PlanNode.Condition condition = group.conditions().get(index);
            Map<String, Object> params = conditionParams.get(index);
            AssertionProvider provider = assertionProviders.get(condition.assertionType());
            results.add(provider.evaluate(
                    condition.assertionType(), params, evidence,
                    new AssertionContext(group.nodeId().value(), params)));
        }
        return results;
    }

    /**
     * Condition results share the order of the declared conditions, so the
     * journal can record which condition produced which outcome.
     */
    private void recordConditionOutcomes(
            PlanNode.EventuallyNode group,
            NodeContext context,
            List<AssertionResult> conditionResults
    ) {
        for (int i = 0; i < conditionResults.size(); i++) {
            AssertionResult condition = conditionResults.get(i);
            context.journal().assertionEvaluated(
                    group.nodeId(), group.conditions().get(i).assertionType(),
                    condition.outcome().name(), condition.message());
        }
    }

    /** Short, sanitized reason a poll did not satisfy its conditions. */
    private static String pollDetail(
            NodeEvidence evidence, List<AssertionResult> conditionResults) {
        if (evidence == null) {
            return "no poll completed";
        }
        if (evidence.hasError()) {
            return "request failed: " + evidence.error()
                    .map(NormalizedError::code).orElse("unknown");
        }
        return conditionResults.stream()
                .filter(condition -> condition.outcome() != AssertionResult.Outcome.PASS)
                .map(AssertionResult::message)
                .findFirst()
                .orElse("all conditions satisfied");
    }
}
