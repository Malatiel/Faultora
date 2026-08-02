package dev.faultora.runner.protocol;

/**
 * The answer to a registration: a session, or a reason there is none.
 * <p>
 * One type with two shapes rather than an exception, because a refusal is an
 * ordinary outcome here — a runner that speaks an older protocol than the
 * dispatcher is a deployment mid-upgrade, not a fault.
 *
 * @param protocolVersion the version both sides settled on, null when refused
 * @param sessionId       identifies this registration in later messages, null
 *                        when refused
 * @param refusal         why there is no session, null when there is one
 */
public record Session(String protocolVersion, String sessionId, Refusal refusal) {

    /** A registration that was accepted. */
    public static Session accepted(String protocolVersion, String sessionId) {
        return new Session(protocolVersion, sessionId, null);
    }

    /** A registration that was not. */
    public static Session refused(Refusal refusal) {
        return new Session(null, null, refusal);
    }

    public boolean isAccepted() {
        return refusal == null;
    }

    /**
     * Answer a registration by negotiating a version.
     * <p>
     * The whole of protocol negotiation, in one place so that both sides of a
     * test are looking at the same rule.
     */
    public static Session answer(Registration registration, String sessionId) {
        if (registration == null) {
            return refused(Refusal.of(Refusal.Reason.UNSUPPORTED_PROTOCOL_VERSION,
                    "the registration was empty"));
        }
        String agreed = ProtocolVersion.negotiate(registration.protocols());
        if (agreed == null) {
            return refused(Refusal.of(Refusal.Reason.UNSUPPORTED_PROTOCOL_VERSION,
                    "runner '" + registration.runnerId() + "' speaks "
                            + registration.protocols() + " and this side speaks "
                            + ProtocolVersion.SUPPORTED));
        }
        return accepted(agreed, sessionId);
    }
}
