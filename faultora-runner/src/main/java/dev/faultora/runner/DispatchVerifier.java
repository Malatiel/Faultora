package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Refusal;
import dev.faultora.runner.protocol.SignedPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Everything a runner decides before it executes anything.
 * <p>
 * Validates dispatch freshness, lease, policy and content before admission.
 * Standalone callers use an in-memory replay window. DispatchedRun validates
 * here and reserves the execution in its durable work-directory ledger.
 */
public final class DispatchVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_NONCE_LENGTH = 128;
    private static final int MAX_REMEMBERED = 4096;

    private final LocalLimits limits;
    private final Predicate<SignedPolicy> signatureIsValid;

    private final List<Seen> accepted = new ArrayList<>();
    private long highWater = Long.MIN_VALUE;

    private record Seen(String runDigest, String nonceDigest, long issuedAt) { }

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
        Verdict verdict = validate(dispatch, receivedAtMs);
        if (!verdict.isAccepted()) return verdict;
        return remember(dispatch, receivedAtMs, verdict);
    }

    Verdict validate(Dispatch dispatch, long receivedAtMs) {
        if (dispatch == null) {
            return Verdict.refused(Refusal.of(Refusal.Reason.REPLAYED_DISPATCH,
                    "there was no dispatch"));
        }
        try {
            new RunId(dispatch.runId());
        } catch (IllegalArgumentException | NullPointerException invalidIdentity) {
            return Verdict.refused(Refusal.of(Refusal.Reason.REPLAYED_DISPATCH,
                    "dispatch requires a valid run identity"));
        }
        if (dispatch.nonce() == null || dispatch.nonce().length() > MAX_NONCE_LENGTH || dispatch.nonce().isBlank()) {
            return Verdict.refused(Refusal.of(Refusal.Reason.REPLAYED_DISPATCH,
                    "dispatch requires a nonblank nonce of at most 128 characters"));
        }
        if (!dispatch.issuedNear(receivedAtMs)) {
            return Verdict.refused(Refusal.of(Refusal.Reason.REPLAYED_DISPATCH,
                    "run '" + dispatch.runId() + "' was issued at "
                            + dispatch.issuedAtEpochMs() + " and it is now " + receivedAtMs
                            + " here; a dispatch is acted on within "
                            + Dispatch.CLOCK_SKEW_ALLOWANCE_MS + "ms of being issued"));
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
        EffectivePolicy dispatched;
        try {
            dispatched = MAPPER.readValue(
                    dispatch.policy().policyJson(), EffectivePolicy.class);
        } catch (Exception unreadable) {
            return Verdict.refused(Refusal.of(Refusal.Reason.UNVERIFIED_POLICY,
                    "the policy for run '" + dispatch.runId() + "' verified but could not "
                            + "be read: " + unreadable.getMessage()));
        }
        if (dispatched == null || dispatched.targets() == null) {
            return Verdict.refused(Refusal.of(Refusal.Reason.UNVERIFIED_POLICY,
                    "the policy for run '" + dispatch.runId() + "' verified and says "
                            + "nothing about what the run may do"));
        }
        String exceeded = limits.exceededBy(dispatched.targets());
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
        String catalogDigest = Dispatch.digestOfDocuments(dispatch.documents());
        if (!catalogDigest.equals(dispatch.catalogDigest())) {
            return Verdict.refused(Refusal.of(Refusal.Reason.DIGEST_MISMATCH,
                    "the documents hash to " + catalogDigest + " and the dispatch says "
                            + dispatch.catalogDigest()));
        }

        return Verdict.accepted(limits.narrow(dispatched));
    }

    private synchronized Verdict remember(Dispatch dispatch, long receivedAtMs, Verdict verdict) {
        highWater = Math.max(highWater, receivedAtMs);
        if (!dispatch.issuedNear(highWater)) {
            return Verdict.refused(Refusal.of(Refusal.Reason.REPLAYED_DISPATCH, "dispatch is outside the replay window"));
        }
        long skew = Dispatch.CLOCK_SKEW_ALLOWANCE_MS;
        long oldest = highWater < Long.MIN_VALUE + skew ? Long.MIN_VALUE : highWater - skew;
        accepted.removeIf(seen -> seen.issuedAt() < oldest);
        String runDigest = ContentDigest.sha256Uri(dispatch.runId());
        String nonceDigest = ContentDigest.sha256Uri(dispatch.nonce());
        for (Seen seen : accepted) {
            if (seen.runDigest().equals(runDigest) || seen.nonceDigest().equals(nonceDigest)) {
                return Verdict.refused(Refusal.of(Refusal.Reason.REPLAYED_DISPATCH,
                        "the run ID or nonce was dispatched here before"));
            }
        }
        if (accepted.size() >= MAX_REMEMBERED) {
            return Verdict.refused(Refusal.of(Refusal.Reason.MISSING_CAPABILITY, "dispatch replay window is full"));
        }
        accepted.add(new Seen(runDigest, nonceDigest, dispatch.issuedAtEpochMs()));
        return verdict;
    }

    /**
     * What the runner decided.
     *
     * @param policy  the policy the run executes under, null when refused
     * @param refusal why it will not run, null when it will
     */
    public record Verdict(EffectivePolicy policy, Refusal refusal) {

        static Verdict accepted(EffectivePolicy policy) {
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
