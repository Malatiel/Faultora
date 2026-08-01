package dev.faultora.examples.recovery;

/**
 * Which version of the payment system is running.
 * <p>
 * Every field is a property a correct implementation has, and every named
 * configuration below removes exactly one of them. That is the whole purpose of
 * this class: a reliability test that has never failed proves nothing, so each
 * gate scenario is run twice — against the correct system, where it must pass,
 * and against the one system missing the property that scenario is about, where
 * it must fail. A variant that broke two things at once would let a scenario
 * pass its gate for the wrong reason.
 *
 * @param idempotentConsumer  whether a command seen twice has one effect
 * @param doubleEntryLedger   whether every booking writes both of its entries
 * @param transactionalOutbox whether the event is written with the payment that
 *                            causes it, rather than after the commit
 * @param reconciling         whether a payment whose provider outcome is
 *                            unknown is resolved afterwards
 * @param providerRespondsToTheCharge whether the provider's response reaches
 *                            the caller — a provider that accepts a charge and
 *                            loses the response is the situation reconciliation
 *                            exists for, not a fault in this system
 */
public record SystemConfig(
        boolean idempotentConsumer,
        boolean doubleEntryLedger,
        boolean transactionalOutbox,
        boolean reconciling,
        boolean providerRespondsToTheCharge
) {
    /** Everything works. Every gate scenario must pass against this. */
    public static SystemConfig correct() {
        return new SystemConfig(true, true, true, true, true);
    }

    /** A command seen twice is booked twice. */
    public static SystemConfig nonIdempotentConsumer() {
        return new SystemConfig(false, true, true, true, true);
    }

    /** A booking writes the debit and forgets the credit. */
    public static SystemConfig singleEntryLedger() {
        return new SystemConfig(true, false, true, true, true);
    }

    /** The event is written after the commit, so a crash between them loses it. */
    public static SystemConfig nonTransactionalOutbox() {
        return new SystemConfig(true, true, false, true, true);
    }

    /**
     * The provider accepts the charge and the response never arrives.
     * <p>
     * This is not a broken variant. It is the situation the reconciliation
     * worker exists for, and the correct system resolves it.
     */
    public static SystemConfig lostProviderResponse() {
        return new SystemConfig(true, true, true, true, false);
    }

    /** The same lost response, with nobody to reconcile it afterwards. */
    public static SystemConfig lostProviderResponseAndNoReconciliation() {
        return new SystemConfig(true, true, true, false, false);
    }
}
