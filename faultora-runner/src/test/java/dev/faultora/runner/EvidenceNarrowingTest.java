package dev.faultora.runner;

import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runtime.RunEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How much a runner keeps, when two sides have said different things.
 * <p>
 * A deployment states what it will hold on to and a dispatch states what it
 * wants held; the run gets the smaller of the two. Unlike every other
 * dimension, a dispatch asking for more evidence than the floor is narrowed
 * rather than refused — keeping less cannot harm anybody's system, and refusing
 * would make "no response bodies leave this network" unusable against a
 * dispatcher that had not been told about it.
 */
class EvidenceNarrowingTest {

    private static final TargetPolicy TARGETS = new TargetPolicy(
            Set.of(), Set.of(SafetyClassification.READ_ONLY),
            100, 2, 60_000, 1024, Set.of(), Set.of());

    private static LocalLimits keeping(EvidencePolicy evidence) {
        return new LocalLimits(
                Set.of(), Set.of(SafetyClassification.READ_ONLY), Set.of(), Set.of(),
                10, 300_000, 1000, 1_048_576, evidence);
    }

    private static EvidencePolicy narrowed(EvidencePolicy floor, EvidencePolicy asked) {
        return keeping(floor).narrow(new EffectivePolicy(TARGETS, asked)).evidence();
    }

    private static EvidencePolicy keeps(boolean bodies, long maxBytes, int maxRows) {
        return new EvidencePolicy(
                bodies, true, Set.of(), maxBytes, maxRows, List.of(), Set.of(), "session");
    }

    @Test
    void aDispatchThatSaysNothingGetsWhatALocalRunKeeps() {
        // The strictest possible default is the safer-looking answer and is the
        // bug this system already had: MINIMAL captures no bodies and no rows,
        // so every row-balance and every body jsonpath came back indeterminate
        // from a runner while passing where the scenario was written.
        EvidencePolicy kept = narrowed(RunEvidence.defaultPolicy(), null);

        assertThat(kept.captureBodies()).isTrue();
        assertThat(kept.maxRows()).isEqualTo(RunEvidence.defaultPolicy().maxRows());
    }

    @Test
    void aRunnerThatKeepsNoBodiesKeepsNoneWhateverItIsAsked() {
        EvidencePolicy kept = narrowed(
                keeps(false, 0, 1000), keeps(true, 10_000_000, 1000));

        assertThat(kept.captureBodies())
                .as("the deployment's answer wins over the dispatcher's").isFalse();
    }

    @Test
    void aDispatchMayAskForLessThanTheRunnerPermits() {
        EvidencePolicy kept = narrowed(
                keeps(true, 10_000_000, 1000), keeps(false, 0, 10));

        assertThat(kept.captureBodies()).isFalse();
        assertThat(kept.maxRows()).isEqualTo(10);
    }

    @Test
    void aCeilingNobodyStatedDoesNotBecomeTheCeiling() {
        // The trap, and it fails in the dangerous direction. A policy stating
        // no body limit carries zero, and the capture code reads zero as "do
        // not truncate" — so Math.min of zero and a real limit is zero, and
        // narrowing would have widened. Both orders are checked because only
        // one of them would have been caught by accident.
        assertThat(narrowed(keeps(true, 4_096, 1000), keeps(true, 0, 0)).maxBodyBytes())
                .as("the dispatch stated no limit, so the runner's holds")
                .isEqualTo(4_096);
        assertThat(narrowed(keeps(true, 0, 0), keeps(true, 4_096, 50)).maxBodyBytes())
                .as("the runner stated no limit, so the dispatch's holds")
                .isEqualTo(4_096);
        assertThat(narrowed(keeps(true, 0, 0), keeps(true, 4_096, 50)).maxRows())
                .isEqualTo(50);
    }

    @Test
    void theSmallerOfTwoStatedCeilingsIsTheOneThatHolds() {
        assertThat(narrowed(keeps(true, 4_096, 100), keeps(true, 8_192, 20)).maxBodyBytes())
                .isEqualTo(4_096);
        assertThat(narrowed(keeps(true, 4_096, 100), keeps(true, 8_192, 20)).maxRows())
                .isEqualTo(20);
    }

    @Test
    void redactionIsAddedTogetherRatherThanChosenBetween() {
        // More redaction is narrower, so both sides' paths apply. Taking one
        // side's list would let a dispatch drop a path the deployment requires.
        EvidencePolicy floor = new EvidencePolicy(
                true, true, Set.of(), 0, 0, List.of("body.pan"), Set.of(), "session");
        EvidencePolicy asked = new EvidencePolicy(
                true, true, Set.of(), 0, 0, List.of("body.email"), Set.of(), "session");

        assertThat(narrowed(floor, asked).redactPaths())
                .containsExactlyInAnyOrder("body.pan", "body.email");
    }

    @Test
    void aHeaderEitherSideDeniesIsDenied() {
        EvidencePolicy floor = new EvidencePolicy(
                true, true, Set.of("x-internal"), 0, 0, List.of(), Set.of(), "session");
        EvidencePolicy asked = new EvidencePolicy(
                true, true, Set.of("x-request-id"), 0, 0, List.of(), Set.of(), "session");

        assertThat(narrowed(floor, asked).headerDenylist())
                .contains("x-internal", "x-request-id", "authorization");
    }

    @Test
    void aContentTypeTheDeploymentDoesNotAllowIsNotKept() {
        EvidencePolicy floor = new EvidencePolicy(
                true, true, Set.of(), 0, 0, List.of(),
                Set.of("application/json"), "session");
        EvidencePolicy asked = new EvidencePolicy(
                true, true, Set.of(), 0, 0, List.of(),
                Set.of("application/json", "text/csv"), "session");

        assertThat(narrowed(floor, asked).contentTypeAllowlist())
                .containsExactly("application/json");
    }
}
