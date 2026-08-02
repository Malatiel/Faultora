package dev.faultora.runner.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What two sides agree on before anything is dispatched, and what they do when
 * they cannot agree.
 * <p>
 * The refusal path is tested beside the happy one rather than after it. 1.0
 * freezes this protocol and a controller that wants a second version arrives at
 * 2.0; negotiation is the thing that lets it, and a negotiation whose failure
 * mode is untested is a negotiation nobody can rely on.
 */
class ProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Registration speaking(String... versions) {
        return new Registration("runner-1", "0.9.0", List.of(versions),
                Set.of("http", "kafka"));
    }

    @Test
    void twoSidesSpeakingTheSameVersionAgreeOnIt() {
        Session session = Session.answer(speaking(ProtocolVersion.CURRENT), "session-1");

        assertThat(session.isAccepted()).isTrue();
        assertThat(session.protocolVersion()).isEqualTo(ProtocolVersion.CURRENT);
        assertThat(session.sessionId()).isEqualTo("session-1");
    }

    @Test
    void theRunnersPreferenceWinsAmongVersionsBothSpeak() {
        // The runner is the side inside somebody's private network, and the
        // side that cannot be upgraded on a whim. Its order decides.
        Session session = Session.answer(
                speaking("99", ProtocolVersion.CURRENT), "session-2");

        assertThat(session.protocolVersion()).isEqualTo(ProtocolVersion.CURRENT);
    }

    @Test
    void noVersionInCommonIsANamedRefusalRatherThanASilence() {
        Session session = Session.answer(speaking("99", "100"), "session-3");

        assertThat(session.isAccepted()).isFalse();
        assertThat(session.refusal().reason())
                .isEqualTo(Refusal.Reason.UNSUPPORTED_PROTOCOL_VERSION);
        assertThat(session.refusal().describe())
                .contains("runner-1", "99", ProtocolVersion.CURRENT);
    }

    @Test
    void aRegistrationThatSaysNothingIsRefusedToo() {
        assertThat(Session.answer(null, "session-4").isAccepted()).isFalse();
        assertThat(Session.answer(speaking(), "session-5").refusal().reason())
                .isEqualTo(Refusal.Reason.UNSUPPORTED_PROTOCOL_VERSION);
    }

    @Test
    void aRefusalWithoutAReasonIsADroppedCall() {
        assertThatThrownBy(() -> new Refusal(null, "something went wrong"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLeaseHoldsUntilItDoesNot() {
        long now = 1_000_000L;
        Lease lease = new Lease(now + 30_000, 5_000);

        assertThat(lease.holdsAt(now)).isTrue();
        assertThat(lease.holdsAt(now + 29_999)).isTrue();
        assertThat(lease.holdsAt(now + 30_000)).isFalse();
        assertThat(lease.remainingMs(now + 60_000)).isZero();
        assertThat(lease.renewedUntil(now + 90_000).holdsAt(now + 60_000)).isTrue();
    }

    @Test
    void aLeaseThatCannotBeRenewedInTimeIsRefusedWhenItIsBuilt() {
        // A renewal interval at or past the expiry is a lease that ends before
        // anything can extend it, which is a configuration mistake rather than
        // a runtime surprise.
        assertThatThrownBy(() -> new Lease(1_000, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Lease(0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDispatchIsActedOnOnlyNearTheTimeItWasIssued() {
        long now = System.currentTimeMillis();
        Dispatch dispatch = dispatchIssuedAt(now);

        assertThat(dispatch.issuedNear(now)).isTrue();
        assertThat(dispatch.issuedNear(now + Dispatch.CLOCK_SKEW_ALLOWANCE_MS - 1)).isTrue();
        assertThat(dispatch.issuedNear(now + Dispatch.CLOCK_SKEW_ALLOWANCE_MS + 1)).isFalse();
        // From the future as well: an unsynchronized clock is not a licence.
        assertThat(dispatch.issuedNear(now - Dispatch.CLOCK_SKEW_ALLOWANCE_MS - 1)).isFalse();
    }

    @Test
    void aDispatchSurvivesTheWireWithItsDocumentsInOrder() throws Exception {
        // The catalog digest is taken over the documents' digests in the order
        // they were named, so an order the wire does not preserve is a digest
        // check that fails on correct dispatches.
        Dispatch sent = dispatchIssuedAt(System.currentTimeMillis());

        Dispatch received = mapper.readValue(
                mapper.writeValueAsString(sent), Dispatch.class);

        assertThat(received.documents()).extracting(DispatchedDocument::family)
                .containsExactly("openapi", "asyncapi", "observations");
        assertThat(received.inputs()).containsEntry("currency", "EUR");
        assertThat(received.targetRedirects()).containsEntry("ledger", "jdbc:postgresql://db/x");
        assertThat(received.seed()).isEqualTo(88001L);
        assertThat(received.policy().signature()).isEqualTo("c2lnbmF0dXJl");
        assertThat(received.lease().expiresAtEpochMs())
                .isEqualTo(sent.lease().expiresAtEpochMs());
    }

    @Test
    void progressKnowsWhereTheNextBatchStarts() throws Exception {
        Progress progress = new Progress("run-1", 12,
                List.of("{\"type\":\"NODE_STARTED\"}", "{\"type\":\"NODE_COMPLETED\"}"));

        Progress received = mapper.readValue(
                mapper.writeValueAsString(progress), Progress.class);

        assertThat(received.nextPosition()).isEqualTo(14);
        assertThat(received.eventLines()).hasSize(2);
    }

    private static Dispatch dispatchIssuedAt(long issuedAt) {
        return new Dispatch(
                "run-88001", issuedAt, "nonce-1",
                "apiVersion: faultora.dev/v1alpha1\nkind: Scenario\n",
                List.of(new DispatchedDocument("openapi", "openapi: 3.0.3"),
                        new DispatchedDocument("asyncapi", "asyncapi: 3.0.0"),
                        new DispatchedDocument("observations", "kind: Observations")),
                Map.of("currency", "EUR"),
                Map.of("ledger", "jdbc:postgresql://db/x"),
                88001L,
                new SignedPolicy("{\"maxConcurrency\":4}", "key-1", "c2lnbmF0dXJl"),
                new Lease(issuedAt + 60_000, 10_000),
                "sha256:aaa", "sha256:bbb");
    }
}
