package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.Refusal;
import dev.faultora.runner.protocol.SignedPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a runner refuses, and what it agrees to run under.
 * <p>
 * Every one of these is a rule that has to hold when whoever is dispatching is
 * compromised, misconfigured, or simply newer and convinced it may ask for
 * more. None of them may depend on the other side behaving.
 */
class DispatchVerifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1alpha1
            kind: Scenario
            metadata:
              name: dispatched
            """;

    private static final LocalLimits LIMITS = new LocalLimits(
            Set.of("payments", "ledger"), Set.of(SafetyClassification.READ_ONLY,
            SafetyClassification.MUTATING), Set.of("staging"),
            Set.of("http-latency", "http-error"), 4, 60_000, 100, 1_048_576);

    private final DispatchVerifier verifier =
            new DispatchVerifier(LIMITS, policy -> "trusted".equals(policy.keyId()));

    private static TargetPolicy policyAsking(
            int concurrency, long durationMs, Set<String> faults) {
        return policyAsking(Set.of(new TargetId("payments")), concurrency, durationMs,
                faults, 1024, Set.of("staging"));
    }

    private static TargetPolicy policyAsking(
            Set<TargetId> targets, int concurrency, long durationMs,
            Set<String> faults, long payloadBytes, Set<String> environments) {
        return new TargetPolicy(
                targets, Set.of(SafetyClassification.READ_ONLY),
                50, concurrency, durationMs, payloadBytes, faults, environments);
    }

    private static SignedPolicy signed(TargetPolicy policy, String keyId) {
        try {
            return new SignedPolicy(MAPPER.writeValueAsString(policy), keyId, "c2ln");
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Dispatch dispatch(
            String runId, long issuedAt, SignedPolicy policy,
            String scenario, String scenarioDigest, String catalogDigest) {
        List<DispatchedDocument> documents = List.of(
                new DispatchedDocument("openapi", "openapi: 3.0.3"),
                new DispatchedDocument("asyncapi", "asyncapi: 3.0.0"));
        return new Dispatch(
                runId, issuedAt, "nonce", scenario, documents,
                Map.of("currency", "EUR"), Map.of(), Dispatch.Credentials.none(), 42L, policy,
                new Lease(issuedAt, 60_000, 10_000),
                scenarioDigest != null ? scenarioDigest : ContentDigest.sha256Uri(scenario),
                catalogDigest != null ? catalogDigest : digestOf(documents));
    }

    private static String digestOf(List<DispatchedDocument> documents) {
        return Dispatch.digestOfDocuments(documents);
    }

    private static Dispatch wellFormed(String runId, long issuedAt) {
        return dispatch(runId, issuedAt,
                signed(policyAsking(2, 30_000, Set.of("http-latency")), "trusted"),
                SCENARIO, null, null);
    }

    @Test
    void aWellFormedDispatchIsAcceptedUnderThePolicyItCarries() {
        long now = System.currentTimeMillis();

        DispatchVerifier.Verdict verdict = verifier.verify(wellFormed("run-1", now), now);

        assertThat(verdict.isAccepted()).isTrue();
        assertThat(verdict.policy().maxConcurrency()).isEqualTo(2);
        assertThat(verdict.policy().allowedFaultTypes()).containsExactly("http-latency");
    }

    @Test
    void theSameRunIsNotDispatchedTwice() {
        long now = System.currentTimeMillis();
        assertThat(verifier.verify(wellFormed("run-2", now), now).isAccepted()).isTrue();

        DispatchVerifier.Verdict again = verifier.verify(wellFormed("run-2", now), now);

        assertThat(again.refusal().reason()).isEqualTo(Refusal.Reason.REPLAYED_DISPATCH);
        assertThat(again.refusal().describe()).contains("dispatched here before");
    }

    @Test
    void aDispatchFromOutsideTheClockWindowIsRefused() {
        long now = System.currentTimeMillis();
        long longAgo = now - Dispatch.CLOCK_SKEW_ALLOWANCE_MS - 1;

        assertThat(verifier.verify(wellFormed("run-3", longAgo), now).refusal().reason())
                .isEqualTo(Refusal.Reason.REPLAYED_DISPATCH);
    }

    @Test
    void anUnsignedOrWronglySignedPolicyIsRefused() {
        long now = System.currentTimeMillis();
        Dispatch fromAnotherKey = dispatch("run-4", now,
                signed(policyAsking(2, 30_000, Set.of()), "some-other-key"),
                SCENARIO, null, null);

        DispatchVerifier.Verdict verdict = verifier.verify(fromAnotherKey, now);

        assertThat(verdict.refusal().reason()).isEqualTo(Refusal.Reason.UNVERIFIED_POLICY);
        assertThat(verdict.refusal().describe()).contains("does not verify with");
    }

    @Test
    void aPolicyAskingForMoreThanTheDeploymentPermitsIsRefused() {
        long now = System.currentTimeMillis();

        // Concurrency above the local floor.
        assertThat(verifier.verify(dispatch("run-5", now,
                signed(policyAsking(16, 30_000, Set.of()), "trusted"),
                SCENARIO, null, null), now).refusal().reason())
                .isEqualTo(Refusal.Reason.POLICY_EXCEEDS_LOCAL_LIMITS);

        // A duration above it.
        assertThat(verifier.verify(dispatch("run-6", now,
                signed(policyAsking(2, 600_000, Set.of()), "trusted"),
                SCENARIO, null, null), now).refusal().describe())
                .contains("600000ms", "60000ms");

        // A fault kind this deployment does not permit at all.
        assertThat(verifier.verify(dispatch("run-7", now,
                signed(policyAsking(2, 30_000, Set.of("network-reset")), "trusted"),
                SCENARIO, null, null), now).refusal().describe())
                .contains("network-reset");
    }

    @Test
    void aPolicyNamingAHostThisDeploymentDoesNotAllowIsRefused() {
        // "Which hosts" is the first thing ADR-021 says a runner's own
        // configuration states, and it was the dimension the comparison did not
        // have: a signed policy could point the run at anything.
        long now = System.currentTimeMillis();

        DispatchVerifier.Verdict elsewhere = verifier.verify(dispatch("run-12", now,
                signed(policyAsking(Set.of(new TargetId("someone-elses-database")),
                        2, 30_000, Set.of(), 1024, Set.of("staging")), "trusted"),
                SCENARIO, null, null), now);

        assertThat(elsewhere.refusal().reason())
                .isEqualTo(Refusal.Reason.POLICY_EXCEEDS_LOCAL_LIMITS);
        assertThat(elsewhere.refusal().describe()).contains("someone-elses-database");
    }

    @Test
    void aPolicyRestrictingNothingIsAskingForEverything() {
        // An empty allowlist means "any" in this policy, so an unrestricted
        // dispatch against a restricted runner is the widest possible ask, not
        // the narrowest. Comparing it like the other fields is how a narrowing
        // widens something.
        long now = System.currentTimeMillis();

        DispatchVerifier.Verdict anyTarget = verifier.verify(dispatch("run-13", now,
                signed(policyAsking(Set.of(), 2, 30_000, Set.of(), 1024, Set.of("staging")),
                        "trusted"),
                SCENARIO, null, null), now);

        assertThat(anyTarget.refusal().reason())
                .isEqualTo(Refusal.Reason.POLICY_EXCEEDS_LOCAL_LIMITS);
        assertThat(anyTarget.refusal().describe()).contains("any targets at all");
    }

    @Test
    void aPolicyWantingBiggerPayloadsOrAnotherEnvironmentIsRefused() {
        long now = System.currentTimeMillis();

        assertThat(verifier.verify(dispatch("run-14", now,
                signed(policyAsking(Set.of(new TargetId("payments")), 2, 30_000,
                        Set.of(), 64L * 1024 * 1024, Set.of("staging")), "trusted"),
                SCENARIO, null, null), now).refusal().describe())
                .contains("payloads of");

        assertThat(verifier.verify(dispatch("run-15", now,
                signed(policyAsking(Set.of(new TargetId("payments")), 2, 30_000,
                        Set.of(), 1024, Set.of("production")), "trusted"),
                SCENARIO, null, null), now).refusal().describe())
                .contains("production");
    }

    @Test
    void aRefusedDispatchDoesNotBurnItsRunId() {
        // A dispatch turned away for a signature the operator then fixes has to
        // be able to come back. Claiming the run id on arrival made the second,
        // correct attempt look like a replay — true of nothing and useful to
        // nobody.
        long now = System.currentTimeMillis();
        Dispatch wrongKey = dispatch("run-16", now,
                signed(policyAsking(2, 30_000, Set.of()), "some-other-key"),
                SCENARIO, null, null);

        assertThat(verifier.verify(wrongKey, now).refusal().reason())
                .isEqualTo(Refusal.Reason.UNVERIFIED_POLICY);

        DispatchVerifier.Verdict afterTheKeyWasFixed = verifier.verify(
                dispatch("run-16", now, signed(policyAsking(2, 30_000, Set.of()), "trusted"),
                        SCENARIO, null, null), now);

        assertThat(afterTheKeyWasFixed.isAccepted()).isTrue();
    }

    @Test
    void oneDocumentHashesTheSameWayAsSeveral() {
        // The failure ADR-020 predicted: a dispatcher passing a single
        // document's own digest through — the rule the catalog loader uses for
        // a catalog version — while the runner takes the list form. It would
        // refuse correct dispatches, and only single-document ones.
        long now = System.currentTimeMillis();
        List<DispatchedDocument> one = List.of(
                new DispatchedDocument("openapi", "openapi: 3.0.3"));

        Dispatch single = new Dispatch(
                "run-17", now, "nonce", SCENARIO, one, Map.of(), Map.of(),
                Dispatch.Credentials.none(), 42L,
                signed(policyAsking(2, 30_000, Set.of()), "trusted"),
                new Lease(now, 60_000, 10_000),
                ContentDigest.sha256Uri(SCENARIO), Dispatch.digestOfDocuments(one));

        assertThat(verifier.verify(single, now).isAccepted()).isTrue();
        assertThat(Dispatch.digestOfDocuments(one))
                .as("not the document's own digest, which is the trap")
                .isNotEqualTo(ContentDigest.sha256Uri(one.get(0).content()));
        assertThat(Dispatch.digestOfDocuments(List.of())).isNotBlank();
    }

    @Test
    void aPolicyAskingForLessThanTheLocalFloorIsHonoured() {
        // Narrowing is ordinary and must work: the runner enforces its own
        // limits and honours the dispatcher's on top, not the other way round.
        long now = System.currentTimeMillis();

        DispatchVerifier.Verdict verdict = verifier.verify(dispatch("run-8", now,
                signed(policyAsking(1, 5_000, Set.of("http-latency")), "trusted"),
                SCENARIO, null, null), now);

        assertThat(verdict.isAccepted()).isTrue();
        assertThat(verdict.policy().maxConcurrency()).isEqualTo(1);
        assertThat(verdict.policy().maxDurationMs()).isEqualTo(5_000);
    }

    @Test
    void aScenarioThatDoesNotHashToWhatWasPromisedIsRefused() {
        long now = System.currentTimeMillis();
        Dispatch tampered = dispatch("run-9", now,
                signed(policyAsking(2, 30_000, Set.of()), "trusted"),
                SCENARIO + "\n# and one more step nobody agreed to\n",
                ContentDigest.sha256Uri(SCENARIO), null);

        DispatchVerifier.Verdict verdict = verifier.verify(tampered, now);

        assertThat(verdict.refusal().reason()).isEqualTo(Refusal.Reason.DIGEST_MISMATCH);
        assertThat(verdict.refusal().describe()).contains("the scenario hashes to");
    }

    @Test
    void documentsInAnotherOrderAreAnotherCatalog() {
        // The catalog digest is taken over the documents' digests in the order
        // they were named, so a reordered dispatch is refused rather than
        // compiled into a catalog nobody asked for.
        long now = System.currentTimeMillis();
        List<DispatchedDocument> reversed = List.of(
                new DispatchedDocument("asyncapi", "asyncapi: 3.0.0"),
                new DispatchedDocument("openapi", "openapi: 3.0.3"));
        Dispatch reordered = new Dispatch(
                "run-10", now, "nonce", SCENARIO, reversed, Map.of(), Map.of(),
                Dispatch.Credentials.none(), 42L,
                signed(policyAsking(2, 30_000, Set.of()), "trusted"),
                new Lease(now, 60_000, 10_000),
                ContentDigest.sha256Uri(SCENARIO),
                digestOf(List.of(
                        new DispatchedDocument("openapi", "openapi: 3.0.3"),
                        new DispatchedDocument("asyncapi", "asyncapi: 3.0.0"))));

        assertThat(verifier.verify(reordered, now).refusal().reason())
                .isEqualTo(Refusal.Reason.DIGEST_MISMATCH);
    }

    @Test
    void aDispatchCarryingNoPolicyIsRefusedRatherThanRunUnprotected() {
        long now = System.currentTimeMillis();
        Dispatch unsigned = new Dispatch(
                "run-11", now, "nonce", SCENARIO, List.of(), Map.of(), Map.of(),
                Dispatch.Credentials.none(), 42L,
                null, new Lease(now, 60_000, 10_000),
                ContentDigest.sha256Uri(SCENARIO), digestOf(List.of()));

        assertThat(verifier.verify(unsigned, now).refusal().reason())
                .isEqualTo(Refusal.Reason.UNVERIFIED_POLICY);
    }
}
