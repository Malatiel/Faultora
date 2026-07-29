package dev.faultora.net;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The destination rule every connector shares.
 * <p>
 * These tests use literals and {@code localhost} rather than names that need
 * resolving, so the offline build decides the same way as a connected one.
 */
class HostPolicyTest {

    @Test
    void theDefaultPolicyRefusesWhatPointsBackAtTheRunner() {
        HostPolicy policy = HostPolicy.defaultPolicy();

        assertThat(policy.check("localhost").reason())
                .contains("Private/reserved host blocked");
        assertThat(policy.check("127.0.0.1").reason())
                .contains("Private/reserved");
        assertThat(policy.check("10.0.0.1").reason())
                .contains("Private/reserved IP literal blocked");
        assertThat(policy.check("[::1]").reason())
                .contains("Private/reserved");
        assertThat(policy.check("169.254.169.254").reason())
                .as("the cloud metadata address is the classic SSRF destination")
                .contains("Private/reserved");
    }

    @Test
    void theDefaultPolicyReachesAPublicAddress() {
        HostPolicy.Decision decision = HostPolicy.defaultPolicy().check("8.8.8.8");

        assertThat(decision.isReachable()).isTrue();
        assertThat(((HostPolicy.Decision.Reachable) decision).addresses()).hasSize(1);
    }

    @Test
    void anAllowlistDecidesAloneAndSkipsClassification() {
        // The documented semantic: an operator who allowlists an internal host
        // means that host. Refusing it for being internal would make the
        // allowlist useless in the networks this tool is built to run in.
        HostPolicy policy = new HostPolicy(false, Set.of("localhost"), Set.of());

        assertThat(policy.check("localhost").isReachable()).isTrue();
        assertThat(policy.check("8.8.8.8").reason())
                .as("a public host off the list is still refused")
                .contains("not in the allowlist");
    }

    @Test
    void aBlockedHostIsRefusedEvenWhenItIsAlsoAllowed() {
        HostPolicy policy = new HostPolicy(
                true, Set.of("localhost"), Set.of("localhost"));

        assertThat(policy.check("localhost").reason())
                .contains("explicitly blocked");
    }

    @Test
    void allowingPrivateNetworksReachesTheRunnersOwnMachine() {
        HostPolicy.Decision decision = HostPolicy.permissive().check("127.0.0.1");

        assertThat(decision.isReachable()).isTrue();
        assertThat(HostPolicy.permissive().allowsPrivateNetworks()).isTrue();
    }

    @Test
    void anUnparseableLiteralIsRefusedRatherThanTreatedAsPublic() {
        assertThat(HostPolicy.defaultPolicy().check("1.2.3.4.5.6").reason())
                .isNotNull();
        assertThat(HostPolicy.defaultPolicy().check("").reason())
                .contains("No host");
    }
}
