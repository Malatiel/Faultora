package dev.faultora.runner;

import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.TargetPolicy;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <p>
 * <b>Every dimension of the policy is compared, and they do not all compare the
 * same way.</b> {@code TargetPolicy} uses an empty set for "no restriction" in
 * its allowlists and for "nothing permitted" in its fault types — the opposite
 * meanings for the same shape. Comparing them uniformly is how a narrowing
 * widens something, which is exactly what this class promised not to do while
 * quietly letting targets, environments, operation classes and payload size
 * through untouched.
 *
 * @param allowedTargets           targets a run may reach here; empty permits
 *                                 any, as the policy's own convention has it
 * @param allowedOperationClasses  safety classes a run may execute; empty
 *                                 permits any
 * @param allowedEnvironments      environments a run may name; empty permits any
 * @param allowedFaultTypes        fault kinds this deployment may inject at
 *                                 all; empty permits <em>none</em>
 * @param maxConcurrency           the most parallel work it will do
 * @param maxDurationMs            the longest any run may take here
 * @param maxRequests              the most requests any run may make here
 * @param maxPayloadBytes          the largest body it will send or keep
 */
public record LocalLimits(
        Set<String> allowedTargets,
        Set<SafetyClassification> allowedOperationClasses,
        Set<String> allowedEnvironments,
        Set<String> allowedFaultTypes,
        int maxConcurrency,
        long maxDurationMs,
        int maxRequests,
        long maxPayloadBytes
) {
    public LocalLimits {
        allowedTargets = allowedTargets == null ? Set.of() : Set.copyOf(allowedTargets);
        allowedOperationClasses = allowedOperationClasses == null
                ? Set.of() : Set.copyOf(allowedOperationClasses);
        allowedEnvironments = allowedEnvironments == null
                ? Set.of() : Set.copyOf(allowedEnvironments);
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
        String targets = beyondAllowlist("targets", allowedTargets,
                dispatched.allowedTargets().stream()
                        .map(TargetId::value).collect(Collectors.toSet()));
        if (targets != null) {
            return targets;
        }
        String classes = beyondAllowlist("operation classes",
                allowedOperationClasses.stream().map(Enum::name).collect(Collectors.toSet()),
                dispatched.allowedOperationClasses().stream()
                        .map(Enum::name).collect(Collectors.toSet()));
        if (classes != null) {
            return classes;
        }
        String environments = beyondAllowlist(
                "environments", allowedEnvironments, dispatched.allowedEnvironments());
        if (environments != null) {
            return environments;
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
        if (dispatched.maxPayloadBytes() > maxPayloadBytes) {
            return "payloads of " + dispatched.maxPayloadBytes()
                    + " bytes, and this runner permits " + maxPayloadBytes;
        }
        Set<String> notPermitted = new LinkedHashSet<>(dispatched.allowedFaultTypes());
        notPermitted.removeAll(allowedFaultTypes);
        if (!notPermitted.isEmpty()) {
            return "fault types " + notPermitted + ", which this runner does not permit";
        }
        return null;
    }

    /**
     * Why a dispatched allowlist reaches past a local one, or null when it does
     * not.
     * <p>
     * Empty means "no restriction" on both sides here, which makes the
     * comparison the opposite of the obvious one: a dispatch that restricts
     * <em>nothing</em> is asking for the most, not the least, and against a
     * runner that restricts something it is asking for more than it may have.
     */
    private static String beyondAllowlist(
            String what, Set<String> permittedHere, Set<String> asked) {
        if (permittedHere.isEmpty()) {
            return null;
        }
        if (asked.isEmpty()) {
            return "any " + what + " at all, and this runner permits only " + permittedHere;
        }
        Set<String> beyond = new LinkedHashSet<>(asked);
        beyond.removeAll(permittedHere);
        return beyond.isEmpty()
                ? null : what + " " + beyond + ", which this runner does not permit";
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
     * some field nobody thought to compare. Every field the policy has is
     * narrowed here; if the policy grows one, this stops compiling.
     */
    public TargetPolicy narrow(TargetPolicy dispatched) {
        return new TargetPolicy(
                narrowedAllowlist(allowedTargets, dispatched.allowedTargets(),
                        TargetId::value, TargetId::new),
                narrowedAllowlist(
                        allowedOperationClasses.stream().map(Enum::name)
                                .collect(Collectors.toSet()),
                        dispatched.allowedOperationClasses(),
                        Enum::name, SafetyClassification::valueOf),
                Math.min(dispatched.maxRequests(), maxRequests),
                Math.min(dispatched.maxConcurrency(), maxConcurrency),
                Math.min(dispatched.maxDurationMs(), maxDurationMs),
                Math.min(dispatched.maxPayloadBytes(), maxPayloadBytes),
                intersected(dispatched.allowedFaultTypes(), allowedFaultTypes),
                narrowedAllowlist(allowedEnvironments, dispatched.allowedEnvironments(),
                        environment -> environment, environment -> environment));
    }

    /**
     * An allowlist narrowed to the local one, honouring "empty means any".
     * <p>
     * A local list that restricts nothing leaves the dispatched one alone. A
     * local list that restricts something replaces an unrestricted dispatch
     * with itself, and intersects a restricted one — so the result can never
     * name something this deployment did not.
     */
    private static <T> Set<T> narrowedAllowlist(
            Set<String> permittedHere,
            Set<T> asked,
            java.util.function.Function<T, String> name,
            java.util.function.Function<String, T> parse) {
        if (permittedHere.isEmpty()) {
            return asked;
        }
        if (asked.isEmpty()) {
            return permittedHere.stream().map(parse)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return asked.stream().filter(item -> permittedHere.contains(name.apply(item)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> intersected(Set<String> asked, Set<String> permittedHere) {
        Set<String> both = new LinkedHashSet<>(asked);
        both.retainAll(permittedHere);
        return both;
    }
}
