package dev.faultora.runner.protocol;

/**
 * A named no.
 * <p>
 * Every rule a runner enforces can end a conversation, and a runner lives
 * inside a network the person reading the failure usually cannot reach. "The
 * connection dropped" tells them nothing; "this dispatch was signed by a key I
 * do not verify with" tells them which file to look at. So refusal is a value
 * with a reason, not an exception with a message, and the reasons are a closed
 * list two versions of this protocol can both understand.
 *
 * @param reason  which rule said no
 * @param detail  what a reader needs in order to act, with nothing secret in it
 */
public record Refusal(Reason reason, String detail) {

    /** The rules that can refuse. */
    public enum Reason {
        /** No protocol version in common. */
        UNSUPPORTED_PROTOCOL_VERSION,
        /** The peer's certificate is not one this side trusts. */
        UNTRUSTED_PEER,
        /** The effective policy is unsigned, or signed by an unknown key. */
        UNVERIFIED_POLICY,
        /** The policy would permit more than this deployment allows. */
        POLICY_EXCEEDS_LOCAL_LIMITS,
        /** This run id has been dispatched before, or was issued too long ago. */
        REPLAYED_DISPATCH,
        /** The documents do not hash to what the dispatch said they would. */
        DIGEST_MISMATCH,
        /** The runner has no extension the scenario needs. */
        MISSING_CAPABILITY,
        /**
         * The scenario arrived intact and cannot become a run here — it does
         * not parse, or it does not compile. Distinct from a digest mismatch,
         * which says the bytes are not the ones that were sent: reporting a
         * parse error as a hash failure sends the reader to look for tampering
         * that did not happen.
         */
        SCENARIO_INVALID,
        /** The runner is already executing a run and takes one at a time. */
        ALREADY_RUNNING,
        /** The lease was already expired when the dispatch arrived. */
        LEASE_EXPIRED
    }

    public Refusal {
        if (reason == null) {
            throw new IllegalArgumentException("A refusal without a reason is a dropped call");
        }
        detail = detail == null ? "" : detail;
    }

    /** Convenience for the common case of a reason and one sentence. */
    public static Refusal of(Reason reason, String detail) {
        return new Refusal(reason, detail);
    }

    /** The refusal as a line a person can act on. */
    public String describe() {
        return detail.isBlank() ? reason.name() : reason.name() + ": " + detail;
    }
}
