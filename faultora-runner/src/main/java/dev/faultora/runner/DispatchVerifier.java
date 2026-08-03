package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.Refusal;
import dev.faultora.runner.protocol.SignedPolicy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Everything a runner decides before it executes anything.
 * <p>
 * Five rules, and each of them can say no with a reason. They are here together
 * rather than scattered through the transport because a runner lives inside a
 * network the person reading the failure usually cannot reach: what refused,
 * and why, is the only thing they will have.
 * <p>
 * The order matters and is the cheapest-first order that never leaks: a replay
 * is rejected before its signature is checked, and a signature before the
 * documents are hashed, so a dispatch nobody should have sent costs the runner
 * as little as possible.
 */
public final class DispatchVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LocalLimits limits;
    private final Predicate<SignedPolicy> signatureIsValid;

    /**
     * Run ids this runner has already accepted.
     * <p>
     * In memory, so a restarted runner will accept a replay of something it saw
     * before restarting. ADR-021 states that rather than hiding it: closing it
     * needs durable state whose own failure modes are worse than the window it
     * closes, and a restart is not something an attacker outside the network
     * can cause.
     */
    private final Set<String> accepted = ConcurrentHashMap.newKeySet();

    /**
     * @param limits          what this deployment permits, whatever it is told
     * @param signatureIsValid verifies a policy against the configured key —
     *                         injected because how a key is loaded is the
     *                         transport's business and whether a signature holds
     *                         is this class's
     */
    public DispatchVerifier(LocalLimits limits, Predicate<SignedPolicy> signatureIsValid) {
        this.limits = limits;
        this.signatureIsValid = signatureIsValid;
    }

    /**
     * Whether this dispatch may be executed, and under which policy.
     *
     * @param dispatch      what arrived
     * @param receivedAtMs  the runner's own clock, not the dispatcher's
     * @return the accepted run, or the reason there is none
     */
    public Verdict verify(Dispatch dispatch, long receivedAtMs) {
        if (dispatch == null) {
            return Verdict.refused(Refusal.of(Refusal.Reason.REPLAYED_DISPATCH,
                    "there was no dispatch"));
        }
        if (!dispatch.issuedNear(receivedAtMs)) {
            return Verdict.refused(Refusal.of(Refusal.Reason.REPLAYED_DISPATCH,
                    "run '" + dispatch.runId() + "' was issued at "
                            + dispatch.issuedAtEpochMs() + " and it is now " + receivedAtMs
                            + " here; a dispatch is acted on within "
                            + Dispatch.CLOCK_SKEW_ALLOWANCE_MS + "ms of being issued"));
        }
        if (!accepted.add(dispatch.runId())) {
            return Verdict.refused(Refusal.of(Refusal.Reason.REPLAYED_DISPATCH,
                    "run '" + dispatch.runId() + "' has been dispatched here before"));
        }
        if (dispatch.lease() == null
                || dispatch.lease().deadlineFrom(receivedAtMs) <= receivedAtMs) {
            return Verdict.refused(Refusal.of(Refusal.Reason.LEASE_EXPIRED,
                    "run '" + dispatch.runId() + "' arrived with no time left to run in"));
        }

        if (dispatch.policy() == null || !signatureIsValid.test(dispatch.policy())) {
            return Verdict.refused(Refusal.of(Refusal.Reason.UNVERIFIED_POLICY,
                    "the policy for run '" + dispatch.runId() + "' is unsigned or was "
                            + "signed by a key this runner does not verify with"));
        }
        TargetPolicy dispatched;
        try {
            dispatched = MAPPER.readValue(dispatch.policy().policyJson(), TargetPolicy.class);
        } catch (Exception unreadable) {
            return Verdict.refused(Refusal.of(Refusal.Reason.UNVERIFIED_POLICY,
                    "the policy for run '" + dispatch.runId() + "' verified but could not "
                            + "be read: " + unreadable.getMessage()));
        }
        String exceeded = limits.exceededBy(dispatched);
        if (exceeded != null) {
            return Verdict.refused(Refusal.of(Refusal.Reason.POLICY_EXCEEDS_LOCAL_LIMITS,
                    "run '" + dispatch.runId() + "' asks for " + exceeded));
        }

        String scenarioDigest = ContentDigest.sha256Uri(dispatch.scenario());
        if (!scenarioDigest.equals(dispatch.scenarioDigest())) {
            return Verdict.refused(Refusal.of(Refusal.Reason.DIGEST_MISMATCH,
                    "the scenario hashes to " + scenarioDigest + " and the dispatch says "
                            + dispatch.scenarioDigest()));
        }
        String catalogDigest = digestOfDocuments(dispatch);
        if (!catalogDigest.equals(dispatch.catalogDigest())) {
            return Verdict.refused(Refusal.of(Refusal.Reason.DIGEST_MISMATCH,
                    "the documents hash to " + catalogDigest + " and the dispatch says "
                            + dispatch.catalogDigest()));
        }

        return Verdict.accepted(limits.narrow(dispatched));
    }

    /**
     * The documents' digest, taken the way the loader takes it.
     * <p>
     * Over each document's digest, in the order they were named. The order is
     * part of the answer, which is why a dispatch carries them as a list.
     */
    private static String digestOfDocuments(Dispatch dispatch) {
        StringBuilder digests = new StringBuilder();
        for (DispatchedDocument document : dispatch.documents()) {
            digests.append(ContentDigest.sha256Uri(document.content())).append('\n');
        }
        return ContentDigest.sha256Uri(digests.toString());
    }

    /**
     * What the runner decided.
     *
     * @param policy  the policy the run executes under, null when refused
     * @param refusal why it will not run, null when it will
     */
    public record Verdict(TargetPolicy policy, Refusal refusal) {

        static Verdict accepted(TargetPolicy policy) {
            return new Verdict(policy, null);
        }

        static Verdict refused(Refusal refusal) {
            return new Verdict(null, refusal);
        }

        public boolean isAccepted() {
            return refusal == null;
        }
    }
}
