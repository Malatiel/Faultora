package dev.faultora.spi.contract;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.result.AssertionResult;

/**
 * Provider for evaluating assertions against collected evidence.
 * Assertions are side-effect free: they read evidence and return a verdict.
 */
public interface AssertionProvider {

    /**
     * The assertion type this provider handles (e.g. "status", "header", "jsonpath", "duration").
     */
    String type();

    /**
     * Evaluate an assertion against the provided evidence.
     *
     * @param assertionType  specific assertion type
     * @param params         assertion parameters from the scenario
     * @param evidence       read-only view of collected evidence
     * @param context        assertion context
     * @return assertion result with outcome and message
     */
    AssertionResult evaluate(
            String assertionType,
            java.util.Map<String, Object> params,
            EvidenceView evidence,
            AssertionContext context
    );
}
