package dev.faultora.runner;

import dev.faultora.model.security.TargetPolicy;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What this deployment permits, whatever it is told.
 * <p>
 * The runner's own configuration, and the reason M4-02 says refusal is
 * independent of controller behaviour. A signed policy narrows this and can
 * never widen it: the runner is not enforcing somebody else's decision, it is
 * enforcing its own and honouring theirs on top.
 * <p>
 * That distinction is the whole security position of a runner inside a private
 * network. Whoever dispatches to it may be compromised, may be misconfigured,
 * or may simply be a newer version that believes it can ask for more. None of
 * those should be able to reach a host, inject a fault, or run for a duration
 * that the operator of <em>this</em> network did not permit.
 *
 * @param allowedFaultTypes fault kinds this deployment may inject at all
 * @param maxConcurrency    the most parallel work it will do
 * @param maxDurationMs     the longest any run may take here
 * @param maxRequests       the most requests any run may make here
 */
public record LocalLimits(
        Set<String> allowedFaultTypes,
        int maxConcurrency,
        long maxDurationMs,
        int maxRequests
) {
    public LocalLimits {
        allowedFaultTypes = allowedFaultTypes == null ? Set.of() : Set.copyOf(allowedFaultTypes);
    }

    /**
     * Whether a dispatched policy asks for more than this deployment allows.
     *
     * @return the first thing it asks too much of, or null when it asks for no
     *         more than is permitted
     */
    public String exceededBy(TargetPolicy dispatched) {
        if (dispatched == null) {
            return "the dispatch carried no policy";
        }
        if (dispatched.maxConcurrency() > maxConcurrency) {
            return "concurrency " + dispatched.maxConcurrency()
                    + ", and this runner permits " + maxConcurrency;
        }
        if (dispatched.maxDurationMs() > maxDurationMs) {
            return "a duration of " + dispatched.maxDurationMs()
                    + "ms, and this runner permits " + maxDurationMs + "ms";
        }
        if (dispatched.maxRequests() > maxRequests) {
            return "up to " + dispatched.maxRequests()
                    + " requests, and this runner permits " + maxRequests;
        }
        Set<String> notPermitted = new LinkedHashSet<>(dispatched.allowedFaultTypes());
        notPermitted.removeAll(allowedFaultTypes);
        if (!notPermitted.isEmpty()) {
            return "fault types " + notPermitted + ", which this runner does not permit";
        }
        return null;
    }

    /**
     * The policy this run actually executes under: the dispatched one, narrowed
     * to what is permitted here.
     * <p>
     * Narrowing rather than refusing where the two merely differ, because a
     * dispatcher asking for less than the local floor is ordinary and should
     * work. It is asking for <em>more</em> that is refused, and that is checked
     * before this is called — this method exists so that even a policy that
     * passed the check cannot leave a value wider than the local one through
     * some field nobody thought to compare.
     */
    public TargetPolicy narrow(TargetPolicy dispatched) {
        Set<String> faults = new LinkedHashSet<>(dispatched.allowedFaultTypes());
        faults.retainAll(allowedFaultTypes);
        return new TargetPolicy(
                dispatched.allowedTargets(),
                dispatched.allowedOperationClasses(),
                Math.min(dispatched.maxRequests(), maxRequests),
                Math.min(dispatched.maxConcurrency(), maxConcurrency),
                Math.min(dispatched.maxDurationMs(), maxDurationMs),
                dispatched.maxPayloadBytes(),
                faults,
                dispatched.allowedEnvironments());
    }
}
