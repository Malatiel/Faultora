package dev.faultora.examples.recovery;

/**
 * The payment provider, from this system's side of the wire.
 * <p>
 * It has three answers and the third is the interesting one. A charge that was
 * accepted and a charge that was refused are both outcomes: the system knows
 * what happened and can act. A charge whose response never arrived is not an
 * outcome at all — the money may have moved and the caller cannot tell. Every
 * retry policy, every ledger, and every reconciliation job in this class of
 * system exists because that third answer is possible.
 */
interface Provider {

    /** What this system learned about a charge. */
    enum Outcome {
        /** The provider took the charge and said so. */
        ACCEPTED,
        /** The provider refused it and said so. */
        REFUSED,
        /**
         * No answer arrived. The charge may or may not have been taken, and
         * only the provider can say which — later, when asked again.
         */
        UNKNOWN
    }

    /** Ask the provider to take a charge. */
    Outcome charge(String paymentId, long amount);

    /** Ask what the provider knows about a charge it may have taken. */
    Outcome outcomeOf(String paymentId);
}
