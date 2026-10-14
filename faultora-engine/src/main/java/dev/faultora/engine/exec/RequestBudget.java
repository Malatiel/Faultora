package dev.faultora.engine.exec;

import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.engine.plan.PlanNode;

import java.util.concurrent.atomic.AtomicLong;

/** Run-scoped connector attempts; cleanup owns a reserved part of the same limit. */
public final class RequestBudget {
    private final AtomicLong remaining;
    private final AtomicLong cleanupRemaining;
    private final AtomicLong started;

    private RequestBudget(AtomicLong remaining, AtomicLong cleanupRemaining, AtomicLong started) {
        this.remaining = remaining;
        this.cleanupRemaining = cleanupRemaining;
        this.started = started;
    }

    public static RequestBudget forPlan(ExecutionPlan plan) {
        long limit = plan.targetPolicy().maxRequests();
        long cleanup = Math.min(limit, plan.nodes().stream().filter(PlanNode.CleanupNode.class::isInstance).count());
        return new RequestBudget(new AtomicLong(limit - cleanup), new AtomicLong(cleanup), new AtomicLong());
    }

    /** Zero means refused; a positive number identifies the granted attempt within the run. */
    public long startAttempt() {
        long previous = remaining.getAndUpdate(value -> value > 0 ? value - 1 : 0);
        return previous > 0 ? started.incrementAndGet() : 0;
    }

    public boolean exhausted() { return remaining.get() == 0; }

    public RequestBudget forCleanup() {
        return new RequestBudget(cleanupRemaining, cleanupRemaining, started);
    }
}
