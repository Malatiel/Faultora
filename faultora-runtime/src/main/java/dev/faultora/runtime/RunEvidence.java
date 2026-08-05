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
 * <b>This is a default, not a contract.</b> A dispatch does not yet carry an
 * evidence policy — the signed policy is a {@code TargetPolicy}, which has no
 * evidence dimension — so a runner cannot be told to keep more or less than
 * this. How much evidence a run may hold is a limit an operator should be able
 * to state, so the protocol has to grow one before 1.0 freezes it; until then
 * the honest thing is the same default the CLI uses, and ADR-020 records the
 * gap rather than leaving the strictest possible policy in place by accident.
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
