package dev.faultora.engine.exec;

import dev.faultora.engine.plan.PlanNode;
import dev.faultora.model.identifier.NodeId;

import java.util.Random;

/**
 * Deterministic retry backoff.
 * <p>
 * The delay before the next attempt is exponential in the failed-attempt
 * number, jittered from the run seed and node ID, and capped when the policy
 * sets a maximum. Deriving jitter from the seed rather than a clock is what
 * makes a seeded run replayable.
 */
public final class RetryBackoff {

    /** Jitter multiplies the delay by a factor in [1 - SPREAD, 1 + SPREAD). */
    private static final double SPREAD = 0.1;

    private RetryBackoff() {
    }

    public static long delayMs(
            PlanNode.RetrySpec retry, long seed, NodeId nodeId, int failedAttempt) {
        double delay = retry.backoffMs()
                * Math.pow(retry.backoffMultiplier(), failedAttempt - 1);
        Random jitterSource = new Random(
                seed ^ (31L * nodeId.value().hashCode()) ^ failedAttempt);
        delay *= (1 - SPREAD) + 2 * SPREAD * jitterSource.nextDouble();
        if (retry.maxBackoffMs() > 0) {
            delay = Math.min(delay, retry.maxBackoffMs());
        }
        return Math.max(0, Math.round(delay));
    }
}
