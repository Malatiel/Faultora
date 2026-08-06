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
        long now = System.currentTimeMillis();
        Lease lease = new Lease(now, 30_000, 5_000);

        assertThat(lease.expiresAtEpochMs()).isEqualTo(now + 30_000);
        assertThat(lease.holdsAt(now)).isTrue();
        assertThat(lease.holdsAt(now + 29_999)).isTrue();
        assertThat(lease.holdsAt(now + 30_000)).isFalse();
        assertThat(lease.remainingMs(now + 60_000)).isZero();
        assertThat(lease.renewedAt(now + 20_000).holdsAt(now + 45_000)).isTrue();
    }

    @Test
    void aLeaseThatCannotBeRenewedInTimeIsRefusedWithARealisticClock() {
        // The check this replaces compared an interval with an epoch
        // millisecond, so it could never be true and a five-second lease
        // renewed every minute was accepted in silence. These are the
        // timestamps a deployment actually has.
        long now = System.currentTimeMillis();

        assertThatThrownBy(() -> new Lease(now, 5_000, 60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be renewed every 60000ms");
        assertThatThrownBy(() -> new Lease(now, 30_000, 30_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Lease(now, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Lease(0, 30_000, 5_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRunnerStopsByItsOwnClockRatherThanTheDispatchersExpiry() {
        // A lease is a bound, and a bound a wrong clock can widen is not one.
        // The runner is an hour behind here; it still stops thirty seconds
        // after it received the dispatch.
        long dispatcherNow = System.currentTimeMillis();
        long runnerReceivedAt = dispatcherNow - 3_600_000;
        Lease lease = new Lease(dispatcherNow, 30_000, 5_000);

        assertThat(lease.deadlineFrom(runnerReceivedAt))
                .isEqualTo(runnerReceivedAt + 30_000);
        assertThat(lease.deadlineFrom(runnerReceivedAt))
                .as("a skewed clock cannot buy the run an extra hour")
                .isLessThan(lease.expiresAtEpochMs());
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
        assertThat(received.lease().ttlMs()).isEqualTo(60_000);
        // Handle names, and a user name that is useless without the password
        // it does not travel with.
        assertThat(received.credentials().authSecretId()).isEqualTo("api-token");
        assertThat(received.credentials().databaseUser()).isEqualTo("faultora_readonly");
        assertThat(received.credentials().databaseSecretId()).isEqualTo("ledger-password");
    }

    @Test
    void aDispatchThatAuthenticatesToNothingSaysSoRatherThanCarryingNull() {
        Dispatch anonymous = new Dispatch(
                "run-1", System.currentTimeMillis(), "nonce", "kind: Scenario",
                List.of(), Map.of(), Map.of(), null, 1L,
                new SignedPolicy("{}", "key", "sig"),
                new Lease(System.currentTimeMillis(), 1_000, 100),
                "sha256:a", "sha256:b");

        assertThat(anonymous.credentials()).isEqualTo(Dispatch.Credentials.none());
    }

    @Test
    void aSessionSurvivesTheWireBothWays() throws Exception {
        // It did not. A derived isAccepted() was serialized as a field the
        // record has no component for, so a session could be sent and not
        // read back — found by the first test that put one on a socket, which
        // is later than a protocol module should find such a thing.
        Session accepted = Session.answer(speaking(ProtocolVersion.CURRENT), "session-1");
        Session refused = Session.answer(speaking("99"), "session-2");

        Session receivedAccepted = mapper.readValue(
                mapper.writeValueAsString(accepted), Session.class);
        Session receivedRefusal = mapper.readValue(
                mapper.writeValueAsString(refused), Session.class);

        assertThat(receivedAccepted.isAccepted()).isTrue();
        assertThat(receivedAccepted.sessionId()).isEqualTo("session-1");
        assertThat(receivedRefusal.isAccepted()).isFalse();
        assertThat(receivedRefusal.refusal().reason())
                .isEqualTo(Refusal.Reason.UNSUPPORTED_PROTOCOL_VERSION);
    }

    @Test
    void everyMessageThisProtocolSendsCanBeReadBack() throws Exception {
        // One test that says the rule rather than the instance: a message with
        // a derived accessor Jackson mistakes for a property is a message that
        // cannot make the return trip.
        Object[] messages = {
                new Registration("r", "0.9.0", List.of("1"), Set.of("http")),
                Session.accepted("1", "s"),
                Session.refused(Refusal.of(Refusal.Reason.UNTRUSTED_PEER, "no")),
                new Refusal(Refusal.Reason.LEASE_EXPIRED, "gone"),
                new Lease(System.currentTimeMillis(), 1_000, 100),
                new SignedPolicy("{}", "key", "sig"),
                new DispatchedDocument("openapi", "openapi: 3.0.3"),
                new Progress("run", 0, List.of("{}")),
                dispatchIssuedAt(System.currentTimeMillis())
        };
        for (Object message : messages) {
            String json = mapper.writeValueAsString(message);
            assertThat(mapper.readValue(json, message.getClass()))
                    .as(message.getClass().getSimpleName())
                    .isEqualTo(message);
        }
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
                new Dispatch.Credentials("api-token", "faultora_readonly", "ledger-password"),
                88001L,
                new SignedPolicy("{\"maxConcurrency\":4}", "key-1", "c2lnbmF0dXJl"),
                new Lease(issuedAt, 60_000, 10_000),
                "sha256:aaa", "sha256:bbb");
    }
}
