package dev.faultora.runner.protocol;

import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.TargetPolicy;

/**
 * Everything a run is permitted, as the document that gets signed.
 * <p>
 * Two questions, and they are separate because they bound different things.
 * {@link TargetPolicy} bounds what a run may <b>do to a target</b> — which
 * operations, which faults, how many requests, for how long. {@link
 * EvidencePolicy} bounds what the runner may <b>keep</b> of what it saw. A
 * compiler needs the first and has no business with the second, which is why
 * this is a wrapper rather than a field added to the policy the compiler reads.
 * <p>
 * Evidence is here because it was missing and the absence had a shape: a
 * dispatch could not say how much of what a run sees may be held, so the runner
 * used a default nobody could change. <b>How much evidence a run may hold is a
 * limit an operator should be able to state</b>, and 1.0 freezes this protocol,
 * so it arrives now or it does not arrive. ADR-020 records both the gap and its
 * closing.
 *
 * @param targets what the run may do; never absent
 * @param evidence what the runner may keep, or null when the dispatcher did not
 *                 say — in which case the runner keeps what a local run keeps,
 *                 which is the only answer that does not make a scenario behave
 *                 differently for having been dispatched
 */
public record EffectivePolicy(TargetPolicy targets, EvidencePolicy evidence) {

    /** A policy that says only what a run may do. */
    public static EffectivePolicy of(TargetPolicy targets) {
        return new EffectivePolicy(targets, null);
    }
}
