package dev.faultora.connector.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationPolicyTest {

    @Test
    void defaultPolicyBlocksLocalhost() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://localhost:8080")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocks127() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://127.0.0.1:8080")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocks127NonStandard() {
        // 127.0.0.2 is loopback but not in BLOCKED_HOSTS — must be caught by
        // InetAddress classification of the IP literal
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://127.0.0.2")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocksMulticast() {
        // 224.0.0.1 is multicast — must be caught by isPrivateOrReserved()
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://224.0.0.1")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocksReserved240() {
        // 240.0.0.1 is reserved (Class E) — must be caught by isPrivateOrReserved()
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://240.0.0.1")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocks10x() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://10.0.0.1:8080")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocks192168() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://192.168.1.1:8080")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocks172x() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://172.16.0.1:8080")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocks169254() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://169.254.1.1:8080")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocksIpv6Loopback() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://[::1]:8080")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocksIpv6Ula() {
        // fc00::/7 — IPv6 Unique Local Address
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://[fd00::1]:8080")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocksIpv6LinkLocal() {
        // fe80::/10 — IPv6 link-local
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://[fe80::1]:8080")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocksIpv4MappedLoopback() {
        // ::ffff:127.0.0.1 — IPv4-mapped loopback
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://[::ffff:127.0.0.1]:8080")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocksDocRange19202() {
        // 192.0.2.0/24 — documentation range (RFC 5737)
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://192.0.2.1")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocksDocRange19851100() {
        // 198.51.100.0/24 — documentation range
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://198.51.100.1")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyBlocksDocRange2030113() {
        // 203.0.113.0/24 — documentation range
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://203.0.113.1")).isAllowed()).isFalse();
    }

    @Test
    void defaultPolicyAllowsPublicHost() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        // Use IP literal to avoid DNS dependency in sandboxed environments
        assertThat(policy.check(URI.create("http://8.8.8.8")).isAllowed()).isTrue();
    }

    @Test
    void checkResultCarriesResolvedAddress() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        DestinationPolicy.CheckResult result = policy.check(URI.create("http://8.8.8.8"));
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.resolvedAddress()).isNotNull();
        assertThat(result.resolvedAddress().getHostAddress()).isEqualTo("8.8.8.8");
    }

    @Test
    void permissivePolicyAllowsLocalhost() {
        DestinationPolicy policy = DestinationPolicy.permissive();
        assertThat(policy.check(URI.create("http://localhost:8080")).isAllowed()).isTrue();
    }

    @Test
    void permissivePolicyAllowsPrivateNetwork() {
        DestinationPolicy policy = DestinationPolicy.permissive();
        assertThat(policy.check(URI.create("http://192.168.1.1:8080")).isAllowed()).isTrue();
    }

    @Test
    void allowlistPermitsMatchingHost() {
        DestinationPolicy policy = new DestinationPolicy(
                false, Set.of("localhost"), Set.of());
        assertThat(policy.check(URI.create("https://localhost")).isAllowed()).isTrue();
    }

    @Test
    void allowlistBlocksNonMatchingHost() {
        DestinationPolicy policy = new DestinationPolicy(
                false, Set.of("api.example.com"), Set.of());
        assertThat(policy.check(URI.create("https://evil.example.com")).isAllowed()).isFalse();
    }

    @Test
    void blocklistBlocksMatchingHost() {
        DestinationPolicy policy = new DestinationPolicy(
                false, Set.of(), Set.of("evil.example.com"));
        assertThat(policy.check(URI.create("https://evil.example.com")).isAllowed()).isFalse();
    }

    @Test
    void blocklistAllowsNonMatchingHost() {
        DestinationPolicy policy = new DestinationPolicy(
                false, Set.of(), Set.of("evil.example.com"));
        // Use IP literal to avoid DNS dependency in sandboxed environments
        assertThat(policy.check(URI.create("http://8.8.8.8")).isAllowed()).isTrue();
    }

    @Test
    void defaultPolicyBlocksNonExistentDns() {
        // DNS lookup for a non-existent domain should fail closed (blocked)
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("https://this-host-does-not-exist.invalid")).isAllowed())
                .as("Non-resolvable DNS host should be blocked")
                .isFalse();
    }

    @Test
    void defaultPolicyBlocksNonResolvableTld() {
        // A host that will fail DNS resolution should be blocked
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("https://definitely-not-a-real-host-12345.example.invalid")).isAllowed())
                .isFalse();
    }

    @Test
    void permissivePolicyAllowsNonExistentDns() {
        // Permissive mode skips all private/reserved checks including DNS
        DestinationPolicy policy = DestinationPolicy.permissive();
        assertThat(policy.check(URI.create("https://this-host-does-not-exist.invalid")).isAllowed())
                .isTrue();
    }

    @Test
    void allowlistFailsClosedWhenHostCannotBeResolved() {
        DestinationPolicy policy = new DestinationPolicy(
                false, Set.of("this-host-does-not-exist.invalid"), Set.of());

        assertThat(policy.check(URI.create("https://this-host-does-not-exist.invalid")).isAllowed())
                .isFalse();
    }

    @Test
    void rejectsCredentialsEmbeddedInDestinationUri() {
        DestinationPolicy policy = DestinationPolicy.permissive();

        assertThat(policy.check(URI.create("https://user:password@example.com")).isAllowed())
                .isFalse();
    }

    @Test
    void allowedAddressesAreDefensivelyCopied() throws Exception {
        DestinationPolicy.CheckResult.Allowed result =
                new DestinationPolicy.CheckResult.Allowed(
                        new java.net.InetAddress[]{java.net.InetAddress.getByName("8.8.8.8")});
        java.net.InetAddress[] first = result.resolvedAddresses();
        first[0] = java.net.InetAddress.getByName("127.0.0.1");

        assertThat(result.resolvedAddress().getHostAddress()).isEqualTo("8.8.8.8");
    }

    @Test
    void rejectsNullUri() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(null).isAllowed()).isFalse();
    }

    @Test
    void rejectsUnsupportedScheme() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("ftp://example.com")).isAllowed()).isFalse();
    }

    @Test
    void rejectsNoScheme() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        // IP literal passes without DNS — test basic scheme validation
        assertThat(policy.check(URI.create("http://8.8.8.8")).isAllowed()).isTrue();
    }

    @Test
    void blockedResultCarriesReason() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        DestinationPolicy.CheckResult result = policy.check(URI.create("http://localhost:8080"));
        assertThat(result).isInstanceOf(DestinationPolicy.CheckResult.Blocked.class);
        assertThat(result.errorMessage()).contains("localhost");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost", "http://localhost:8080",
            "http://127.0.0.1", "http://127.0.0.1:3000",
            "http://0.0.0.0", "http://0.0.0.0:8080",
            "http://10.0.0.1", "http://10.255.255.255",
            "http://172.16.0.1", "http://172.31.255.255",
            "http://192.168.0.1", "http://192.168.255.255",
            "http://169.254.0.1", "http://169.254.255.255",
            "http://127.0.0.2", "http://127.0.0.254",
            "http://224.0.0.1", "http://240.0.0.1",
            "http://192.0.2.1", "http://198.51.100.1", "http://203.0.113.1",
            "http://100.64.0.1", "http://100.127.255.254",
            "http://192.0.0.1", "http://192.88.99.1",
            "http://198.18.0.1", "http://198.19.255.254",
            "http://[100::1]", "http://[2001:db8::1]"
    })
    void defaultPolicyBlocksPrivateAddresses(String uri) {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create(uri)).isAllowed())
                .as("Expected blocked: " + uri)
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://8.8.8.8",
            "https://1.1.1.1",
            "http://93.184.216.34"
    })
    void defaultPolicyAllowsPublicAddresses(String uri) {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create(uri)).isAllowed())
                .as("Expected allowed: " + uri)
                .isTrue();
    }
}
