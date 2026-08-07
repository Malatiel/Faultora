package dev.faultora.runtime;

import dev.faultora.model.security.EvidencePolicy;

import java.util.List;
import java.util.Set;

/**
 * How much of what a run sees is kept.
 * <p>
 * One definition, because two would be a scenario that passes locally and comes
 * back indeterminate from a runner. That is not a hypothetical: the runner
 * started with {@code EvidencePolicy.MINIMAL}, which captures no bodies and no
 * rows, so every {@code row-balance} and every {@code jsonpath} over a response
 * body would have been indeterminate remotely while passing on the machine the
 * scenario was written on. ADR-020 says local and remote runs cannot drift; an
 * evidence policy chosen separately in two places is exactly how they would.
 * <p>
 * <b>This is what applies when nobody said otherwise.</b> A dispatch may now
 * carry an evidence policy and a runner may state a floor of its own, and the
 * run gets the smaller of the two. This is the answer when neither side said
 * anything — deliberately this rather than the strictest possible policy,
 * because a run must not behave differently for having been dispatched.
 */
public final class RunEvidence {

    private RunEvidence() {
    }

    /** What a run keeps unless something says otherwise. */
    public static EvidencePolicy defaultPolicy() {
        return new EvidencePolicy(
                true, true,
                Set.of("authorization", "cookie", "set-cookie", "proxy-authorization"),
                10 * 1024 * 1024, 1000, List.of(), Set.of(), "session");
    }
}
